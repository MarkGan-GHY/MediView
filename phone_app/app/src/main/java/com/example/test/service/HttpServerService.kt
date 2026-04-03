package com.example.test.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.test.MainActivity
import com.example.test.R
import com.example.test.server.LocalHttpServer

/**
 * HTTP 服务的宿主：Android 前台服务。
 *
 * 为什么要用前台服务（而非普通 Service 或后台线程）？
 * - Android 的后台进程管理会在内存不足时杀死后台应用
 * - 前台服务会显示一条持久通知，系统将其视为"用户感知"的任务，不会随意杀死
 * - 这保证了眼镜端在任何时候都能与手机建立 HTTP 连接
 *
 * 生命周期：
 *   startForegroundService(ACTION_START) → onCreate → onStartCommand → HTTP 服务启动
 *   startService(ACTION_STOP)            → onStartCommand → HTTP 服务停止 → stopSelf()
 */
class HttpServerService : Service() {

    companion object {
        private const val TAG = "HttpServerService"

        const val ACTION_START = "com.example.test.HTTP_SERVER_START"
        const val ACTION_STOP = "com.example.test.HTTP_SERVER_STOP"

        /** 广播 Action：通知 Activity 有新的图片请求到达 */
        const val BROADCAST_REQUEST_RECEIVED = "com.example.test.REQUEST_RECEIVED"

        /** 广播 Extra Key */
        const val EXTRA_IMAGE_SIZE = "image_size"
        const val EXTRA_SAVE_PATH = "save_path"

        const val SERVER_PORT = 8080

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "http_server_channel"
    }

    private var httpServer: LocalHttpServer? = null

    // -------------------------------------------------------------------------
    // 生命周期
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startHttpServer()
            ACTION_STOP -> stopHttpServer()
        }
        // START_NOT_STICKY：服务被杀后不自动重启，需用户或眼镜端手动重启
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        httpServer?.stop()
        httpServer = null
        Log.i(TAG, "HttpServerService 销毁，HTTP 服务已停止")
    }

    /** 本服务不提供 Binder 绑定接口，通过 Intent + 广播与 Activity 通信 */
    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------------------------
    // HTTP 服务启停
    // -------------------------------------------------------------------------

    private fun startHttpServer() {
        if (httpServer?.isAlive == true) {
            Log.w(TAG, "HTTP 服务已在运行，忽略重复启动")
            return
        }

        // 必须在启动子线程之前调用 startForeground，否则 Android 会 ANR
        startForeground(NOTIFICATION_ID, buildNotification())

        httpServer = LocalHttpServer(
            context = applicationContext,
            port = SERVER_PORT,
            onRequestReceived = { imageSize, savePath ->
                // 此回调在 NanoHTTPD 工作线程中执行，通过广播通知 Activity 更新 UI
                broadcastRequestReceived(imageSize, savePath)
            }
        )

        try {
            httpServer!!.start()
            Log.i(TAG, "HTTP 服务已启动，端口: $SERVER_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "HTTP 服务启动失败", e)
            stopSelf()
        }
    }

    private fun stopHttpServer() {
        httpServer?.stop()
        httpServer = null
        Log.i(TAG, "HTTP 服务已手动停止")
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
            // 限定广播只在本 App 内部接收，防止外部 App 监听
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
            NotificationManager.IMPORTANCE_LOW  // LOW：静音，不振动，不弹出
        ).apply {
            description = "保持手机与眼镜之间的 HTTP 通信通道"
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        // 点击通知时跳转回主界面
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
            .setOngoing(true)   // 用户无法手动滑掉此通知
            .build()
    }
}
