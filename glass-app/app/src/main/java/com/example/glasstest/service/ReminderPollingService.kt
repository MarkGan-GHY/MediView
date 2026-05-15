package com.example.glasstest.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.glasstest.network.PhoneServiceDiscovery
import com.example.glasstest.network.ReminderApiClient
import com.example.glasstest.ui.activity.reminder.ReminderActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "ReminderPollingService"
private const val CHANNEL_ID = "reminder_polling_channel"
private const val NOTIFICATION_ID = 2001

/** 轮询间隔（毫秒） */
private const val POLL_INTERVAL_MS = 30_000L

/**
 * 眼镜端服药提醒轮询服务。
 *
 * - 启动后通过 [PhoneServiceDiscovery] 持续发现手机 host:port
 * - 每 [POLL_INTERVAL_MS] 调用一次 GET /pendingReminders
 * - 命中且本地未弹过的 messageId → startActivity 拉起 [ReminderActivity]
 *
 * 本服务作为前台服务运行，独立于 DrugCaptureActivity 生命周期。
 */
class ReminderPollingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    private val discovery by lazy { PhoneServiceDiscovery(this) }
    private val api = ReminderApiClient()

    @Volatile private var phoneHost: String? = null
    @Volatile private var phonePort: Int = 8080

    /** 已经弹过的 messageId，避免确认前重复拉起 Activity */
    private val shownMessageIds = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            } else 0
        )
        startDiscovery()
        startPolling()
        Log.i(TAG, "轮询服务已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        pollJob?.cancel()
        scope.cancel()
        discovery.stop()
        Log.i(TAG, "轮询服务已停止")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startDiscovery() {
        discovery.start(object : PhoneServiceDiscovery.Listener {
            override fun onPhoneFound(host: String, port: Int) {
                phoneHost = host
                phonePort = port
                Log.i(TAG, "手机服务发现：$host:$port")
            }

            override fun onPhoneLost() {
                phoneHost = null
                Log.w(TAG, "手机服务已丢失")
            }
        })
    }

    private fun startPolling() {
        pollJob = scope.launch {
            while (isActive) {
                val host = phoneHost
                if (host != null) {
                    pollOnce(host, phonePort)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun pollOnce(host: String, port: Int) {
        val result = api.fetchPending(host, port)
        if (result !is ReminderApiClient.FetchResult.Success) return
        val pending = result.reminders
        if (pending.isEmpty()) return

        // 取队列里第一条尚未展示的消息，启动提醒页
        val target = pending.firstOrNull { it.messageId !in shownMessageIds } ?: return
        shownMessageIds.add(target.messageId)

        Log.i(TAG, "拉起提醒页：${target.name} @ ${target.scheduledTime}${if (target.isTest) " [测试]" else ""}")
        val intent = Intent(this, ReminderActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(ReminderActivity.EXTRA_MESSAGE_ID, target.messageId)
            putExtra(ReminderActivity.EXTRA_NAME, target.name)
            putExtra(ReminderActivity.EXTRA_AMOUNT, target.amount)
            putExtra(ReminderActivity.EXTRA_UNIT, target.unit)
            putExtra(ReminderActivity.EXTRA_TIMES_PER_DAY, target.timesPerDay)
            putExtra(ReminderActivity.EXTRA_SCHEDULED_TIME, target.scheduledTime)
            putExtra(ReminderActivity.EXTRA_IS_TEST, target.isTest)
            putExtra(ReminderActivity.EXTRA_PHONE_HOST, host)
            putExtra(ReminderActivity.EXTRA_PHONE_PORT, port)
        }
        startActivity(intent)

        // 清理：超过 200 条时压缩，避免无界增长
        if (shownMessageIds.size > 200) {
            val keep = shownMessageIds.toList().takeLast(100).toSet()
            shownMessageIds.clear()
            shownMessageIds.addAll(keep)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "服药提醒轮询",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MediView 服药提醒")
            .setContentText("正在监听手机端提醒")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, ReminderPollingService::class.java)
            context.startForegroundService(intent)
        }
    }
}
