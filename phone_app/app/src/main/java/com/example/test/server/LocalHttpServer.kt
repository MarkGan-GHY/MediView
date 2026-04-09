package com.example.test.server

import android.content.Context
import android.util.Log
import com.example.test.data.DrugAnalyzeResponse
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.IOException
import com.example.test.service.LlmApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 手机端本地 HTTP 服务器。
 *
 * 架构说明：
 * 在本系统中，手机承担"服务端"角色而非"客户端"，原因如下：
 * - 眼镜端资源受限，适合做轻量的请求发起方（客户端）
 * - 手机算力更强，适合承载图像处理、规则判断等逻辑（服务端）
 * - 手机开启热点后拥有固定的局域网 IP（通常为 192.168.43.1），
 *   眼镜连入热点后只需访问该固定地址，无需服务发现
 *
 * 扩展指引：
 * - 接入真实药物识别模型 → 替换 [buildMockResponse] 中的逻辑即可
 * - 接入数据库记录历史 → 在 [handleAnalyzeDrug] 返回前调用 DAO 存储
 * - 新增其他接口 → 在 [serve] 的 when 分支中追加路由
 */
class LocalHttpServer(
    private val context: Context,
    port: Int,
    /** 真实大模型调用服务；传 null 时回退到模拟数据（方便无配置时测试）*/
    private val llmApiService: LlmApiService? = null,
    /** 每次收到请求时的回调，用于向 UI 层广播事件（在子线程调用） */
    private val onRequestReceived: (imageSize: Int, savePath: String) -> Unit,
    /** 日志回调 (message, level)，由上层广播到 UI */
    private val onLogEvent: (message: String, level: String) -> Unit = { _, _ -> }
) : NanoHTTPD("0.0.0.0", port) {

    companion object {
        private const val TAG = "LocalHttpServer"
        private const val ROUTE_ANALYZE = "/analyzeDrug"
        private const val MIME_JSON = "application/json"
        private const val MIME_JPEG = "image/jpeg"
    }

    private val gson = Gson()

    /**
     * NanoHTTPD 的请求入口，所有 HTTP 请求都经过此方法分发。
     */
    override fun serve(session: IHTTPSession): Response {
        val remoteIp = session.headers["http-client-ip"]
            ?: session.headers["remote-addr"]
            ?: "未知IP"
        Log.i(TAG, "收到请求: ${session.method} ${session.uri}")
        onLogEvent("收到连接：$remoteIp  ${session.method} ${session.uri}", "INFO")

        return when {
            session.method == Method.POST && session.uri == ROUTE_ANALYZE -> {
                handleAnalyzeDrug(session)
            }
            else -> {
                newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    MIME_JSON,
                    """{"success":false,"error":"未知接口: ${session.uri}"}"""
                )
            }
        }
    }

    /**
     * 处理 POST /analyzeDrug 请求。
     *
     * 图片接收方式：
     * NanoHTTPD 在 parseBody() 调用前不会读取 body，调用后：
     * - 对于 multipart/form-data：文件会被写入临时文件，通过 files map 取出路径
     * - 对于 application/octet-stream / image/jpeg（直接传二进制）：
     *   body 数据在 inputStream 中，需手动按 content-length 读取
     *
     * 眼镜端发送 image/jpeg 时采用直接传二进制方式，此处使用 inputStream 读取。
     */
    private fun handleAnalyzeDrug(session: IHTTPSession): Response {
        return try {
            val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
            if (contentLength <= 0) {
                onLogEvent("图片读取异常：content-length 为 0 或缺失", "WARN")
            }
            Log.i(TAG, "图片大小: $contentLength 字节")

            // 直接从 inputStream 读取原始图片字节，避免 parseBody() 的临时文件开销
            val imageBytes = readImageBytes(session, contentLength)

            val sizeKb = "%.1f".format(imageBytes.size / 1024.0)
            onLogEvent("收到图片 ${sizeKb}KB（${imageBytes.size}B）", "INFO")

            // 保存到缓存目录，便于 adb pull 调试，后续可删除此步骤
            val savedPath = saveImageToCache(imageBytes)
            val fileName = savedPath.substringAfterLast('/')
            Log.i(TAG, "图片已保存: $savedPath")
            onLogEvent("图片已保存：$fileName", "INFO")

            // 通知 UI 层（在工作线程，UI 层需切换到主线程更新）
            onRequestReceived(imageBytes.size, savedPath)

            // 调用大模型（有配置时）或返回模拟数据（无配置时）
            val response = if (llmApiService != null) {
                runBlocking(Dispatchers.IO) {
                    llmApiService.analyze(imageBytes, onLog = onLogEvent)
                }
            } else {
                onLogEvent("未配置大模型，使用模拟数据", "WARN")
                buildMockResponse()
            }
            val json = gson.toJson(response)
            Log.i(TAG, "返回结果: $json")

            if (response.success) {
                onLogEvent("返回结果给眼镜：${response.drugName}（置信度 ${"%.0f".format(response.confidence * 100)}%）", "INFO")
            } else {
                onLogEvent("返回错误响应给眼镜：${response.warningText}", "ERROR")
            }

            newFixedLengthResponse(Response.Status.OK, MIME_JSON, json)

        } catch (e: IOException) {
            Log.e(TAG, "处理请求时发生 IO 错误", e)
            onLogEvent("IO 错误：${e.message}", "ERROR")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_JSON,
                """{"success":false,"error":"服务器内部错误: ${e.message}"}"""
            )
        }
    }

    /**
     * 从 session 的 inputStream 中读取指定长度的字节。
     *
     * NanoHTTPD 的 inputStream 在 parseBody() 未调用时包含完整的请求 body。
     * 使用 content-length 作为读取上限，防止阻塞等待。
     */
    private fun readImageBytes(session: IHTTPSession, contentLength: Int): ByteArray {
        if (contentLength <= 0) return ByteArray(0)
        val buffer = ByteArray(contentLength)
        var totalRead = 0
        val inputStream = session.inputStream
        while (totalRead < contentLength) {
            val read = inputStream.read(buffer, totalRead, contentLength - totalRead)
            if (read == -1) break
            totalRead += read
        }
        return buffer.copyOf(totalRead)
    }

    /**
     * 将图片字节保存到应用缓存目录。
     *
     * 保存路径：/data/data/<packageName>/cache/drug_images/
     * 可通过 adb pull 拉取调试：
     *   adb pull /data/data/com.example.test/cache/drug_images/
     *
     * @return 保存后的文件绝对路径
     */
    private fun saveImageToCache(imageBytes: ByteArray): String {
        val dir = File(context.cacheDir, "drug_images").apply { mkdirs() }
        val file = File(dir, "img_${System.currentTimeMillis()}.jpg")
        file.writeBytes(imageBytes)
        return file.absolutePath
    }

    /**
     * 构造模拟的识别结果。
     *
     * 【扩展点】接入真实药物识别模型时，替换此函数：
     * - 可调用本地 TFLite/ONNX 模型推理
     * - 可调用云端 API（需联网权限）
     * - 函数签名建议改为 suspend，使用协程处理异步推理
     */
    private fun buildMockResponse(): DrugAnalyzeResponse {
        return DrugAnalyzeResponse(
            success = true,
            drugName = "测试药物",
            confidence = 0.95,
            warningText = "这是手机端返回的测试结果",
            needConfirm = true
        )
    }
}
