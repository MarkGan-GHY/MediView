package com.example.glasstest.network

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "ReminderApiClient"

/**
 * 服药提醒 HTTP 客户端。
 *
 * GET  /pendingReminders → 拉取手机待推送提醒列表
 * POST /ackReminder?messageId=xxx → 确认收到
 */
class ReminderApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /** 单条提醒消息（与手机端 ReminderMessage 对齐） */
    data class ReminderMessage(
        val messageId: String,
        val reminderId: Long,
        val name: String,
        val timesPerDay: String,
        val amount: String,
        val unit: String,
        val scheduledTime: String,
        val enqueuedAt: Long,
        val isTest: Boolean = false
    )

    sealed class FetchResult {
        data class Success(val reminders: List<ReminderMessage>) : FetchResult()
        data class Failure(val reason: String) : FetchResult()
    }

    /** 阻塞拉取，调用方需在 IO 线程使用 */
    fun fetchPending(host: String, port: Int): FetchResult {
        val url = "http://$host:$port/pendingReminders"
        return try {
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return FetchResult.Failure("HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            val json = JSONObject(body)
            if (!json.optBoolean("success", false)) {
                return FetchResult.Failure("响应 success=false")
            }
            val arr = json.optJSONArray("reminders") ?: return FetchResult.Success(emptyList())
            val list = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ReminderMessage(
                    messageId = o.getString("messageId"),
                    reminderId = o.getLong("reminderId"),
                    name = o.getString("name"),
                    timesPerDay = o.optString("timesPerDay", ""),
                    amount = o.optString("amount", ""),
                    unit = o.optString("unit", ""),
                    scheduledTime = o.getString("scheduledTime"),
                    enqueuedAt = o.getLong("enqueuedAt"),
                    isTest = o.optBoolean("isTest", false)
                )
            }
            FetchResult.Success(list)
        } catch (e: Exception) {
            Log.w(TAG, "拉取提醒失败：${e.javaClass.simpleName}: ${e.message}")
            FetchResult.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    /** 阻塞 ack；调用方需在 IO 线程使用 */
    fun ack(host: String, port: Int, messageId: String): Boolean {
        val url = "http://$host:$port/ackReminder?messageId=$messageId"
        return try {
            val request = Request.Builder()
                .url(url)
                .post(ByteArray(0).toRequestBody(null))
                .build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.w(TAG, "ack 失败：${e.message}")
            false
        }
    }
}
