# MediView 网络功能说明文档

> 适用版本：当前（眼镜端 NSD 动态发现 + 手机端 HTTP 服务 + 大模型调用）
> 最后更新：2026-04-09
> 本文档以代码实现为准，替换此前版本中字段名过时的旧文档。

---

## 一、系统角色与通信架构

MediView 采用三端协同架构：**眼镜端**（RayNeo X3 Pro）、**手机端**（本项目）、**监护人端**（待开发）。当前阶段实现眼镜端与手机端的通信闭环。

```
眼镜端                          手机端                         大模型云端
  │                               │                               │
  │  1. NSD Discover + UDP Listen │  NsdManager 注册 + UDP Beacon │
  │ ────────────────────────────► │                               │
  │                               │                               │
  │  2. NSD Resolve / UDP 包解析   │                               │
  │  得到 host:port               │                               │
  │                               │                               │
  │  3. POST /analyzeDrug         │                               │
  │     (JPEG 二进制)              │                               │
  │ ────────────────────────────► │                               │
  │                               │  4. HTTPS 请求                │
  │                               │     (Base64 图片 + Prompt)    │
  │                               │ ────────────────────────────► │
  │                               │                               │
  │                               │  5. JSON 响应                 │
  │                               │ ◄──────────────────────────── │
  │                               │                               │
  │  6. JSON 响应                  │                               │
  │ ◄──────────────────────────── │                               │
  │  { drugName, usage,           │                               │
  │    dosage, contraindications }│                               │
```

**手机端作为服务端的原因：**
- 眼镜端资源受限，只负责拍照和显示
- 手机负责所有计算密集型工作：接收图片、调用大模型 API、解析结果
- 手机开热点后局域网 IP 稳定，结合 NSD + UDP 双通道广播使眼镜可自动发现

---

## 二、服务发现（NSD + UDP Beacon 双通道）

### 2.1 为什么需要双通道

Android NSD（mDNS）在**热点模式**下，部分国产 ROM（小米、荣耀、OPPO 等）会将 mDNS 多播包限制在 loopback 接口，导致连接到热点的眼镜端收不到服务发现包。UDP 广播（`255.255.255.255:5354`）作为备用通道穿透此限制。

### 2.2 手机端发布

| 通道 | 实现文件 | 参数 |
|------|---------|------|
| NSD (主) | `NsdServicePublisher.kt` | serviceType=`_rayneo-pill._tcp.`, serviceName=`rayneo_phone_bridge` |
| UDP Beacon (备) | `UdpServiceBeacon.kt` | 每 2s 广播 `MEDIVIEW_SERVICE <port> <serviceName>` 到 `255.255.255.255:5354` |

启动顺序（`PhoneBridgeServiceController.kt` 协调）：
1. 启动 NanoHTTPD（端口 8080）
2. 端口就绪后启动 UDP Beacon
3. 注册 NSD 服务

停止顺序：UDP → NSD 注销 → HTTP 停止。

### 2.3 眼镜端发现

实现文件：`PhoneServiceDiscovery.kt`

```
start()
  ├─ NSD 发现：discoverServices("_rayneo-pill._tcp.")
  │     └─ onServiceFound → resolveService → onPhoneFound(host, port)
  │
  └─ UDP Beacon 监听：DatagramSocket(5354)
        └─ 收到 "MEDIVIEW_SERVICE <port> <name>"
           → 从 DatagramPacket 取发送方 IP → onPhoneFound(host, port)
```

两条通道**并行**，任意一条成功即缓存 host:port，UI 显示"单击拍照 / 双击退出"。网络层错误（连接拒绝、超时）触发清除缓存地址并重新 `startServiceDiscovery()`。

### 2.4 端口与常量汇总

| 用途 | 协议 | 值 | 定义位置 |
|------|------|-----|---------|
| HTTP 服务 | TCP | 8080 | `HttpServerService.SERVER_PORT` |
| UDP Beacon 端口 | UDP | 5354 | `UdpServiceBeacon.BEACON_PORT` |
| NSD 服务类型 | mDNS | `_rayneo-pill._tcp.` | `NsdServicePublisher.SERVICE_TYPE` |
| NSD 服务名 | mDNS | `rayneo_phone_bridge` | `NsdServicePublisher.SERVICE_NAME` |
| UDP 消息前缀 | — | `MEDIVIEW_SERVICE` | `UdpServiceBeacon.MESSAGE_PREFIX` |
| UDP 广播间隔 | — | 2000ms | `UdpServiceBeacon.BROADCAST_INTERVAL_MS` |

---

## 三、HTTP 通信协议

### 3.1 接口

```
POST http://<手机IP>:8080/analyzeDrug
Content-Type: image/jpeg
Body: 原始 JPEG 字节流
```

### 3.2 响应格式

**成功响应（HTTP 200）：**

```json
{
  "success": true,
  "drugName": "阿莫西林胶囊",
  "usage": "温水送服",
  "dosage": "一日三次，每次一片",
  "contraindications": "不宜空腹服用，不宜与阿司匹林共服"
}
```

**字段说明：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `success` | Boolean | 是否识别成功 |
| `drugName` | String | 识别出的药物名称 |
| `usage` | String | 用法说明（如"温水送服"） |
| `dosage` | String | 用量说明（如"一日三次，每次一片"） |
| `contraindications` | String | 禁忌说明 |

**失败响应（HTTP 200）：**

```json
{
  "success": false,
  "drugName": "",
  "usage": "",
  "dosage": "",
  "contraindications": "错误描述信息"
}
```

### 3.3 超时设置

| 阶段 | 眼镜端（OkHttp） | 手机端（OkHttp → LLM） |
|------|-----------------|----------------------|
| 连接超时 | 5s | 15s |
| 读取超时 | 15s | 60s（LLM 响应慢） |
| 写入超时 | — | 30s（上传 Base64 图片） |

---

## 四、全链路流程

### 阶段 1–3：图片从眼镜端传入手机

**涉及文件：**
- 眼镜端：`DrugCaptureActivity.kt` — Camera2 拍照 → YUV 转 JPEG
- 眼镜端：`DrugCaptureActivity.kt` — OkHttp POST
- 手机端：`LocalHttpServer.kt` — NanoHTTPD 接收
- 手机端：`HttpServerService.kt` — 前台 Service 宿主

眼镜端通过 `PhoneServiceDiscovery` 动态获取手机 host:port，构造 URL：

```
POST http://<discovered_host>:<discovered_port>/analyzeDrug
```

手机端 `LocalHttpServer`（继承 NanoHTTPD）不调用 `parseBody()`，直接从 `session.inputStream` 按 `content-length` 循环读取原始字节：

```kotlin
// LocalHttpServer.kt : readImageBytes()
val buffer = ByteArray(contentLength)
while (totalRead < contentLength) {
    val read = inputStream.read(buffer, totalRead, contentLength - totalRead)
    if (read == -1) break
    totalRead += read
}
```

读取完成后图片以 `ByteArray` 形式保留在内存，同时写入 `/cache/drug_images/img_<timestamp>.jpg` 供 adb 调试。

### 阶段 4：通知 UI 更新日志

`LocalHttpServer` 通过回调 `onLogEvent` 和 `onRequestReceived` 在工作线程触发广播（`BroadcastReceiver`），`ServiceScreen` 收到后在主线程追加日志条目。此步骤与后续 LLM 调用流程**并行**，不影响响应时序。

### 阶段 5：调用大模型 API

**涉及文件：** `LlmApiService.kt`、`NetworkRepository.kt`、`ApiConfig.kt`

#### 5.1 读取配置

`LlmApiService` 从 DataStore 读取 `isDefault=true && enabled=true` 的 `ApiConfig`。若未找到回退到 `buildMockResponse()`。

```kotlin
// ApiConfig.kt — 数据模型
data class ApiConfig(
    val id: String,
    val name: String,
    val provider: String,
    val model: String,
    val endpoint: String,
    val apiKey: String,
    val promptTemplate: String,
    val enabled: Boolean,
    val isDefault: Boolean,
    val remark: String,
    val requestFormatType: RequestFormatType
)

enum class RequestFormatType {
    OPENAI_COMPATIBLE,  // OpenAI 兼容接口（默认）
    QWEN,               // 通义千问 DashScope 原生
    GEMINI,             // Google Gemini
    CUSTOM              // 自定义（与 OpenAI 兼容同构）
}
```

#### 5.2 构造 Prompt

优先使用 `config.promptTemplate`（用户可单独为每个配置编辑），否则使用全局 Prompt（`NetworkRepository.globalPromptFlow`）。末尾**动态追加** JSON 格式约束（不写入 DataStore）：

```
<用户 Prompt>

请严格以如下 JSON 格式返回，不要包含任何其他文字或 Markdown 标记：
{"drugName":"药物名称","usage":"用法","dosage":"用量","contraindications":"禁忌"}
```

#### 5.3 图片 Base64 编码

```kotlin
val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
```

`NO_WRAP` 生成无换行符单行字符串，直接嵌入 JSON 请求体。

#### 5.4 按协议类型构造请求体

**OPENAI_COMPATIBLE / CUSTOM：**

```json
{
  "model": "<model>",
  "messages": [
    { "role": "system", "content": "<prompt>" },
    {
      "role": "user",
      "content": [
        { "type": "image_url", "image_url": { "url": "data:image/jpeg;base64,<base64>" } },
        { "type": "text", "text": "请识别图片中的药物" }
      ]
    }
  ],
  "response_format": { "type": "json_object" }
}
```

鉴权：`Authorization: Bearer <apiKey>`

**QWEN（DashScope 原生）：**

```json
{
  "model": "<model>",
  "input": {
    "messages": [
      { "role": "system", "content": [{ "text": "<prompt>" }] },
      {
        "role": "user",
        "content": [
          { "image": "data:image/jpeg;base64,<base64>" },
          { "text": "请识别图片中的药物" }
        ]
      }
    ]
  },
  "parameters": {}
}
```

鉴权：`Authorization: Bearer <apiKey>`

> **注意：** Qwen 原生格式不支持 `response_format`，JSON 输出仅靠 Prompt 约束。建议改用 DashScope 的 OpenAI 兼容端点。

**GEMINI：**

```json
{
  "contents": [{
    "parts": [
      { "inline_data": { "mime_type": "image/jpeg", "data": "<base64>" } },
      { "text": "<prompt>\n\n请识别图片中的药物" }
    ]
  }],
  "generationConfig": { "response_mime_type": "application/json" }
}
```

鉴权：API Key 拼接在 URL 参数 `?key=<apiKey>`，不使用 Authorization 头。

### 阶段 6：提取模型输出文本

各协议响应结构不同，从对应路径提取内容：

| 协议 | JSON 提取路径 |
|------|-------------|
| OpenAI 兼容 | `choices[0].message.content` |
| Qwen | `output.choices[0].message.content[0].text` |
| Gemini | `candidates[0].content.parts[0].text` |

### 阶段 7：解析模型输出为结构化对象

**涉及文件：** `LlmApiService.parseModelOutput()`、`DrugAnalyzeResponse.kt`

目标数据结构：

```kotlin
data class DrugAnalyzeResponse(
    val success: Boolean,
    val drugName: String,
    val usage: String,
    val dosage: String,
    val contraindications: String
)
```

解析流程：

```
模型输出文本
  ├─ 去除可能的 Markdown 包裹（```json … ```）
  ├─ Gson 解析为 JsonObject
  │     ├─ 成功 → 映射 drugName / usage / dosage / contraindications
  │     └─ 失败 → 降级：原始文本截取前 300 字放入 contraindications
  │                      drugName = "识别完成"
  └─ DrugAnalyzeResponse
```

**降级策略：** 即使模型未遵循 JSON 格式，眼镜端也不会崩溃，用户仍能看到模型原始输出（显示在 contraindications 区域）。

### 阶段 8–9：封装并返回给眼镜端

`LocalHttpServer` 将 `DrugAnalyzeResponse` 序列化为 JSON，作为 HTTP 200 响应体返回给眼镜端。

**无配置兜底：** 若未配置任何 API，`llmApiService` 为 `null`，`LocalHttpServer` 回退到 `buildMockResponse()`：

```kotlin
DrugAnalyzeResponse(
    success = true,
    drugName = "测试药物",
    usage = "温水送服",
    dosage = "一日三次，每次一片",
    contraindications = "不宜空腹服用，不宜与阿司匹林共服"
)
```

---

## 五、眼镜端结果展示

**涉及文件：** `DrugCaptureActivity.kt`、`activity_drug_capture.xml`

眼镜端 `DrugResult` 数据类与手机端 `DrugAnalyzeResponse` 字段一一对应：

```kotlin
data class DrugResult(
    val drugName: String,
    val usage: String,
    val dosage: String,
    val contraindications: String
)
```

`DrugResponse` sealed class 三级分类：

| 类型 | 触发条件 | 眼镜端行为 |
|------|---------|-----------|
| `Success` | HTTP 200 + JSON 解析成功 | 双目显示结果（`mBindingPair.updateView`） |
| `Failure` | 业务层错误（服务端返回 error、JSON 格式错误） | Toast 提示，不清除缓存地址 |
| `NetworkFailure` | 网络层错误（连接拒绝、超时） | 清除 `phoneHost`，重新 `startServiceDiscovery()` |

结果显示布局（`activity_drug_capture.xml`）：

| View ID | 显示内容 |
|---------|---------|
| `tvDrugName` | 药物名称（白色粗体） |
| `tvUsage` | "用法：xxx" |
| `tvDosage` | "用量：xxx" |
| `tvContraindications` | "禁忌：xxx"（黄色高亮） |

---

## 六、关键文件索引

### 手机端（phone_app）

| 文件 | 职责 |
|------|------|
| `server/LocalHttpServer.kt` | NanoHTTPD 路由：接收图片 → 调用 LLM → 返回 JSON |
| `service/HttpServerService.kt` | Android 前台 Service，持有 HTTP Server 和 LlmApiService 生命周期 |
| `service/LlmApiService.kt` | 读取 DataStore 配置 → Base64 编码 → 按协议构造请求 → 解析响应 |
| `service/ConnectionTestService.kt` | 最小化连通性测试（不带图片），供「测试连接」按钮使用 |
| `data/NetworkRepository.kt` | DataStore 持久化：API 配置列表 + 全局 Prompt |
| `data/ApiConfig.kt` | API 配置数据模型 + RequestFormatType 枚举 |
| `data/DrugAnalyzeResponse.kt` | 手机端返回给眼镜端的响应数据结构 |
| `network/local/PhoneBridgeServiceController.kt` | 统一生命周期协调器：HTTP 启动 → UDP → NSD 注册 |
| `network/local/LocalHttpServerManager.kt` | 封装 NanoHTTPD 启停，注入 LlmApiService |
| `network/local/NsdServicePublisher.kt` | NSD 注册/注销，服务类型 `_rayneo-pill._tcp.` |
| `network/local/UdpServiceBeacon.kt` | UDP 广播：每 2s → `255.255.255.255:5354` |
| `network/local/PhoneBridgePublishState.kt` | 发布状态 data class + StateFlow |

### 眼镜端（glass-app）

| 文件 | 职责 |
|------|------|
| `ui/activity/capture/DrugCaptureActivity.kt` | 核心 Activity：相机初始化 → 手势监听 → YUV 转换 → HTTP POST → 双目显示 |
| `network/PhoneServiceDiscovery.kt` | NSD + UDP Beacon 双通道服务发现 |
| `MyApplication.kt` | `MercurySDK.init(this)` 初始化 |

---

## 七、后续开发指引

### 接入新的大模型协议

1. 在 `ApiConfig.kt` 的 `RequestFormatType` 枚举中新增类型
2. 在 `LlmApiService.kt` 的 `when` 分支中新增对应方法（请求构造 + 响应路径提取）
3. 在 `ConnectionTestService.kt` 的 `when` 分支中新增对应最小化测试请求

### 扩展返回字段

修改 `DrugAnalyzeResponse.kt` 新增字段，同步修改：
- `LlmApiService.parseModelOutput()` 中的字段映射
- `LlmApiService.JSON_FORMAT_INSTRUCTION` 中的格式约束
- 眼镜端 `DrugResult` 数据类
- 眼镜端 `activity_drug_capture.xml` 布局

### 接入本地模型（离线识别）

在 `LocalHttpServer.handleAnalyzeDrug()` 中，将 `llmApiService?.analyze()` 替换为本地 TFLite/ONNX 推理调用，返回值类型保持 `DrugAnalyzeResponse` 不变，眼镜端无需改动。

### 接入监护人端推送

在 `LocalHttpServer.handleAnalyzeDrug()` 中识别完成后、返回响应之前插入推送逻辑，与 HTTP 服务解耦。

---

## 八、权限清单

### 手机端

| 权限 | 用途 |
|------|------|
| `INTERNET` | HTTP Server 接收请求 |
| `ACCESS_NETWORK_STATE` | 获取本机 IP |
| `ACCESS_WIFI_STATE` | 获取 Wi-Fi 状态 |
| `FOREGROUND_SERVICE` | 保持后台服务不被杀死 |
| `FOREGROUND_SERVICE_REMOTE_MESSAGING` | Android 14+ 前台服务类型声明 |
| `POST_NOTIFICATIONS` | Android 13+ 通知权限（运行时申请） |

### 眼镜端

| 权限 | 用途 |
|------|------|
| `CAMERA` | 拍摄药物图片 |
| `INTERNET` | HTTP POST 发送图片 |

### 眼镜端特殊配置

- `android:usesCleartextTraffic="true"` — Android 9+ 默认禁止 HTTP 明文，局域网必须开启
- `<meta-data android:name="com.rayneo.mercury.app" android:value="true" />` — RayNeo 平台标识

---

## 九、线程模型

### 眼镜端

| 操作 | 线程 |
|------|------|
| UI 初始化、手势响应 | 主线程 |
| ImageReader 回调 | `camera-bg` HandlerThread |
| YUV → Bitmap 转换 | `camera-bg` HandlerThread（回调内同步） |
| Bitmap → JPEG 压缩 | `Dispatchers.IO` |
| HTTP 请求/响应 | `Dispatchers.IO`（`sendToPhone` 内部 `withContext`） |
| UI 更新（结果显示） | 主线程（`Dispatchers.Main`） |
| UDP Beacon 接收 | 独立 daemon 线程 |

### 手机端

| 操作 | 线程 |
|------|------|
| HTTP 请求接收、路由 | NanoHTTPD 工作线程 |
| 图片读取、保存 | NanoHTTPD 工作线程（同步） |
| LLM 调用 | NanoHTTPD 工作线程（`runBlocking(Dispatchers.IO)`） |
| 广播通知 UI | NanoHTTPD 工作线程 → BroadcastReceiver → 主线程 |
| NSD 注册 | 主线程（`withContext(Dispatchers.Main)`） |
| UDP Beacon 发送 | `ScheduledExecutorService` 单线程 |
| DataStore 读写 | 调用方协程（`viewModelScope`） |

---

## 十、调试方法

### 确认服务运行

```bash
# 检查手机 8080 端口是否在监听
adb shell "ss -tlnp | grep 8080"
```

### 测试 HTTP 接口

```bash
curl -X POST http://192.168.43.1:8080/analyzeDrug \
  -H "Content-Type: image/jpeg" \
  --data-binary @test.jpg
```

### 查看日志

```bash
# 手机端
adb logcat -s LocalHttpServer HttpServerService LlmApiService PhoneBridgeController UdpServiceBeacon NsdServicePublisher

# 眼镜端
adb logcat -s DrugCapture PhoneServiceDiscovery
```

### 拉取图片缓存

```bash
adb pull /data/data/com.example.test/cache/drug_images/
```

### 崩溃日志

```bash
adb shell cat /data/data/com.example.test/files/crash.txt
```

---

## 十一、文档历史

| 版本 | 日期 | 变更 |
|------|------|------|
| v2.0 | 2026-04-09 | 以代码为准重写：响应字段更新为 `usage`/`dosage`/`contraindications`；更新服务发现为 NSD+UDP 双通道；补齐眼镜端动态寻址描述 |
| v1.0 | — | 旧版本（已归档），使用 `confidence`/`warningText`/`needConfirm` 字段，描述硬编码 IP 方案 |
