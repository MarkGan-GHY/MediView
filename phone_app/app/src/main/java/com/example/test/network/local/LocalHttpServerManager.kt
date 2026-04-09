package com.example.test.network.local

import android.content.Context
import android.util.Log
import com.example.test.server.LocalHttpServer
import com.example.test.service.LlmApiService

/**
 * 封装 [LocalHttpServer]，提供清晰的 start/stop 接口。
 *
 * llmApiService 由外部注入，避免在 IO 线程上初始化 DataStore 导致死锁。
 */
class LocalHttpServerManager(
    private val context: Context,
    private val port: Int,
    private val llmApiService: LlmApiService?,
    private val onRequestReceived: (imageSize: Int, savePath: String) -> Unit,
    private val onLogEvent: (message: String, level: String) -> Unit = { _, _ -> }
) {
    companion object {
        private const val TAG = "LocalHttpServerManager"
    }

    private var server: LocalHttpServer? = null

    val isRunning: Boolean
        get() = server?.isAlive == true

    val listeningPort: Int
        get() = port

    fun start(): Result<Int> {
        if (isRunning) {
            Log.w(TAG, "HTTP 服务已在运行，端口: $port")
            return Result.success(port)
        }

        return try {
            server = LocalHttpServer(
                context = context,
                port = port,
                llmApiService = llmApiService,
                onRequestReceived = onRequestReceived,
                onLogEvent = onLogEvent
            )
            server!!.start()
            Log.i(TAG, "HTTP 服务启动成功，端口: $port")
            Result.success(port)
        } catch (e: Exception) {
            Log.e(TAG, "HTTP 服务启动失败: ${e.message}", e)
            server = null
            Result.failure(e)
        }
    }

    fun stop() {
        server?.stop()
        server = null
        Log.i(TAG, "HTTP 服务已停止")
    }
}
