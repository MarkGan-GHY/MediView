package com.example.test.viewmodel

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import com.example.test.model.MedicineReminder
import com.example.test.model.ReminderMessage
import com.example.test.reminder.ReminderPushQueue
import com.example.test.reminder.ReminderScheduler
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 服药提醒 ViewModel，内置旧数据自动迁移逻辑。
 *
 * CRUD 操作除了更新内存列表与持久化，还会同步调用 [ReminderScheduler]
 * 维护 AlarmManager 中的精确闹钟。
 */
class ReminderViewModel(context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    private val sharedPreferences =
        context.getSharedPreferences("medicine_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    val reminders = mutableStateListOf<MedicineReminder>()

    init {
        loadFromDisk()
        // 启动时按当前列表重排所有 alarm，覆盖进程被杀后丢失的 PendingIntent
        ReminderScheduler.rescheduleAll(appContext, reminders.toList())
    }

    // ---------- 持久化 ----------

    private fun loadFromDisk() {
        val json = sharedPreferences.getString("reminders_list", null) ?: return
        try {
            // 先用 Map 解析，兼容旧字段 time → 新字段 times
            val rawType = object : TypeToken<List<Map<String, Any?>>>() {}.type
            val rawList: List<Map<String, Any?>> = gson.fromJson(json, rawType)
            val migrated = rawList.map { raw -> migrateRecord(raw) }
            reminders.clear()
            reminders.addAll(migrated)
        } catch (_: Exception) {
            // Map 方式失败则尝试直接按新模型解析
            try {
                val type = object : TypeToken<List<MedicineReminder>>() {}.type
                val list: List<MedicineReminder> = gson.fromJson(json, type)
                reminders.clear()
                reminders.addAll(list)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /** 单条记录迁移：{ "time": "08:00" } → { "times": ["08:00"] } */
    private fun migrateRecord(raw: Map<String, Any?>): MedicineReminder {
        val times: List<String> = when {
            raw["times"] is List<*> ->
                (raw["times"] as List<*>).mapNotNull { it?.toString() }
            raw["time"] is String ->
                listOf(raw["time"] as String)
            else -> emptyList()
        }

        return MedicineReminder(
            id = (raw["id"] as? Double)?.toLong() ?: System.currentTimeMillis(),
            name = raw["name"] as? String ?: "",
            timesPerDay = raw["timesPerDay"] as? String ?: "3",
            amount = raw["amount"] as? String ?: "1",
            unit = raw["unit"] as? String ?: "粒",
            times = times.sorted(),
            repeatDays = (raw["repeatDays"] as? List<*>)?.mapNotNull {
                (it as? Double)?.toInt()
            }?.toSet() ?: setOf(1, 2, 3, 4, 5, 6, 7),
            isActive = raw["isActive"] as? Boolean ?: true
        )
    }

    private fun saveToDisk() {
        val json = gson.toJson(reminders.toList())
        sharedPreferences.edit().putString("reminders_list", json).apply()
    }

    // ---------- CRUD ----------

    fun addReminder(reminder: MedicineReminder) {
        reminders.add(reminder)
        saveToDisk()
        ReminderScheduler.reschedule(appContext, reminder)
    }

    fun deleteReminder(reminder: MedicineReminder) {
        reminders.remove(reminder)
        saveToDisk()
        ReminderScheduler.cancel(appContext, reminder)
    }

    fun updateReminder(updatedReminder: MedicineReminder) {
        val index = reminders.indexOfFirst { it.id == updatedReminder.id }
        if (index != -1) {
            reminders[index] = updatedReminder
            saveToDisk()
            ReminderScheduler.reschedule(appContext, updatedReminder)
        }
    }

    fun updateReminderStatus(id: Long, isActive: Boolean) {
        val index = reminders.indexOfFirst { it.id == id }
        if (index != -1) {
            val updated = reminders[index].copy(isActive = isActive)
            reminders[index] = updated
            saveToDisk()
            ReminderScheduler.reschedule(appContext, updated)
        }
    }

    /**
     * 测试推送：用当前时间作为 scheduledTime，标记 isTest=true，
     * 跳过队列去重直接入队，等待眼镜端下一次轮询拉取。
     */
    fun pushTestReminder(reminder: MedicineReminder) {
        val now = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val message = ReminderMessage(
            messageId = UUID.randomUUID().toString(),
            reminderId = reminder.id,
            name = reminder.name,
            timesPerDay = reminder.timesPerDay,
            amount = reminder.amount,
            unit = reminder.unit,
            scheduledTime = now,
            enqueuedAt = System.currentTimeMillis(),
            isTest = true
        )
        ReminderPushQueue.enqueue(appContext, message, bypassDedup = true)
    }
}
