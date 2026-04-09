package com.example.glasstest.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket

private const val TAG = "PhoneServiceDiscovery"
private const val SERVICE_TYPE = "_rayneo-pill._tcp."

/** UDP beacon 端口，与手机端 UdpServiceBeacon.BEACON_PORT 保持一致 */
private const val BEACON_PORT = 5354

/**
 * 通过 NSD（DNS-SD/mDNS）+ UDP 广播两种方式发现手机端服务，获取 host:port。
 *
 * 在热点模式下，国产 ROM（小米/荣耀/OPPO 等）会限制 mDNS 多播包只在 loopback 发出，
 * 导致 NSD 发现失败。UDP 广播作为补充方案，穿透这一限制。
 */
class PhoneServiceDiscovery(private val context: Context) {

    interface Listener {
        fun onPhoneFound(host: String, port: Int)
        fun onPhoneLost()
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var listener: Listener? = null

    // 标记是否有 resolve 正在进行，避免并发 resolve（Android NsdManager 限制）
    @Volatile private var isResolving = false

    // 待 resolve 的服务队列（简单单槽：只保留最新一个）
    @Volatile private var pendingResolve: NsdServiceInfo? = null

    // UDP beacon 监听
    private var udpSocket: DatagramSocket? = null
    private var udpThread: Thread? = null

    fun start(listener: Listener) {
        this.listener = listener
        startNsdDiscovery(listener)
        startUdpBeaconListener(listener)
    }

    fun stop() {
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.e(TAG, "停止发现异常", e)
            }
        }
        discoveryListener = null
        listener = null
        isResolving = false
        pendingResolve = null

        udpSocket?.close()
        udpSocket = null
        udpThread = null
    }

    private fun startNsdDiscovery(listener: Listener) {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "发现启动失败: errorCode=$errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "发现停止失败: errorCode=$errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "开始搜索服务: $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "停止搜索服务: $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "发现服务: name=${serviceInfo.serviceName}, type=${serviceInfo.serviceType}")
                if (serviceInfo.serviceType == SERVICE_TYPE) {
                    resolveService(serviceInfo)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.w(TAG, "服务消失: ${serviceInfo.serviceName}")
                listener.onPhoneLost()
            }
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener!!)
    }

    /**
     * 监听手机端每 2 秒发出的 UDP broadcast，格式：MEDIVIEW_SERVICE <port> <serviceName>
     * 这是 NSD 在热点模式下失效时的备用发现通道。
     */
    private fun startUdpBeaconListener(listener: Listener) {
        val mainHandler = Handler(Looper.getMainLooper())
        udpThread = Thread({
            try {
                val socket = DatagramSocket(BEACON_PORT).also { udpSocket = it }
                socket.soTimeout = 0  // 无超时，阻塞等待
                val buf = ByteArray(256)
                Log.i(TAG, "UDP beacon 监听启动，端口 $BEACON_PORT")
                while (!socket.isClosed) {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val msg = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                    if (!msg.startsWith("MEDIVIEW_SERVICE")) continue
                    val parts = msg.split(" ")
                    if (parts.size < 2) continue
                    val port = parts[1].toIntOrNull() ?: continue
                    val host = packet.address?.hostAddress ?: continue
                    Log.i(TAG, "UDP beacon 收到: host=$host, port=$port")
                    mainHandler.post { listener.onPhoneFound(host, port) }
                }
            } catch (e: Exception) {
                if (udpSocket?.isClosed == false) {
                    Log.e(TAG, "UDP beacon 监听异常", e)
                } else {
                    Log.i(TAG, "UDP beacon 监听已停止")
                }
            }
        }, "UdpBeaconReceiver")
        udpThread!!.isDaemon = true
        udpThread!!.start()
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        if (isResolving) {
            // 已有 resolve 在进行，暂存最新服务，等当前 resolve 完成后处理
            Log.d(TAG, "resolve 正忙，暂存: ${serviceInfo.serviceName}")
            pendingResolve = serviceInfo
            return
        }
        doResolve(serviceInfo)
    }

    private fun doResolve(serviceInfo: NsdServiceInfo) {
        isResolving = true
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                isResolving = false
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                    // errorCode=3：另一个 resolve 正在进行，稍后重试
                    Log.w(TAG, "FAILURE_ALREADY_ACTIVE，100ms 后重试")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        doResolve(serviceInfo)
                    }, 100)
                } else {
                    Log.e(TAG, "Resolve 失败: errorCode=$errorCode")
                    drainPending()
                }
            }

            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                isResolving = false
                val host = resolvedInfo.host?.hostAddress
                val port = resolvedInfo.port
                if (host != null) {
                    Log.i(TAG, "Resolve 成功: host=$host, port=$port")
                    listener?.onPhoneFound(host, port)
                } else {
                    Log.e(TAG, "Resolve 成功但 host 为 null")
                }
                drainPending()
            }
        })
    }

    /** resolve 完成后，若有待处理的服务则继续 resolve */
    private fun drainPending() {
        val next = pendingResolve ?: return
        pendingResolve = null
        Log.d(TAG, "处理暂存服务: ${next.serviceName}")
        doResolve(next)
    }
}