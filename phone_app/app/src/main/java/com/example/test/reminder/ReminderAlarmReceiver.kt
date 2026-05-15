package com.example.test.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.test.model.ReminderMessage
import java.util.UUID

/**
 * 服药提醒闹钟接收器。
 *
 * AlarmManager 触发时：
 *  1. 构造 [ReminderMessage] 入队（[ReminderPushQueue.enqueue]）
 *  2. 根据 repeatDays 计算下一次触发时间，重新注册该时间点
 *     （[AlarmManager.setExactAndAllowWhileIdle] 是一次性闹钟，必须自行 reschedule）
 */
class ReminderAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ReminderAlarmReceiver"

        const val ACTION_FIRE = "com.example.test.REMINDER_FIRE"

        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_NAME = "name"
        const val EXTRA_TIMES_PER_DAY = "times_per_day"
        const val EXTRA_AMOUNT = "amount"
        const val EXTRA_UNIT = "unit"
        const val EXTRA_TIME = "time"
        const val EXTRA_TIME_INDEX = "time_index"
        const val EXTRA_REPEAT_DAYS = "repeat_days"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return

        val reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, -1L)
        val name = intent.getStringExtra(EXTRA_NAME) ?: ""
        val timesPerDay = intent.getStringExtra(EXTRA_TIMES_PER_DAY) ?: ""
        val amount = intent.getStringExtra(EXTRA_AMOUNT) ?: ""
        val unit = intent.getStringExtra(EXTRA_UNIT) ?: ""
        val time = intent.getStringExtra(EXTRA_TIME) ?: ""
        val timeIndex = intent.getIntExtra(EXTRA_TIME_INDEX, 0)
        val repeatDays = intent.getIntArrayExtra(EXTRA_REPEAT_DAYS)?.toSet() ?: emptySet()

        Log.i(TAG, "闹钟触发：$name $time")

        ReminderPushQueue.enqueue(
            context,
            ReminderMessage(
                messageId = UUID.randomUUID().toString(),
                reminderId = reminderId,
                name = name,
                timesPerDay = timesPerDay,
                amount = amount,
                unit = unit,
                scheduledTime = time,
                enqueuedAt = System.currentTimeMillis()
            )
        )

        ReminderScheduler.rescheduleSingle(
            context = context,
            reminderId = reminderId,
            name = name,
            timesPerDay = timesPerDay,
            amount = amount,
            unit = unit,
            time = time,
            timeIndex = timeIndex,
            repeatDays = repeatDays
        )
    }
}
