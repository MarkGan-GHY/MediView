package com.example.test.server

import android.content.Context
import android.util.Log
import com.example.test.data.DrugAnalyzeResponse
import com.example.test.reminder.ReminderPushQueue
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.IOException
import com.example.test.service.LlmApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
    /** 承载异步 LLM 调用的协程作用域，由 HttpServerService 注入并在服务停止时 cancel */
    private val analyzeScope: CoroutineScope,
    /** 每次收到请求时的回调，用于向 UI 层广播事件（在子线程调用） */
    private val onRequestReceived: (imageSize: Int, savePath: String) -> Unit,
    /** 日志回调 (message, level)，由上层广播到 UI */
    private val onLogEvent: (message: String, level: String) -> Unit = { _, _ -> }
) : NanoHTTPD("0.0.0.0", port) {

    companion object {
        private const val TAG = "LocalHttpServer"
        private const val ROUTE_ANALYZE = "/analyzeDrug"
        private const val ROUTE_ANALYZE_RESULT = "/analyzeResult"
        private const val ROUTE_PENDING_REMINDERS = "/pendingReminders"
        private const val ROUTE_ACK_REMINDER = "/ackReminder"
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
            session.method == Method.GET && session.uri == ROUTE_ANALYZE_RESULT -> {
                handleAnalyzeResult(session)
            }
            session.method == Method.GET && session.uri == ROUTE_PENDING_REMINDERS -> {
                handlePendingReminders()
            }
            session.method == Method.POST && session.uri == ROUTE_ACK_REMINDER -> {
                handleAckReminder(session)
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
     * GET /pendingReminders
     * 返回当前队列里待推送的服药提醒消息列表。
     */
    private fun handlePendingReminders(): Response {
        val list = ReminderPushQueue.snapshot(context)
        val json = gson.toJson(mapOf("success" to true, "reminders" to list))
        if (list.isNotEmpty()) {
            onLogEvent("眼镜拉取提醒：${list.size} 条", "INFO")
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, json)
    }

    /**
     * POST /ackReminder?messageId=xxx
     * 眼镜端确认收到并展示后，从队列移除该消息。
     */
    private fun handleAckReminder(session: IHTTPSession): Response {
        val messageId = session.parameters["messageId"]?.firstOrNull()
        if (messageId.isNullOrBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                MIME_JSON,
                """{"success":false,"error":"缺少 messageId 参数"}"""
            )
        }
        val ok = ReminderPushQueue.ack(context, messageId)
        onLogEvent("眼镜确认提醒：$messageId${if (ok) "" else "（未命中）"}", "INFO")
        return newFixedLengthResponse(
            Response.Status.OK,
            MIME_JSON,
            """{"success":$ok}"""
        )
    }

    /**
     * 处理 POST /analyzeDrug 请求（异步任务模式）。
     *
     * 协议变更（异步两段式）：
     *  - 本接口立刻返回 `{success:true, taskId:"uuid"}`，不再等待 LLM
     *  - LLM 调用在 [analyzeScope] 中后台执行，结果写入 [AnalyzeTaskStore]
     *  - 眼镜端通过 GET /analyzeResult?taskId=xxx 轮询拿结果
     *
     * 这样设计的原因：LLM 偶发慢响应（>15s）会让眼镜端 OkHttp 先超时丢弃结果，
     * 异步化后眼镜端短超时即可，等待过程通过轮询接口持续进行。
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

            // 保存到缓存目录，便于 adb pull 调试
            val savedPath = saveImageToCache(imageBytes)
            val fileName = savedPath.substringAfterLast('/')
            Log.i(TAG, "图片已保存: $savedPath")
            onLogEvent("图片已保存：$fileName", "INFO")

            onRequestReceived(imageBytes.size, savedPath)

            val taskId = AnalyzeTaskStore.submit()
            onLogEvent("创建识别任务：${taskId.take(8)}", "INFO")

            // 后台执行 LLM 调用；NanoHTTPD 请求线程立刻返回 taskId 给眼镜端
            analyzeScope.launch {
                val response = try {
                    if (llmApiService != null) {
                        llmApiService.analyze(imageBytes, onLog = onLogEvent)
                    } else {
                        onLogEvent("未配置大模型，使用模拟数据", "WARN")
                        buildMockResponse()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "LLM 调用异常", e)
                    onLogEvent("识别异常：${e.message}", "ERROR")
                    AnalyzeTaskStore.markError(taskId, e.message ?: e.javaClass.simpleName)
                    return@launch
                }
                AnalyzeTaskStore.markDone(taskId, response)
                if (response.success) {
                    onLogEvent("识别完成：${response.drugName}（任务 ${taskId.take(8)}）", "INFO")
                } else {
                    onLogEvent("识别失败：${response.contraindications}", "ERROR")
                }
            }

            val json = gson.toJson(mapOf("success" to true, "taskId" to taskId))
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
     * GET /analyzeResult?taskId=xxx
     *
     * 返回结构：
     *  - 任务不存在：`{"success":false,"error":"taskId 不存在或已过期"}`
     *  - RUNNING：`{"success":true,"status":"running","elapsedMs":12345}`
     *  - DONE：   `{"success":true,"status":"done","drugName":..,"usage":..,"dosage":..,"contraindications":..}`
     *  - ERROR：  `{"success":true,"status":"error","error":"..."}`
     */
    private fun handleAnalyzeResult(session: IHTTPSession): Response {
        val taskId = session.parameters["taskId"]?.firstOrNull()
        if (taskId.isNullOrBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                MIME_JSON,
                """{"success":false,"error":"缺少 taskId 参数"}"""
            )
        }
        val task = AnalyzeTaskStore.get(taskId)
            ?: return newFixedLengthResponse(
                Response.Status.OK,
                MIME_JSON,
                """{"success":false,"error":"taskId 不存在或已过期"}"""
            )

        val body: Map<String, Any?> = when (task.status) {
            AnalyzeTaskStore.Status.RUNNING -> mapOf(
                "success" to true,
                "status" to "running",
                "elapsedMs" to (System.currentTimeMillis() - task.startMs)
            )
            AnalyzeTaskStore.Status.DONE -> {
                val r = task.result
                if (r != null && r.success) {
                    mapOf(
                        "success" to true,
                        "status" to "done",
                        "drugName" to r.drugName,
                        "usage" to r.usage,
                        "dosage" to r.dosage,
                        "contraindications" to r.contraindications
                    )
                } else {
                    mapOf(
                        "success" to true,
                        "status" to "error",
                        "error" to (r?.contraindications ?: "识别失败")
                    )
                }
            }
            AnalyzeTaskStore.Status.ERROR -> mapOf(
                "success" to true,
                "status" to "error",
                "error" to (task.error ?: "未知错误")
            )
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, gson.toJson(body))
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
            success           = true,
            drugName          = "测试药物",
            usage             = "温水送服",
            dosage            = "一日三次，每次一片",
            contraindications = "不宜空腹服用，不宜与阿司匹林共服"
        )
    }
}
