package com.example.test.model

/**
 * 推送给眼镜端的提醒消息。
 *
 * 由 alarm 触发时由 [com.example.test.reminder.ReminderAlarmReceiver] 构造，
 * 进入 [com.example.test.reminder.ReminderPushQueue]，
 * 等待眼镜端通过 GET /pendingReminders 取走。
 */
data class ReminderMessage(
    /** 全局唯一消息 id，用于眼镜端去重和 ack */
    val messageId: String,
    /** 来源提醒的 id（同一 reminder 在不同时间会产生多条 message） */
    val reminderId: Long,
    val name: String,
    val timesPerDay: String,
    val amount: String,
    val unit: String,
    /** 触发时间 "HH:mm" */
    val scheduledTime: String,
    /** 入队时刻（毫秒），用于队列排序与过期淘汰 */
    val enqueuedAt: Long,
    /** true 表示来自手机端"测试"按钮，眼镜端展示时应区分于真实闹钟 */
    val isTest: Boolean = false
)
