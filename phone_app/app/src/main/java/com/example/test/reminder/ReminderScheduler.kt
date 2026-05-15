package com.example.test.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.test.model.MedicineReminder
import java.util.Calendar

/**
 * 服药提醒调度器，封装 AlarmManager。
 *
 * 每条提醒（id）× 每个时间点（timeIndex）注册一个独立的精确闹钟，
 * 触发后由 [ReminderAlarmReceiver] 处理：入队 + 计算下一次触发时间重新注册。
 *
 * requestCode 算法：reminderId.hashCode() * 100 + timeIndex
 *   - 同一 reminder 的多个时间点彼此独立
 *   - update / delete 时通过相同 requestCode 找到并取消
 */
object ReminderScheduler {

    private const val TAG = "ReminderScheduler"
    private const val MAX_TIMES_PER_REMINDER = 100  // 与 requestCode 公式中的 * 100 一致

    /** 重新调度某条提醒：先取消旧的所有 alarm，再按当前 isActive/times/repeatDays 注册 */
    fun reschedule(context: Context, reminder: MedicineReminder) {
        cancel(context, reminder)
        if (!reminder.isActive) {
            Log.i(TAG, "提醒未启用，跳过调度：${reminder.name}")
            return
        }
        if (reminder.times.isEmpty() || reminder.repeatDays.isEmpty()) {
            Log.w(TAG, "提醒无时间或无重复日，跳过：${reminder.name}")
            return
        }

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        reminder.times.forEachIndexed { index, time ->
            val triggerAt = computeNextTrigger(time, reminder.repeatDays)
            if (triggerAt <= 0) {
                Log.w(TAG, "无法计算下次触发时间：${reminder.name} $time")
                return@forEachIndexed
            }
            scheduleOne(context, am, reminder, time, index, triggerAt)
        }
    }

    /** 取消某条提醒的所有 alarm（按可能的最大时间点数量遍历，确保彻底清理） */
    fun cancel(context: Context, reminder: MedicineReminder) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (index in 0 until MAX_TIMES_PER_REMINDER) {
            val pi = buildPendingIntent(context, reminder.id, index, createIfMissing = false)
            if (pi != null) {
                am.cancel(pi)
                pi.cancel()
            }
        }
    }

    /** 重新调度所有提醒（用于开机恢复/进程重启） */
    fun rescheduleAll(context: Context, reminders: List<MedicineReminder>) {
        reminders.forEach { reschedule(context, it) }
    }

    /**
     * Receiver 触发后调用，仅重排单个时间点（避免重复操作其他时间点）。
     */
    internal fun rescheduleSingle(
        context: Context,
        reminderId: Long,
        name: String,
        timesPerDay: String,
        amount: String,
        unit: String,
        time: String,
        timeIndex: Int,
        repeatDays: Set<Int>
    ) {
        val triggerAt = computeNextTrigger(time, repeatDays)
        if (triggerAt <= 0) return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = buildIntent(context, reminderId, name, timesPerDay, amount, unit, time, timeIndex, repeatDays)
        val pi = PendingIntent.getBroadcast(
            context,
            requestCodeOf(reminderId, timeIndex),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        setExact(am, triggerAt, pi)
        Log.i(TAG, "已重排下次：$name $time -> $triggerAt")
    }

    private fun scheduleOne(
        context: Context,
        am: AlarmManager,
        reminder: MedicineReminder,
        time: String,
        timeIndex: Int,
        triggerAt: Long
    ) {
        val intent = buildIntent(
            context,
            reminder.id,
            reminder.name,
            reminder.timesPerDay,
            reminder.amount,
            reminder.unit,
            time,
            timeIndex,
            reminder.repeatDays
        )
        val pi = PendingIntent.getBroadcast(
            context,
            requestCodeOf(reminder.id, timeIndex),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        setExact(am, triggerAt, pi)
        Log.i(TAG, "已注册：${reminder.name} $time -> ${java.util.Date(triggerAt)}")
    }

    private fun setExact(am: AlarmManager, triggerAt: Long, pi: PendingIntent) {
        // Android 12+ 需要 SCHEDULE_EXACT_ALARM 或 USE_EXACT_ALARM 权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            Log.w(TAG, "无精确闹钟权限，降级为 setAndAllowWhileIdle")
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            return
        }
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
    }

    private fun buildIntent(
        context: Context,
        reminderId: Long,
        name: String,
        timesPerDay: String,
        amount: String,
        unit: String,
        time: String,
        timeIndex: Int,
        repeatDays: Set<Int>
    ): Intent {
        return Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ReminderAlarmReceiver.ACTION_FIRE
            putExtra(ReminderAlarmReceiver.EXTRA_REMINDER_ID, reminderId)
            putExtra(ReminderAlarmReceiver.EXTRA_NAME, name)
            putExtra(ReminderAlarmReceiver.EXTRA_TIMES_PER_DAY, timesPerDay)
            putExtra(ReminderAlarmReceiver.EXTRA_AMOUNT, amount)
            putExtra(ReminderAlarmReceiver.EXTRA_UNIT, unit)
            putExtra(ReminderAlarmReceiver.EXTRA_TIME, time)
            putExtra(ReminderAlarmReceiver.EXTRA_TIME_INDEX, timeIndex)
            putExtra(ReminderAlarmReceiver.EXTRA_REPEAT_DAYS, repeatDays.toIntArray())
        }
    }

    private fun buildPendingIntent(
        context: Context,
        reminderId: Long,
        timeIndex: Int,
        createIfMissing: Boolean
    ): PendingIntent? {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ReminderAlarmReceiver.ACTION_FIRE
        }
        val flags = if (createIfMissing) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context, requestCodeOf(reminderId, timeIndex), intent, flags)
    }

    private fun requestCodeOf(reminderId: Long, timeIndex: Int): Int {
        return reminderId.hashCode() * MAX_TIMES_PER_REMINDER + timeIndex
    }

    /**
     * 计算给定 "HH:mm" + repeatDays（1=周一..7=周日）的下一次触发时间戳。
     * 若今天匹配且时间还没到 → 今天；否则向后找最近的匹配日。
     */
    fun computeNextTrigger(time: String, repeatDays: Set<Int>): Long {
        if (repeatDays.isEmpty()) return -1
        val parts = time.split(":")
        if (parts.size != 2) return -1
        val hour = parts[0].toIntOrNull() ?: return -1
        val minute = parts[1].toIntOrNull() ?: return -1

        val now = Calendar.getInstance()
        for (offset in 0..7) {
            val candidate = (now.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, offset)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            // Calendar.DAY_OF_WEEK：周日=1，周一=2，..周六=7；本项目约定：周一=1，..周日=7
            val cw = candidate.get(Calendar.DAY_OF_WEEK)
            val projectWeekday = if (cw == Calendar.SUNDAY) 7 else cw - 1
            if (projectWeekday in repeatDays && candidate.timeInMillis > now.timeInMillis) {
                return candidate.timeInMillis
            }
        }
        return -1
    }
}
