package com.example.test.network.local

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 通过 UDP 广播在局域网内周期性宣告手机服务。
 *
 * 为什么需要这个：
 * Android NSD (mDNS) 在热点模式下，部分国产 ROM（荣耀/小米/OPPO 等）会限制
 * mDNS 多播包只在 loopback 接口发出，导致连接到热点的眼镜端收不到服务发现包。
 * 本类通过 255.255.255.255 定向广播作为补充/替代方案，穿透这一限制。
 *
 * 协议格式（纯文本，一行）：
 *   MEDIVIEW_SERVICE <port> <serviceName>
 * 例：
 *   MEDIVIEW_SERVICE 8080 rayneo_phone_bridge
 *
 * 眼镜端只需监听 UDP port 5354，收到符合格式的包即可提取 host:port。
 */
class UdpServiceBeacon(private val context: Context) {

    companion object {
        private const val TAG = "UdpServiceBeacon"
        const val BEACON_PORT = 5354
        private const val BROADCAST_INTERVAL_MS = 2000L
        private const val MESSAGE_PREFIX = "MEDIVIEW_SERVICE"
    }

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "UdpBeacon").also { it.isDaemon = true }
    }
    private var scheduledFuture: ScheduledFuture<*>? = null
    private var socket: DatagramSocket? = null

    @Volatile var isRunning = false
        private set

    /**
     * 开始每 2 秒广播一次服务信息。
     */
    fun start(port: Int, serviceName: String) {
        if (isRunning) return
        isRunning = true

        val message = "$MESSAGE_PREFIX $port $serviceName".toByteArray(Charsets.UTF_8)

        scheduledFuture = executor.scheduleAtFixedRate({
            try {
                val s = DatagramSocket().also {
                    it.broadcast = true
                    socket = it
                }
                // 255.255.255.255 是受限广播地址，会在所有本地接口上广播
                val address = InetAddress.getByName("255.255.255.255")
                val packet = DatagramPacket(message, message.size, address, BEACON_PORT)
                s.send(packet)
                s.close()
                Log.d(TAG, "UDP beacon 发送: port=$port")
            } catch (e: Exception) {
                Log.w(TAG, "UDP beacon 发送失败: ${e.message}")
            }
        }, 0L, BROADCAST_INTERVAL_MS, TimeUnit.MILLISECONDS)

        Log.i(TAG, "UDP beacon 启动: port=$port, serviceName=$serviceName")
    }

    /**
     * 停止广播。
     */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        scheduledFuture?.cancel(true)
        scheduledFuture = null
        socket?.close()
        socket = null
        Log.i(TAG, "UDP beacon 已停止")
    }
}
