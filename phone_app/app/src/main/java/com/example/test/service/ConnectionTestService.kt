package com.example.test.service

import android.util.Log
import com.example.test.data.ApiConfig
import com.example.test.data.RequestFormatType
import com.example.test.network.TestResult
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 针对指定 ApiConfig 发送最小化请求，验证 endpoint 可达性和 API Key 有效性。
 *
 * 与 LlmApiService 的区别：
 * - 不依赖 NetworkRepository，直接接收 ApiConfig
 * - 不携带图片，只发纯文本消息（"Hi"），加 max_tokens:1 降低成本
 * - 只关心请求是否成功，不解析模型输出内容
 */
class ConnectionTestService {

    companion object {
        private const val TAG = "ConnectionTestService"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val TEST_MESSAGE = "Hi"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * 测试指定配置的连通性，返回 TestResult.Success 或 TestResult.Failure。
     * 在调用方的协程上下文中执行（ViewModel 已切换到 IO）。
     */
    fun test(config: ApiConfig): TestResult {
        return try {
            val start = System.currentTimeMillis()
            when (config.requestFormatType) {
                RequestFormatType.OPENAI_COMPATIBLE,
                RequestFormatType.CUSTOM -> testOpenAiCompatible(config)
                RequestFormatType.QWEN   -> testQwen(config)
                RequestFormatType.GEMINI -> testGemini(config)
            }
            val latency = System.currentTimeMillis() - start
            Log.d(TAG, "测试成功 [${config.name}] ${latency}ms")
            TestResult.Success(latency)
        } catch (e: Exception) {
            Log.w(TAG, "测试失败 [${config.name}]: ${e.message}")
            TestResult.Failure(e.message ?: "未知错误")
        }
    }

    // ---- OpenAI 兼容 ----

    private fun testOpenAiCompatible(config: ApiConfig) {
        val body = JsonObject().apply {
            addProperty("model", config.model)
            add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", TEST_MESSAGE)
                })
            })
            addProperty("max_tokens", 1)
        }
        execute(
            Request.Builder()
                .url(config.endpoint)
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        )
    }

    // ---- 通义千问 DashScope ----

    private fun testQwen(config: ApiConfig) {
        val body = JsonObject().apply {
            addProperty("model", config.model)
            add("input", JsonObject().apply {
                add("messages", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        add("content", JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("text", TEST_MESSAGE)
                            })
                        })
                    })
                })
            })
            add("parameters", JsonObject().apply {
                addProperty("max_tokens", 1)
            })
        }
        execute(
            Request.Builder()
                .url(config.endpoint)
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        )
    }

    // ---- Gemini ----

    private fun testGemini(config: ApiConfig) {
        val url = "${config.endpoint}?key=${config.apiKey}"
        val body = JsonObject().apply {
            add("contents", JsonArray().apply {
                add(JsonObject().apply {
                    add("parts", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("text", TEST_MESSAGE)
                        })
                    })
                })
            })
            add("generationConfig", JsonObject().apply {
                addProperty("maxOutputTokens", 1)
            })
        }
        execute(
            Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        )
    }

    // ---- 执行请求，非 2xx 抛异常 ----

    private fun execute(request: Request) {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val bodySnippet = response.body?.string()?.take(120) ?: ""
                throw Exception("HTTP ${response.code}：$bodySnippet")
            }
        }
    }
}
