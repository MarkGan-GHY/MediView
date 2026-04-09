package com.example.test.service

import android.util.Base64
import android.util.Log
import com.example.test.data.ApiConfig
import com.example.test.data.DrugAnalyzeResponse
import com.example.test.data.NetworkRepository
import com.example.test.data.RequestFormatType
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 负责向大模型 API 发送图片识别请求并解析结果。
 *
 * 设计原则：
 * - 与 LocalHttpServer 解耦：LocalHttpServer 只传入图片字节，不感知任何 LLM 细节
 * - 按 requestFormatType 分支构造请求，方便后续扩展新协议
 * - 模型被要求返回固定 JSON，解析失败时降级为纯文本模式
 *
 * 接入方式：
 *   HttpServerService 创建实例 → 传给 LocalHttpServer → 替换 buildMockResponse()
 */
class LlmApiService(private val repository: NetworkRepository) {

    companion object {
        private const val TAG = "LlmApiService"
        private const val USER_PROMPT = "请识别图片中的药物"

        /**
         * 追加到 system prompt 末尾，约束模型输出结构化 JSON。
         * 不写入 DataStore，在调用时动态拼接，不影响用户编辑的 Prompt 内容。
         */
        private const val JSON_FORMAT_INSTRUCTION = "\n\n" +
            "请严格以如下 JSON 格式返回，不要包含任何其他文字或 Markdown 标记：\n" +
            "{\"drugName\":\"药物名称\",\"confidence\":0.9," +
            "\"warningText\":\"注意事项摘要（100字以内）\",\"needConfirm\":false}"

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)   // LLM 响应可能较慢
        .writeTimeout(30, TimeUnit.SECONDS)  // 上传 Base64 图片
        .build()

    private val gson = Gson()

    // =========================================================================
    // 公开接口
    // =========================================================================

    /**
     * 发送图片到大模型并返回识别结果。
     * 在 IO 线程执行，可安全地从 runBlocking 调用（NanoHTTPD 工作线程）。
     *
     * @param onLog 日志回调 (message, level)，在调用线程触发，由上层广播到 UI
     */
    suspend fun analyze(
        imageBytes: ByteArray,
        onLog: (message: String, level: String) -> Unit = { _, _ -> }
    ): DrugAnalyzeResponse = withContext(Dispatchers.IO) {
        val configs = repository.configsFlow.first()
        val config = configs.firstOrNull { it.isDefault && it.enabled }
            ?: run {
                val msg = "无可用的默认 API 配置，请在「网络设置」中配置并设为默认"
                onLog(msg, "WARN")
                return@withContext errorResponse(msg)
            }

        onLog("开始调用大模型：${config.name}（${config.model}）", "INFO")

        val basePrompt = config.promptTemplate.takeIf { it.isNotBlank() }
            ?: repository.globalPromptFlow.first()
        val fullPrompt = basePrompt + JSON_FORMAT_INSTRUCTION

        val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

        val startMs = System.currentTimeMillis()
        return@withContext try {
            val result = when (config.requestFormatType) {
                RequestFormatType.OPENAI_COMPATIBLE,
                RequestFormatType.CUSTOM -> callOpenAiCompatible(config, fullPrompt, base64Image)
                RequestFormatType.QWEN   -> callQwen(config, fullPrompt, base64Image)
                RequestFormatType.GEMINI -> callGemini(config, fullPrompt, base64Image)
            }
            val elapsed = "%.1f".format((System.currentTimeMillis() - startMs) / 1000.0)
            if (result.success) {
                onLog("识别完成，耗时 ${elapsed}s：${result.drugName}（置信度 ${"%.0f".format(result.confidence * 100)}%）", "INFO")
            } else {
                onLog("大模型返回失败：${result.warningText}", "ERROR")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "LLM 调用失败", e)
            onLog("大模型调用异常：${e.message}", "ERROR")
            errorResponse("识别失败：${e.message}")
        }
    }

    // =========================================================================
    // 各协议请求构造
    // =========================================================================

    /** OpenAI 兼容格式（也用于 CUSTOM）*/
    private fun callOpenAiCompatible(
        config: ApiConfig,
        prompt: String,
        base64: String
    ): DrugAnalyzeResponse {
        val body = JsonObject().apply {
            addProperty("model", config.model)
            add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", prompt)
                })
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("content", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("type", "image_url")
                            add("image_url", JsonObject().apply {
                                addProperty("url", "data:image/jpeg;base64,$base64")
                            })
                        })
                        add(JsonObject().apply {
                            addProperty("type", "text")
                            addProperty("text", USER_PROMPT)
                        })
                    })
                })
            })
            add("response_format", JsonObject().apply {
                addProperty("type", "json_object")
            })
        }

        val request = Request.Builder()
            .url(config.endpoint)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .post(gson.toJson(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val raw = executeRequest(request)
        // choices[0].message.content
        val content = gson.fromJson(raw, JsonObject::class.java)
            .getAsJsonArray("choices")?.get(0)?.asJsonObject
            ?.getAsJsonObject("message")
            ?.get("content")?.asString
            ?: return errorResponse("无法解析 OpenAI 响应结构")

        return parseModelOutput(content)
    }

    /** 通义千问 DashScope 原生格式 */
    private fun callQwen(
        config: ApiConfig,
        prompt: String,
        base64: String
    ): DrugAnalyzeResponse {
        val body = JsonObject().apply {
            addProperty("model", config.model)
            add("input", JsonObject().apply {
                add("messages", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "system")
                        add("content", JsonArray().apply {
                            add(JsonObject().apply { addProperty("text", prompt) })
                        })
                    })
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        add("content", JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("image", "data:image/jpeg;base64,$base64")
                            })
                            add(JsonObject().apply {
                                addProperty("text", USER_PROMPT)
                            })
                        })
                    })
                })
            })
            add("parameters", JsonObject())
        }

        val request = Request.Builder()
            .url(config.endpoint)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .post(gson.toJson(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val raw = executeRequest(request)
        // output.choices[0].message.content[0].text
        val content = gson.fromJson(raw, JsonObject::class.java)
            .getAsJsonObject("output")
            ?.getAsJsonArray("choices")?.get(0)?.asJsonObject
            ?.getAsJsonObject("message")
            ?.getAsJsonArray("content")?.get(0)?.asJsonObject
            ?.get("text")?.asString
            ?: return errorResponse("无法解析 Qwen 响应结构")

        return parseModelOutput(content)
    }

    /** Google Gemini：API Key 放 URL 参数，不用 Authorization 头 */
    private fun callGemini(
        config: ApiConfig,
        prompt: String,
        base64: String
    ): DrugAnalyzeResponse {
        val url = "${config.endpoint}?key=${config.apiKey}"

        val body = JsonObject().apply {
            add("contents", JsonArray().apply {
                add(JsonObject().apply {
                    add("parts", JsonArray().apply {
                        add(JsonObject().apply {
                            add("inline_data", JsonObject().apply {
                                addProperty("mime_type", "image/jpeg")
                                addProperty("data", base64)
                            })
                        })
                        add(JsonObject().apply {
                            addProperty("text", "$prompt\n\n$USER_PROMPT")
                        })
                    })
                })
            })
            add("generationConfig", JsonObject().apply {
                addProperty("response_mime_type", "application/json")
            })
        }

        val request = Request.Builder()
            .url(url)
            .post(gson.toJson(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val raw = executeRequest(request)
        // candidates[0].content.parts[0].text
        val content = gson.fromJson(raw, JsonObject::class.java)
            .getAsJsonArray("candidates")?.get(0)?.asJsonObject
            ?.getAsJsonObject("content")
            ?.getAsJsonArray("parts")?.get(0)?.asJsonObject
            ?.get("text")?.asString
            ?: return errorResponse("无法解析 Gemini 响应结构")

        return parseModelOutput(content)
    }

    // =========================================================================
    // 工具函数
    // =========================================================================

    private fun executeRequest(request: Request): String {
        client.newCall(request).execute().use { response ->
            val bodyText = response.body?.string() ?: throw Exception("响应体为空")
            if (!response.isSuccessful) {
                Log.e(TAG, "API 错误 ${response.code}: $bodyText")
                throw Exception("API 返回 ${response.code}")
            }
            Log.d(TAG, "API 响应: ${bodyText.take(500)}")
            return bodyText
        }
    }

    /**
     * 解析模型输出的 JSON 字符串为 DrugAnalyzeResponse。
     * 支持去除 markdown 代码块包裹；解析失败时降级为纯文本模式。
     */
    private fun parseModelOutput(text: String): DrugAnalyzeResponse {
        val cleaned = text.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        return try {
            val obj = gson.fromJson(cleaned, JsonObject::class.java)
            DrugAnalyzeResponse(
                success     = true,
                drugName    = obj.get("drugName")?.asString    ?: "未知药物",
                confidence  = obj.get("confidence")?.asDouble  ?: 0.8,
                warningText = obj.get("warningText")?.asString ?: "",
                needConfirm = obj.get("needConfirm")?.asBoolean ?: false
            )
        } catch (e: Exception) {
            Log.w(TAG, "模型输出 JSON 解析失败，降级为纯文本: ${text.take(200)}")
            DrugAnalyzeResponse(
                success     = true,
                drugName    = "识别完成",
                confidence  = 0.7,
                warningText = text.take(300),
                needConfirm = true
            )
        }
    }

    private fun errorResponse(message: String) = DrugAnalyzeResponse(
        success     = false,
        drugName    = "",
        confidence  = 0.0,
        warningText = message,
        needConfirm = false
    )
}
