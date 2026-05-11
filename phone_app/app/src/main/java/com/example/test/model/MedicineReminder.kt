package com.example.test.model

/**
 * 服药提醒数据模型。
 * time 字段已升级为 times，支持多个提醒时间。
 */
data class MedicineReminder(
    val id: Long = System.currentTimeMillis(),
    val name: String,
    val timesPerDay: String,
    val amount: String,
    val unit: String,
    /** 多个提醒时间，格式 "HH:mm"，已排序 */
    val times: List<String> = emptyList(),
    val repeatDays: Set<Int>,
    val isActive: Boolean = true
)
