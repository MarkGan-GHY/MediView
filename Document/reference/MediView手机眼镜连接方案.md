# MediView 手机 ↔ 眼镜连接方案

## 1. 架构概述

```
┌─────────────────────────────────────────────────────────┐
│                     局域网（手机热点）                      │
│                                                         │
│  ┌──────────────────────┐    ┌──────────────────────┐   │
│  │      手机（服务端）     │    │     眼镜（客户端）      │   │
│  │                      │    │                      │   │
│  │  HTTP Server :8080   │◄───│  OkHttp POST         │   │
│  │  NSD/mDNS 发布       │    │  NSD 发现             │   │
│  │  UDP Beacon :5354    │───►│  UDP Beacon 监听      │   │
│  │                      │    │                      │   │
│  │  IP: 192.168.43.1    │    │  IP: 动态分配          │   │
│  └──────────────────────┘    └──────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

**角色分工**

| 角色 | 设备 | 职责 |
|------|------|------|
| 服务端 | 手机 | 开热点、运行 HTTP Server、调用大模型、返回识别结果 |
| 客户端 | 眼镜 | 连接热点、拍照、发送图片、展示结果 |

手机开热点后拥有固定 IP（通常为 `192.168.43.1`），眼镜连入热点后通过服务发现获取该地址。

---

## 2. 网络连接建立流程

### 2.1 手机端启动流程

```
用户点击「启动服务」
    │
    ▼
startForegroundService(ACTION_START)
    │
    ▼
HttpServerService.startServer()
    │
    ▼
PhoneBridgeServiceController.start()
    │
    ├─► LocalHttpServerManager.start()
    │       NanoHTTPD 绑定 0.0.0.0:8080
    │
    ├─► UdpServiceBeacon.start(port=8080)
    │       每 2 秒广播 UDP 包到 255.255.255.255:5354
    │       格式：MEDIVIEW_SERVICE 8080 rayneo_phone_bridge
    │
    └─► NsdServicePublisher.register(port=8080)
            mDNS 发布 _rayneo-pill._tcp. 服务
```

### 2.2 眼镜端发现流程

```
DrugCaptureActivity.onCreate()
    │
    ▼
PhoneServiceDiscovery.start()
    │
    ├─► NSD 发现（主通道）
    │       discoverServices("_rayneo-pill._tcp.")
    │       onServiceFound → resolveService → onPhoneFound(host, port)
    │
    └─► UDP Beacon 监听（备用通道）
            DatagramSocket 绑定 :5354
            收到 MEDIVIEW_SERVICE <port> <serviceName>
            从 DatagramPacket 取发送方 IP → onPhoneFound(host, port)
```

两条通道并行，任意一条成功即触发 `onPhoneFound`，UI 显示「已连接手机服务」。

### 2.3 为什么需要两条发现通道

Android NSD（mDNS）在**热点模式**下，部分国产 ROM（小米、荣耀、OPPO 等）会将 mDNS 多播包限制在 loopback 接口，导致连接到热点的眼镜收不到服务发现包。UDP 广播（`255.255.255.255`）可穿透这一限制，作为可靠的备用方案。

---

## 3. HTTP 通信协议

### 接口

```
POST http://<手机IP>:8080/analyzeDrug
Content-Type: image/jpeg
Body: 原始 JPEG 字节流
```

### 响应

```json
{
  "success": true,
  "drugName": "阿莫西林胶囊",
  "confidence": 0.92,
  "warningText": "青霉素类抗生素，过敏者禁用",
  "needConfirm": false
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `success` | Boolean | 是否识别成功 |
| `drugName` | String | 药物名称 |
| `confidence` | Double | 置信度 0.0~1.0 |
| `warningText` | String | 注意事项（≤100字） |
| `needConfirm` | Boolean | 是否需要用户二次确认 |

失败响应：

```json
{
  "success": false,
  "error": "错误描述"
}
```

### 超时设置（眼镜端）

| 阶段 | 超时 |
|------|------|
| 连接超时 | 5 秒 |
| 读取超时 | 15 秒 |

---

## 4. 大模型集成

手机端收到图片后，调用 `LlmApiService` 转发给大模型。支持三种 API 格式：

| 格式 | 适用场景 |
|------|---------|
| OpenAI 兼容 | GPT-4o、国内兼容接口（默认） |
| 通义千问 | 阿里 DashScope 原生格式 |
| Gemini | Google Gemini，API Key 放 URL 参数 |

配置路径：手机 App → 底部导航「网络设置」→ 添加 API 配置 → 设为默认。

未配置大模型时，服务返回模拟数据（`buildMockResponse`），方便无配置环境下测试连通性。

---

## 5. 关键端口与常量

| 用途 | 协议 | 端口 | 定义位置 |
|------|------|------|---------|
| HTTP 服务 | TCP | 8080 | `HttpServerService.SERVER_PORT` |
| UDP Beacon | UDP | 5354 | `UdpServiceBeacon.BEACON_PORT` |
| NSD 服务类型 | mDNS | — | `_rayneo-pill._tcp.` |
| NSD 服务名 | mDNS | — | `rayneo_phone_bridge` |

---

## 6. 权限清单

### 手机端（phone_app）

| 权限 | 用途 |
|------|------|
| `INTERNET` | HTTP Server 接收请求 |
| `ACCESS_NETWORK_STATE` | 获取本机 IP 显示给用户 |
| `ACCESS_WIFI_STATE` | 获取 Wi-Fi 状态 |
| `FOREGROUND_SERVICE` | 保持后台服务不被杀死 |
| `FOREGROUND_SERVICE_REMOTE_MESSAGING` | Android 14+ 前台服务类型声明 |
| `POST_NOTIFICATIONS` | Android 13+ 通知权限（运行时申请） |

### 眼镜端（glass-app）

| 权限 | 用途 |
|------|------|
| `CAMERA` | 拍摄药物图片 |
| `INTERNET` | HTTP POST 发送图片 |
| `ACCESS_NETWORK_STATE` | 网络状态检查 |

---

## 7. 故障排除

### 7.1 眼镜一直显示「正在搜索手机服务...」

**排查步骤：**

1. **确认手机热点已开启**，眼镜已连接到该热点（不是其他 Wi-Fi）

2. **确认手机服务已启动**：打开手机 App，「服务」页面状态应显示「运行中」

3. **检查 NSD 注册日志**：
   ```bash
   adb -s <手机设备> shell cat /data/data/com.example.test/files/nsd_log.txt
   ```
   正常输出应包含：
   ```
   NSD 注册开始 port=8080
   NSD 注册成功: name=rayneo_phone_bridge type=_rayneo-pill._tcp. port=8080
   ```

4. **检查 UDP Beacon 是否发出**（抓包或查看 Logcat）：
   ```bash
   adb -s <手机设备> logcat -s UdpServiceBeacon
   ```
   正常输出：`UDP beacon 发送: port=8080`

5. **检查眼镜端 UDP 监听**：
   ```bash
   adb -s <眼镜设备> logcat -s PhoneServiceDiscovery
   ```
   正常输出：`UDP beacon 监听启动，端口 5354` 和 `UDP beacon 收到: host=192.168.43.1, port=8080`

6. **防火墙/ROM 限制**：部分 ROM 会拦截 UDP 广播，尝试在手机「设置 → 热点」中关闭「AP 隔离」选项（如有）

### 7.2 发现服务后 HTTP 请求失败

**排查步骤：**

1. **确认 IP 正确**：手机热点 IP 通常为 `192.168.43.1`，手机 App「服务」页面会显示本机 IP，核对是否一致

2. **测试 HTTP 连通性**（在眼镜或同网络设备上）：
   ```bash
   curl -X POST http://192.168.43.1:8080/analyzeDrug \
     -H "Content-Type: image/jpeg" \
     --data-binary @test.jpg
   ```

3. **查看手机端请求日志**：手机 App「服务」页面的请求日志会实时显示收到的图片大小和保存路径；若无日志说明请求未到达手机

4. **拉取收到的图片验证**：
   ```bash
   adb -s <手机设备> pull /data/data/com.example.test/cache/drug_images/
   ```

5. **查看崩溃日志**：
   ```bash
   adb -s <手机设备> shell cat /data/data/com.example.test/files/crash.txt
   ```

### 7.3 大模型识别失败

1. 手机 App → 「网络设置」→ 确认有一个配置被设为「默认」且「已启用」

2. 检查 API Key 和 Endpoint 是否正确，可使用「测试连接」功能验证

3. 未配置大模型时服务会返回模拟数据（`drugName: 测试药物`），这是正常的降级行为

### 7.4 服务被系统杀死

手机 App 使用前台服务（`FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING`），正常情况下不会被杀死。若仍被杀：

- 手机「设置 → 电池 → 应用省电」中将 MediView 设为「不限制」
- 部分 ROM 需要在「自启动管理」中允许 MediView

---

## 8. 连接状态检查清单

```
□ 手机热点已开启
□ 眼镜已连接到手机热点（非其他网络）
□ 手机 App 服务状态显示「运行中」
□ 手机 App 显示的本机 IP 为 192.168.43.1（或其他热点 IP）
□ 眼镜 UI 显示「单击拍照 / 双击退出」（表示已发现服务）
□ 手机 App 请求日志在拍照后有新条目出现
□ 网络设置中有默认 API 配置（或接受模拟数据）
```

---

## 9. 代码文件索引

### 手机端（phone_app）

| 文件 | 职责 |
|------|------|
| `service/HttpServerService.kt` | 前台服务宿主，管理生命周期 |
| `network/local/PhoneBridgeServiceController.kt` | 统一协调 HTTP + NSD + UDP Beacon |
| `network/local/LocalHttpServerManager.kt` | 封装 NanoHTTPD 启停 |
| `network/local/NsdServicePublisher.kt` | mDNS 服务注册/注销 |
| `network/local/UdpServiceBeacon.kt` | UDP 广播，每 2 秒发一次 |
| `server/LocalHttpServer.kt` | HTTP 请求处理，路由 `/analyzeDrug` |
| `service/LlmApiService.kt` | 大模型调用（OpenAI/Qwen/Gemini） |
| `ServiceScreen.kt` | 服务控制 UI，显示请求日志 |

### 眼镜端（glass-app）

| 文件 | 职责 |
|------|------|
| `network/PhoneServiceDiscovery.kt` | NSD + UDP Beacon 双通道服务发现 |
| `ui/activity/capture/DrugCaptureActivity.kt` | 相机拍照、HTTP 发送、结果展示 |
