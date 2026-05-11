package com.example.test.viewmodel

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import com.example.test.model.MedicineReminder
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 服药提醒 ViewModel，内置旧数据自动迁移逻辑。
 */
class ReminderViewModel(context: Context) : ViewModel() {
    private val sharedPreferences =
        context.getSharedPreferences("medicine_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    val reminders = mutableStateListOf<MedicineReminder>()

    init { loadFromDisk() }

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
    }

    fun deleteReminder(reminder: MedicineReminder) {
        reminders.remove(reminder)
        saveToDisk()
    }

    fun updateReminder(updatedReminder: MedicineReminder) {
        val index = reminders.indexOfFirst { it.id == updatedReminder.id }
        if (index != -1) {
            reminders[index] = updatedReminder
            saveToDisk()
        }
    }

    fun updateReminderStatus(id: Long, isActive: Boolean) {
        val index = reminders.indexOfFirst { it.id == id }
        if (index != -1) {
            reminders[index] = reminders[index].copy(isActive = isActive)
            saveToDisk()
        }
    }
}
