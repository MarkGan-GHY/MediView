package com.example.test.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.test.MainActivity
import com.example.test.R
import com.example.test.data.NetworkRepository
import com.example.test.network.local.PhoneBridgeServiceController

/**
 * HTTP 服务的宿主：Android 前台服务。
 *
 * 生命周期：
 *   startForegroundService(ACTION_START) → onCreate → onStartCommand → controller.start()
 *   startService(ACTION_STOP)            → onStartCommand → controller.stop() → stopSelf()
 */
class HttpServerService : Service() {

    companion object {
        private const val TAG = "HttpServerService"

        const val ACTION_START = "com.example.test.HTTP_SERVER_START"
        const val ACTION_STOP = "com.example.test.HTTP_SERVER_STOP"

        /** 广播 Action：通知 Activity 有新的图片请求到达 */
        const val BROADCAST_REQUEST_RECEIVED = "com.example.test.REQUEST_RECEIVED"

        /** 广播 Action：通用日志事件，覆盖服务生命周期、LLM 调用等全链路 */
        const val BROADCAST_LOG_EVENT = "com.example.test.LOG_EVENT"

        /** 广播 Extra Key */
        const val EXTRA_IMAGE_SIZE = "image_size"
        const val EXTRA_SAVE_PATH = "save_path"
        const val EXTRA_LOG_MESSAGE = "log_message"
        const val EXTRA_LOG_LEVEL = "log_level"   // "INFO" | "WARN" | "ERROR"

        const val SERVER_PORT = 8080

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "http_server_channel"
    }

    private var controller: PhoneBridgeServiceController? = null

    // llmApiService 在主线程（Service onCreate 后）初始化，避免在 IO 线程初始化 DataStore
    private val repository by lazy { NetworkRepository(applicationContext) }
    private val llmApiService by lazy { LlmApiService(repository) }

    // -------------------------------------------------------------------------
    // 生命周期
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startServer()
            ACTION_STOP -> stopServer()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        controller?.stop()
        controller = null
        Log.i(TAG, "HttpServerService 销毁，服务已停止")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------------------------
    // 启停
    // -------------------------------------------------------------------------

    private fun startServer() {
        if (controller?.isServerRunning == true) {
            Log.w(TAG, "服务已在运行，忽略重复启动")
            return
        }

        // Android 14+ 要求 startForeground 必须指定 foregroundServiceType
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        )

        controller = PhoneBridgeServiceController(
            context = applicationContext,
            port = SERVER_PORT,
            llmApiService = llmApiService,
            onRequestReceived = { imageSize, savePath ->
                broadcastRequestReceived(imageSize, savePath)
            },
            onLogEvent = { message, level ->
                broadcastLog(message, level)
            }
        )
        controller!!.start()
    }

    private fun stopServer() {
        controller?.stop()
        controller = null
        Log.i(TAG, "服务已手动停止")
        broadcastLog("服务已停止")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // -------------------------------------------------------------------------
    // 广播
    // -------------------------------------------------------------------------

    private fun broadcastRequestReceived(imageSize: Int, savePath: String) {
        val intent = Intent(BROADCAST_REQUEST_RECEIVED).apply {
            putExtra(EXTRA_IMAGE_SIZE, imageSize)
            putExtra(EXTRA_SAVE_PATH, savePath)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    fun broadcastLog(message: String, level: String = "INFO") {
        val intent = Intent(BROADCAST_LOG_EVENT).apply {
            putExtra(EXTRA_LOG_MESSAGE, message)
            putExtra(EXTRA_LOG_LEVEL, level)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    // -------------------------------------------------------------------------
    // 通知
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "HTTP 药物识别服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持手机与眼镜之间的 HTTP 通信通道"
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("药物识别服务运行中")
            .setContentText("正在监听端口 $SERVER_PORT，等待眼镜端连接")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
