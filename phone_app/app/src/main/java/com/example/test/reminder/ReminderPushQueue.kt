package com.example.test.reminder

import android.content.Context
import android.util.Log
import com.example.test.model.ReminderMessage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 待推送给眼镜的提醒队列。
 *
 * 进程内单例（线程安全），同时落盘到 SharedPreferences，
 * 保证 alarm 触发后即使 App 进程被杀，重启后队列仍能恢复。
 *
 * 工作流：
 *  - alarm 触发 → [enqueue] 入队
 *  - 眼镜轮询 GET /pendingReminders → [snapshot] 读取
 *  - 眼镜确认 POST /ackReminder → [ack] 移除
 */
object ReminderPushQueue {

    private const val TAG = "ReminderPushQueue"
    private const val PREFS = "reminder_push_queue"
    private const val KEY_LIST = "messages"

    /** 队列里超过此时长的消息丢弃，避免眼镜断连后堆积过期提醒 */
    private const val EXPIRE_MS = 30 * 60 * 1000L  // 30 分钟

    private val gson = Gson()
    private val lock = Any()
    private val messages = mutableListOf<ReminderMessage>()
    private var loaded = false

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_LIST, null)
            if (!json.isNullOrEmpty()) {
                try {
                    val type = object : TypeToken<List<ReminderMessage>>() {}.type
                    val list: List<ReminderMessage> = gson.fromJson(json, type)
                    messages.clear()
                    messages.addAll(list)
                } catch (e: Exception) {
                    Log.e(TAG, "队列恢复失败，清空", e)
                }
            }
            loaded = true
        }
    }

    private fun persist(context: Context) {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LIST, gson.toJson(messages)).apply()
    }

    /**
     * 入队。
     *
     * @param bypassDedup 真闹钟链路（默认 false）按 reminderId+scheduledTime 去重，
     *   防止同一时刻 alarm 被多次触发导致重复入队；测试推送传 true，允许同一卡片
     *   在同一分钟内连点多次都能成功入队。
     */
    fun enqueue(context: Context, message: ReminderMessage, bypassDedup: Boolean = false) {
        ensureLoaded(context)
        synchronized(lock) {
            if (!bypassDedup) {
                val duplicate = messages.any {
                    it.reminderId == message.reminderId && it.scheduledTime == message.scheduledTime
                }
                if (duplicate) {
                    Log.i(TAG, "重复消息已忽略：${message.name} ${message.scheduledTime}")
                    return
                }
            }
            messages.add(message)
            persist(context)
            Log.i(TAG, "入队：${message.name} ${message.scheduledTime}（队列长度 ${messages.size}）")
        }
    }

    /** 读取当前队列（按入队时间正序）；同时清理过期消息 */
    fun snapshot(context: Context): List<ReminderMessage> {
        ensureLoaded(context)
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val before = messages.size
            messages.removeAll { now - it.enqueuedAt > EXPIRE_MS }
            if (before != messages.size) {
                persist(context)
                Log.i(TAG, "清理过期消息 ${before - messages.size} 条")
            }
            return messages.sortedBy { it.enqueuedAt }
        }
    }

    /** 眼镜确认收到，从队列移除；返回是否命中 */
    fun ack(context: Context, messageId: String): Boolean {
        ensureLoaded(context)
        synchronized(lock) {
            val removed = messages.removeAll { it.messageId == messageId }
            if (removed) {
                persist(context)
                Log.i(TAG, "ack 移除：$messageId（剩余 ${messages.size}）")
            }
            return removed
        }
    }
}
