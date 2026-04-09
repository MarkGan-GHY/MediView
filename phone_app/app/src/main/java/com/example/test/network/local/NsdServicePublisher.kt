package com.example.test.network.local

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * 使用 Android 原生 NsdManager 将本地 HTTP 服务发布到局域网。
 *
 * 职责：
 * - 构建 NsdServiceInfo 并注册
 * - 处理注册成功/失败/注销等生命周期回调
 * - 不关心业务 API，只负责"让服务可被发现"
 *
 * 注意（Android 版本差异）：
 * - Android 12 (API 31) 以下：NsdManager 回调在系统内部线程，需注意线程安全
 * - Android 12+：NsdManager 支持 executor 参数，回调线程可控
 * - 本实现兼容 API 26+，回调中只做状态通知，不做 UI 操作
 */
class NsdServicePublisher(private val context: Context) {

    companion object {
        private const val TAG = "NsdServicePublisher"

        /** NSD 服务类型，格式必须为 "_name._tcp." */
        const val SERVICE_TYPE = "_rayneo-pill._tcp."

        /** NSD 服务名，系统可能在冲突时自动追加数字后缀 */
        const val SERVICE_NAME = "rayneo_phone_bridge"
    }

    interface Listener {
        fun onRegistered(serviceName: String, serviceType: String, port: Int)
        fun onRegistrationFailed(errorCode: Int)
        fun onUnregistered()
        fun onUnregistrationFailed(errorCode: Int)
    }

    private val nsdManager: NsdManager? =
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var listener: Listener? = null

    /** 是否已处于注册状态（防止重复注册） */
    @Volatile
    var isRegistered: Boolean = false
        private set

    /**
     * 向局域网注册服务。
     *
     * @param port HTTP 服务实际监听的端口
     * @param listener 注册结果回调
     */
    fun register(port: Int, listener: Listener) {
        if (nsdManager == null) {
            Log.e(TAG, "NsdManager 不可用，跳过 NSD 注册")
            listener.onRegistrationFailed(-1)
            return
        }
        if (isRegistered) {
            Log.w(TAG, "NSD 服务已注册，忽略重复注册请求")
            return
        }
        this.listener = listener

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            setPort(port)
        }

        registrationListener = buildRegistrationListener()

        Log.i(TAG, "NSD 注册开始: serviceName=$SERVICE_NAME, serviceType=$SERVICE_TYPE, port=$port")
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener!!)
        } catch (e: Exception) {
            Log.e(TAG, "NSD registerService 调用失败", e)
            registrationListener = null
            listener.onRegistrationFailed(-2)
        }
    }

    /**
     * 注销 NSD 服务。应在 HTTP 服务停止前调用。
     */
    fun unregister() {
        val rl = registrationListener
        if (nsdManager == null || rl == null || !isRegistered) {
            Log.w(TAG, "NSD 服务未注册，无需注销")
            return
        }
        try {
            nsdManager.unregisterService(rl)
        } catch (e: Exception) {
            // 极少数情况：listener 已失效
            Log.e(TAG, "NSD 注销异常", e)
        }
    }

    private fun buildRegistrationListener(): NsdManager.RegistrationListener {
        return object : NsdManager.RegistrationListener {

            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                // Android 某些版本的回调中 serviceType 可能为 null，用注册时的常量兜底
                val finalName = serviceInfo.serviceName ?: SERVICE_NAME
                val finalType = serviceInfo.serviceType ?: SERVICE_TYPE
                val finalPort = serviceInfo.port
                isRegistered = true
                Log.i(TAG, "NSD 注册成功: serviceName=$finalName, serviceType=$finalType, port=$finalPort")
                listener?.onRegistered(finalName, finalType, finalPort)
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                isRegistered = false
                Log.e(TAG, "NSD 注册失败: errorCode=$errorCode")
                listener?.onRegistrationFailed(errorCode)
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                isRegistered = false
                registrationListener = null
                Log.i(TAG, "NSD 注销成功: serviceName=${serviceInfo.serviceName ?: SERVICE_NAME}")
                listener?.onUnregistered()
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD 注销失败: errorCode=$errorCode")
                listener?.onUnregistrationFailed(errorCode)
            }
        }
    }
}
