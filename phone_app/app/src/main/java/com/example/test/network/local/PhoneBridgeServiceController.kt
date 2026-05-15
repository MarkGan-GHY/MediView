package com.example.test.network.local

import android.content.Context
import android.util.Log
import com.example.test.service.LlmApiService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 统一生命周期控制器：协调 HTTP 服务启动与 NSD 服务发布。
 *
 * 启动顺序：
 *   1. 启动本地 HTTP Server
 *   2. 获取实际端口
 *   3. 通过 NSD 发布服务
 *
 * 停止顺序：
 *   1. 注销 NSD 服务
 *   2. 停止 HTTP Server
 *
 * 状态通过 [state] StateFlow 暴露，UI 层可直接 collect。
 */
class PhoneBridgeServiceController(
    private val context: Context,
    port: Int,
    llmApiService: LlmApiService?,
    analyzeScope: CoroutineScope,
    onRequestReceived: (imageSize: Int, savePath: String) -> Unit,
    private val onLogEvent: (message: String, level: String) -> Unit = { _, _ -> }
) {
    companion object {
        private const val TAG = "PhoneBridgeController"
    }

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "协程未捕获异常", throwable)
        _state.value = _state.value.copy(errorMessage = "内部错误: ${throwable.message}")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + exceptionHandler)

    private val httpManager = LocalHttpServerManager(
        context, port, llmApiService, analyzeScope, onRequestReceived, onLogEvent
    )
    private val nsdPublisher = NsdServicePublisher(context)
    private val udpBeacon = UdpServiceBeacon(context)

    private val _state = MutableStateFlow(PhoneBridgePublishState())
    val state: StateFlow<PhoneBridgePublishState> = _state.asStateFlow()

    val isServerRunning: Boolean get() = httpManager.isRunning

    /**
     * 启动 HTTP 服务，成功后注册 NSD。
     */
    fun start() {
        if (httpManager.isRunning) {
            Log.w(TAG, "服务已在运行，忽略重复启动")
            return
        }

        scope.launch(Dispatchers.IO) {
            val result = httpManager.start()
            result.onSuccess { actualPort ->
                _state.value = _state.value.copy(
                    serverRunning = true,
                    port = actualPort,
                    errorMessage = null
                )
                onLogEvent("HTTP 服务启动成功，端口 $actualPort", "INFO")
                // UDP beacon：热点模式下 mDNS 多播在国产 ROM 上可能被限制，UDP 广播作为补充
                udpBeacon.start(actualPort, NsdServicePublisher.SERVICE_NAME)
                onLogEvent("UDP Beacon 已启动（端口 ${UdpServiceBeacon.BEACON_PORT}）", "INFO")
                // NsdManager 需要在主线程调用
                withContext(Dispatchers.Main) {
                    registerNsd(actualPort)
                }
            }.onFailure { e ->
                Log.e(TAG, "HTTP 服务启动失败: ${e.message}")
                onLogEvent("HTTP 服务启动失败：${e.message}", "ERROR")
                _state.value = _state.value.copy(
                    serverRunning = false,
                    errorMessage = "HTTP 启动失败: ${e.message}"
                )
            }
        }
    }

    /**
     * 停止服务：先注销 NSD，再停止 HTTP Server。
     */
    fun stop() {
        udpBeacon.stop()
        nsdPublisher.unregister()
        httpManager.stop()
        _state.value = PhoneBridgePublishState()
        Log.i(TAG, "服务已全部停止")
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        try {
            File(context.filesDir, "nsd_log.txt").appendText("$msg\n")
        } catch (_: Exception) {}
    }

    private fun registerNsd(port: Int) {
        log("NSD 注册开始 port=$port")
        nsdPublisher.register(port, object : NsdServicePublisher.Listener {
            override fun onRegistered(serviceName: String, serviceType: String, port: Int) {
                log("NSD 注册成功: name=$serviceName type=$serviceType port=$port")
                onLogEvent("NSD 注册成功：$serviceName（$serviceType）", "INFO")
                _state.value = _state.value.copy(
                    published = true,
                    serviceName = serviceName,
                    serviceType = serviceType,
                    errorMessage = null
                )
            }

            override fun onRegistrationFailed(errorCode: Int) {
                log("NSD 注册失败: errorCode=$errorCode")
                onLogEvent("NSD 注册失败，errorCode=$errorCode（将依赖 UDP Beacon 发现）", "WARN")
                _state.value = _state.value.copy(
                    published = false,
                    errorMessage = "NSD 注册失败，errorCode=$errorCode"
                )
            }

            override fun onUnregistered() {
                log("NSD 注销成功")
                _state.value = _state.value.copy(
                    published = false,
                    serviceName = "",
                    serviceType = ""
                )
            }

            override fun onUnregistrationFailed(errorCode: Int) {
                log("NSD 注销失败: errorCode=$errorCode")
            }
        })
    }
}
