# MediView 项目分析报告

> 分析日期：2026-04-09  
> 分析范围：`glass-app`（眼镜端）+ `phone_app`（手机端）全部源码、文档、配置

---

## 一、项目定位

**MediView** 是一个基于 **RayNeo X3 Pro AR 眼镜** 的智能用药辅助系统，采用三端协同架构：

```
眼镜端（拍照+显示） ──HTTP──► 使用者手机端（服务端+大模型调用） ──► 监护人手机端（待开发）
```

当前处于 **v0.1 最小可运行 Demo 阶段**，只验证「眼镜拍照 → 手机接收 → 大模型识别 → 结果回传眼镜」这条核心通信链路。

---

## 二、仓库结构

```
MediView/
├── glass-app/                          ← 眼镜端 Android 应用
│   ├── app/libs/                       ← MercuryAndroidSDK-v0.2.5.aar
│   ├── app/src/main/java/com/example/glasstest/
│   │   ├── MyApplication.kt            ← MercurySDK 初始化
│   │   ├── network/
│   │   │   └── PhoneServiceDiscovery.kt ← NSD + UDP 双通道发现
│   │   └── ui/activity/capture/
│   │       └── DrugCaptureActivity.kt   ← ★ 核心 Activity
│   ├── ARCHITECTURE.md                 ← 眼镜端技术文档
│   ├── 眼镜端对接文档.md
│   └── information/                    ← 其他参考文档
│
├── phone_app/                          ← 手机端 Android 应用
│   ├── app/src/main/java/com/example/test/
│   │   ├── MainActivity.kt             ← Compose 入口 + 底部导航
│   │   ├── ServiceScreen.kt            ← 服务控制页（启停+日志）
│   │   ├── NetworkScreen.kt            ← API 配置管理页
│   │   ├── data/                       ← 数据层
│   │   │   ├── ApiConfig.kt            ← API 配置模型
│   │   │   ├── DrugAnalyzeResponse.kt  ← 响应数据类
│   │   │   └── NetworkRepository.kt    ← DataStore CRUD
│   │   ├── server/
│   │   │   └── LocalHttpServer.kt      ← NanoHTTPD 路由处理
│   │   ├── service/
│   │   │   ├── HttpServerService.kt    ← 前台 Service 宿主
│   │   │   ├── LlmApiService.kt        ← 大模型调用（3 协议）
│   │   │   └── ConnectionTestService.kt ← API 连通性测试
│   │   └── network/
│   │       ├── local/                  ← 局域网服务发布
│   │       │   ├── PhoneBridgeServiceController.kt
│   │       │   ├── LocalHttpServerManager.kt
│   │       │   ├── NsdServicePublisher.kt
│   │       │   ├── UdpServiceBeacon.kt
│   │       │   └── PhoneBridgePublishState.kt
│   │       ├── NetworkViewModel.kt     ← 配置管理 ViewModel
│   │       └── *.kt                    ← Compose 组件
│   ├── MediView网络功能说明文档.md       ← 网络全链路详解
│   ├── 手机端设计.txt                   ← 初始需求文档
│   └── 眼镜端对接文档.md
│
└── MediView手机眼镜连接方案.md           ← 架构总览 + 故障排除
```

两个子项目是**独立的 Android 工程**，各自有独立的 Gradle wrapper、`build.gradle.kts`、`settings.gradle.kts`，不共享模块。

---

## 三、全局架构

```
┌──────────────────────────────────────────────────────────────┐
│                  手机热点局域网（通常 192.168.43.0/24）          │
│                                                              │
│  ┌─────────────────────────────┐  ┌────────────────────────┐ │
│  │  phone_app（服务端）          │  │  glass-app（客户端）     │ │
│  │                             │  │                        │ │
│  │  NanoHTTPD 监听 :8080       │◄─│  OkHttp POST           │ │
│  │  NSD 注册 _rayneo-pill._tcp │──│  NSD 发现 + Resolve    │ │
│  │  UDP Beacon → :5354         │──│  UDP 监听 ← :5354      │ │
│  │  LlmApiService → 大模型云端  │  │  Camera2 拍照           │ │
│  │  DataStore 持久化配置        │  │  Mercury SDK 双目渲染    │ │
│  └─────────────────────────────┘  └────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
```

**关键设计决策：**
- 手机作为 HTTP 服务端（算力强、IP 固定），眼镜作为客户端（轻量、只拍照和显示）
- NSD（mDNS）+ UDP Beacon **双通道**服务发现——UDP 广播用于穿透国产 ROM 热点模式下的 mDNS 限制
- HTTP 传输 raw JPEG binary（禁止 Base64/multipart），最小化传输体积

---

## 四、glass-app（眼镜端）分析

### 4.1 技术栈

| 维度 | 选型 | 说明 |
|------|------|------|
| 语言 | Kotlin | |
| minSdk / compileSdk | 31 / 36 | Android 12+ |
| UI 框架 | XML + ViewBinding | RayNeo SDK 强制，禁止 Compose |
| AR 平台 | MercuryAndroidSDK v0.2.5 | 本地 AAR，提供双目渲染、镜腿手势 |
| 相机 | Camera2 API | YUV_420_888 → NV21 → JPEG |
| HTTP 客户端 | OkHttp 4.12.0 | |
| 异步 | Kotlin Coroutines 1.7.3 | |

### 4.2 核心文件职责

| 文件 | 行数 | 职责 |
|------|------|------|
| `MyApplication.kt` | 14 | `onCreate()` 调用 `MercurySDK.init(this)`，必须早于任何 SDK 类使用 |
| `DrugCaptureActivity.kt` | ~380 | 核心 Activity：相机初始化 → 手势监听 → YUV 转换 → HTTP POST → 双目显示 |
| `PhoneServiceDiscovery.kt` | ~175 | NSD + UDP Beacon 双通道并行发现，resolve 并发控制 |
| `activity_drug_capture.xml` | ~70 | FrameLayout 布局：TextureView 预览 + 提示文本 + 状态文本 + 结果卡片 |

### 4.3 主链路详解

`DrugCaptureActivity` 继承 `BaseMirrorActivity<ActivityDrugCaptureBinding>`，整合五个步骤：

#### Step 1 — Camera2 初始化

```
onCreate → setupCameraPreview()
  → mBindingPair.updateView {} 为左右两眼各注册 SurfaceTextureListener
    → 两个 Surface 就绪 → delay(100ms) → setupCamera2()
      → CameraManager.openCamera()
        → stateCallback.onOpened()
          → setUpImageReader()
            → ImageReader(1920×1080, YUV_420_888, buffer=10)
            → setOnImageAvailableListener(backHandler)
            → createCaptureSession(ImageReader + 2×Surface)
```

**关键设计：**
- `updateView {}` 对左右两眼各执行一次，产生两个 `Surface`
- `ImageReader` 回调在 `camera-bg` HandlerThread，不阻塞主线程
- `acquireLatestImage()` 丢弃积压旧帧，始终处理最新帧
- FPS 锁定 `Range(5, 10)` 降低功耗

#### Step 2 — 镜腿手势监听

```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.RESUMED) {
        templeActionViewModel.state.collect { action ->
            when (action) {
                is TempleAction.Click -> takePhoto.set(true)
                is TempleAction.DoubleClick -> finish()
            }
        }
    }
}
```

- `AtomicBoolean takePhoto` 保证线程安全
- `repeatOnLifecycle(RESUMED)` 确保后台自动停止收集

#### Step 3 — YUV → Bitmap 转换

```
Image (YUV_420_888)
  planes[0] → Y buffer
  planes[2] → V buffer  ← NV21 中 V 在 U 之前
  planes[1] → U buffer
  → nv21[ySize + vSize + uSize]
  → YuvImage(NV21).compressToJpeg(quality=100)  → ByteArray
  → BitmapFactory.decodeByteArray()              → Bitmap
  → Bitmap.compress(JPEG, quality=85)            → HTTP 用 ByteArray
```

**注意：** 先保存 `remaining()` 再 `get()`，避免 Buffer position 移动导致错误。

#### Step 4 — HTTP POST

```kotlin
suspend fun sendToPhone(jpegBytes: ByteArray): DrugResponse
    = withContext(Dispatchers.IO) {
        // POST http://{phoneHost}:{phonePort}/analyzeDrug
        // Content-Type: image/jpeg（raw binary）
    }
```

- OkHttpClient 复用单例，连接超时 5s，读取超时 15s
- 返回 `DrugResponse` sealed class 三类结果

#### Step 5 — 结果显示

```kotlin
mBindingPair.updateView {
    tvDrugName.text    = result.drugName
    tvConfidence.text  = "置信度：${result.confidence * 100}%"
    tvWarning.text     = result.warningText
    resultCard.visibility = View.VISIBLE
}
```

- `mBindingPair.updateView {}` 同时更新左右两眼
- `mBindingPair.setLeft {}` 仅左眼单侧初始化

### 4.4 错误处理

`DrugResponse` sealed class 三级分类：

| 类型 | 触发条件 | 行为 |
|------|---------|------|
| `Success` | HTTP 200 + JSON 解析成功 | 双目显示结果 |
| `Failure` | 业务层错误（服务端返回 error、JSON 格式错误） | Toast 提示，不清除缓存地址 |
| `NetworkFailure` | 网络层错误（连接拒绝、超时） | 清除 `phoneHost`，重新 `startServiceDiscovery()` |

---

## 五、phone_app（手机端）分析

### 5.1 技术栈

| 维度 | 选型 | 说明 |
|------|------|------|
| 语言 | Kotlin | |
| minSdk / compileSdk | 31 / 36 | |
| UI 框架 | Jetpack Compose + Material 3 | |
| HTTP Server | NanoHTTPD（~50KB） | 纯 Java，专为嵌入式/Android 设计 |
| HTTP Client | OkHttp 4.12.0 | 调用大模型 API |
| JSON | Gson | 依赖小，无需 Kotlin 序列化插件 |
| 持久化 | DataStore Preferences | API 配置 + 全局 Prompt |
| 架构 | MVVM | ViewModel + Repository + StateFlow |

### 5.2 核心模块职责

#### 5.2.1 服务层

| 文件 | 职责 |
|------|------|
| `HttpServerService.kt` | Android 前台 Service，持有 HTTP Server 和 LlmApiService 的生命周期 |
| `LocalHttpServer.kt` | NanoHTTPD 子类：`POST /analyzeDrug` 路由、图片读取、LLM 调用、JSON 响应 |
| `LlmApiService.kt` | 读取 DataStore 配置 → Base64 编码图片 → 按协议构造请求 → 解析模型输出 |
| `ConnectionTestService.kt` | 不带图片的最小化连通性测试（`max_tokens=1`），供 UI 「测试连接」按钮使用 |

#### 5.2.2 局域网服务发布

| 文件 | 职责 |
|------|------|
| `PhoneBridgeServiceController.kt` | 统一生命周期协调器：HTTP 启动 → UDP → NSD 注册（顺序启动，逆序停止） |
| `LocalHttpServerManager.kt` | 封装 NanoHTTPD 启停，注入 LlmApiService |
| `NsdServicePublisher.kt` | NSD 注册/注销，服务类型 `_rayneo-pill._tcp.`，服务名 `rayneo_phone_bridge` |
| `UdpServiceBeacon.kt` | 每 2 秒向 `255.255.255.255:5354` 发送 `MEDIVIEW_SERVICE <port> <serviceName>` |
| `PhoneBridgePublishState.kt` | 发布状态 data class |

#### 5.2.3 数据层

| 文件 | 职责 |
|------|------|
| `ApiConfig.kt` | API 配置模型 + `RequestFormatType` 枚举（4 种协议） |
| `DrugAnalyzeResponse.kt` | 响应数据类（success, drugName, confidence, warningText, needConfirm） |
| `NetworkRepository.kt` | DataStore CRUD：API 配置列表 + 全局 Prompt |

#### 5.2.4 UI 层

| 文件 | 职责 |
|------|------|
| `MainActivity.kt` | Compose 入口 + 底部导航（服务 / 网络设置） |
| `ServiceScreen.kt` | 服务启停控制 + 本机 IP 显示 + 实时请求日志（BroadcastReceiver） |
| `NetworkScreen.kt` | API 配置列表（新增/编辑/删除/设为默认/启用切换/测试连接） + 全局 Prompt 编辑 |
| `NetworkViewModel.kt` | 配置管理 ViewModel |

### 5.3 服务端启动流程

```
用户点击「启动服务」
  → HttpServerService.startForegroundService(ACTION_START)
    → startForeground()  // Android 14+ 声明 foregroundServiceType
    → PhoneBridgeServiceController.start()
      ├── LocalHttpServerManager.start()
      │     └── NanoHTTPD 绑定 0.0.0.0:8080
      ├── UdpServiceBeacon.start(8080, "rayneo_phone_bridge")
      │     └── 每 2s 向 255.255.255.255:5354 广播
      └── NsdServicePublisher.register(8080)
            └── mDNS 发布 _rayneo-pill._tcp. 服务
```

### 5.4 HTTP 请求处理全链路

```
眼镜 POST /analyzeDrug (JPEG binary)
  └─ LocalHttpServer.serve()
       ├─ readImageBytes()  // 从 inputStream 按 content-length 循环读取
       ├─ saveImageToCache()  // 写入 /cache/drug_images/img_{timestamp}.jpg
       ├─ broadcastRequestReceived()  // 通知 UI 更新日志（并行）
       ├─ [有 LLM 配置] → LlmApiService.analyze(imageBytes)
       │     ├─ 读取默认 ApiConfig（DataStore）
       │     ├─ 拼接 Prompt + JSON_FORMAT_INSTRUCTION
       │     ├─ Base64 编码图片
       │     ├─ 按 RequestFormatType 分支构造请求体
       │     ├─ OkHttp POST → 大模型云端
       │     ├─ 提取模型输出文本（按协议路径）
       │     └─ parseModelOutput() → DrugAnalyzeResponse
       └─ [无 LLM 配置] → buildMockResponse()
             └─ 固定测试数据：drugName="测试药物", confidence=0.95
```

### 5.5 大模型协议适配

| 协议 | `RequestFormatType` | 鉴权 | API 层 JSON 约束 |
|------|---------------------|------|-----------------|
| OpenAI 兼容 | `OPENAI_COMPATIBLE` | `Authorization: Bearer` 头 | `response_format: json_object` ✅ |
| 通义千问 | `QWEN` | `Authorization: Bearer` 头 | ❌ 仅 Prompt 约束 |
| Gemini | `GEMINI` | `?key=` URL 参数 | `response_mime_type: application/json` ✅ |
| 自定义 | `CUSTOM` | `Authorization: Bearer` 头 | `response_format: json_object` ✅ |

**容错机制：**

1. **JSON 解析失败** → 降级为纯文本模式：`drugName="识别完成"`、`needConfirm=true`、`warningText` 放置原始输出前 300 字
2. **未配置默认 API** → `llmApiService == null`，返回 `buildMockResponse()`，服务不中断
3. **LLM 调用异常** → 返回 `DrugAnalyzeResponse(success=false, warningText=错误描述)`
4. **崩溃日志** → `App.kt` 注册全局 `UncaughtExceptionHandler`，写入 `files/crash.txt`

### 5.6 两种 JsonObject 构建方式

项目中使用 Gson `JsonObject` 构建器构造请求体（`LlmApiService.kt`），而非手动拼接 JSON 字符串或使用 `JSONObject`。**优势：**

- Gson 自动处理字符串转义，避免手动拼接 JSON 的安全问题
- 类型安全的结构化构造
- 与项目整体的 Gson 序列化方案统一

注意：眼镜端 `DrugCaptureActivity.kt` 中解析响应仍使用 `org.json.JSONObject`，与手机端 Gson 不一致，但不影响功能。

---

## 六、两个项目对比

| 维度 | glass-app（眼镜端） | phone_app（手机端） |
|------|-------------------|-------------------|
| 角色 | HTTP 客户端 | HTTP 服务端 |
| UI 框架 | XML + ViewBinding | Jetpack Compose |
| AR 平台 | MercuryAndroidSDK | 无 |
| 相机 | Camera2 API | 无 |
| 核心复杂度 | 相机管线 + YUV 转换 + 双目渲染 | 大模型协议适配 + 服务发现发布 + 配置管理 UI |
| 代码量（Kotlin） | ~650 行 | ~1400 行 |
| 成熟度 | 通信链路验证阶段 | 功能较完整 |
| 架构模式 | 单 Activity | MVVM（ViewModel + Repository + StateFlow） |

---

## 七、通信协议

### 7.1 HTTP 接口

```
POST http://{phoneHost}:8080/analyzeDrug
Content-Type: image/jpeg
Body: 原始 JPEG 字节流
```

**成功响应（200）：**

```json
{
  "success": true,
  "drugName": "阿莫西林胶囊",
  "confidence": 0.92,
  "warningText": "青霉素类抗生素，过敏者禁用",
  "needConfirm": false
}
```

**失败响应：**

```json
{
  "success": false,
  "error": "错误描述"
}
```

### 7.2 服务发现

| 通道 | 协议 | 端口 | 格式 |
|------|------|------|------|
| NSD（主） | mDNS (DNS-SD) | — | `_rayneo-pill._tcp.` / `rayneo_phone_bridge` |
| UDP Beacon（备） | UDP 广播 | 5354 | `MEDIVIEW_SERVICE <port> <serviceName>` |

### 7.3 端口与常量汇总

| 用途 | 协议 | 端口/值 | 定义位置 |
|------|------|---------|---------|
| HTTP 服务 | TCP | 8080 | `HttpServerService.SERVER_PORT` |
| UDP Beacon | UDP | 5354 | `UdpServiceBeacon.BEACON_PORT` |
| NSD 服务类型 | mDNS | `_rayneo-pill._tcp.` | `NsdServicePublisher.SERVICE_TYPE` |
| NSD 服务名 | mDNS | `rayneo_phone_bridge` | `NsdServicePublisher.SERVICE_NAME` |
| UDP 消息前缀 | — | `MEDIVIEW_SERVICE` | `UdpServiceBeacon.MESSAGE_PREFIX` |
| 广播间隔 | — | 2000ms | `UdpServiceBeacon.BROADCAST_INTERVAL_MS` |

---

## 八、线程模型

### 眼镜端

| 操作 | 线程 |
|------|------|
| UI 初始化、手势响应 | 主线程（Main） |
| ImageReader 回调 | `camera-bg` HandlerThread |
| YUV → Bitmap 转换 | `camera-bg` HandlerThread（回调内同步） |
| Bitmap → JPEG 压缩 | `Dispatchers.IO` |
| HTTP 请求/响应 | `Dispatchers.IO`（`sendToPhone` 内部 `withContext`） |
| UI 更新（结果显示） | 主线程（`lifecycleScope.launch(Dispatchers.Main)`） |
| NSD 回调 | 系统内部线程 |
| UDP Beacon 接收 | 独立 daemon 线程 `UdpBeaconReceiver` |

### 手机端

| 操作 | 线程 |
|------|------|
| HTTP 请求接收、路由 | NanoHTTPD 工作线程 |
| 图片读取、保存 | NanoHTTPD 工作线程（同步） |
| LLM 调用 | NanoHTTPD 工作线程（`runBlocking(Dispatchers.IO)`） |
| 广播通知 UI | NanoHTTPD 工作线程 → BroadcastReceiver → 主线程 |
| NSD 注册 | 主线程（`withContext(Dispatchers.Main)`） |
| UDP Beacon 发送 | `ScheduledExecutorService` 单线程 |
| UI 状态更新 | 主线程（Compose State） |
| DataStore 读写 | 调用方协程（`viewModelScope`） |

---

## 九、成熟度评估

### 已完成 ✅

- 眼镜端 Camera2 拍照 → YUV 转 JPEG 完整管线
- 眼镜端 `mBindingPair` 双目渲染
- 眼镜端 NSD + UDP Beacon 双通道服务发现
- 眼镜端网络失败自动重连
- 手机端 NanoHTTPD 本地 HTTP 服务
- 手机端 Android 前台 Service（防系统杀死）
- 手机端 NSD 注册 + UDP Beacon 广播
- 手机端三种大模型协议适配（OpenAI / Qwen / Gemini）
- 手机端 DataStore 配置持久化
- 手机端 Compose UI（服务控制 + API 配置管理）
- 请求日志实时显示（BroadcastReceiver）
- 崩溃日志写入文件
- 图片本地缓存（调试用）
- 无配置时模拟数据降级

### 待实现（路线图）

| 版本 | 内容 |
|------|------|
| v0.2 | 接入本地 TFLite 药物识别模型，替换手机端模拟响应 |
| v0.3 | 数据库记录用药历史（Room DAO） |
| v0.4 | `needConfirm=true` 时的二次确认对话框（FDialog） |
| v0.5 | 监护人端推送通知 |

---

## 十、文档质量评价

| 文档 | 质量 | 说明 |
|------|------|------|
| `MediView手机眼镜连接方案.md` | ⭐⭐⭐⭐⭐ | 架构、流程、故障排除、权限清单、检查清单 |
| `MediView网络功能说明文档.md` | ⭐⭐⭐⭐⭐ | 全链路 9 阶段详解，扩展指引详尽 |
| `眼镜端对接文档.md` | ⭐⭐⭐⭐ | HTTP 协议、发现流程、示例代码、常见问题 |
| `ARCHITECTURE.md` | ⭐⭐⭐⭐ | 眼镜端模块设计、线程模型、已知修正 |
| `眼镜拍照传输全流程对接文档.md` | ⭐⭐⭐ | 拍照传输详细步骤 |
| `NetworkTask.md` / `NetworkTaskSummary.md` | ⭐⭐⭐ | 网络任务设计 |
| 代码注释 | ⭐⭐⭐⭐⭐ | 关键类/方法有清晰注释，扩展点有明确标注 |

---

## 十一、潜在问题与改进建议

### 11.1 安全性

| 问题 | 风险等级 | 建议 |
|------|---------|------|
| UDP Beacon 明文无认证 | 低（Demo 阶段可接受） | 后续加入简单签名或一次性 token |
| HTTP 明文传输（`usesCleartextTraffic=true`） | 低（局域网环境） | 当前阶段可接受，后续考虑局域网内 TLS |
| API Key 明文存储在 DataStore | 中 | 生产环境建议使用 EncryptedSharedPreferences |

### 11.2 稳定性

| 问题 | 影响 | 建议 |
|------|------|------|
| Qwen 协议仅靠 Prompt 约束 JSON | 模型可能输出非 JSON，触发降级 | 改用 DashScope OpenAI 兼容端点走 `OPENAI_COMPATIBLE` |
| `ImageReader buffer=10` | 极高拍照频率下可能耗尽 | 当前手动触发拍照风险低，后续若连续拍照需增大 buffer |
| phone_app `runBlocking` 阻塞 NanoHTTPD 工作线程 | LLM 调用期间该线程无法处理其他请求 | 当前单用户场景可接受，多请求场景改为异步响应 |

### 11.3 工程规范

| 问题 | 建议 |
|------|------|
| `眼镜端对接文档.md` 在两个项目中各有一份完全相同的副本 | 保留一份在根目录，子项目用引用 |
| glass-app `build.gradle.kts` 开启了 `compose = true` 但未使用 | 关闭以减小 APK 体积 |
| glass-app 的 `build.gradle.kts` 中 `core-ktx` 依赖声明了两次（1.7.0 和 1.8.0） | 去重，只保留高版本 |
| 眼镜端用 `org.json.JSONObject`，手机端用 Gson | 眼镜端也改用 Gson 保持统一（`okhttp` 不强制依赖 Gson，需额外添加） |

### 11.4 架构一致性

| 差异 | 眼镜端 | 手机端 |
|------|--------|--------|
| UI 框架 | XML + ViewBinding | Jetpack Compose |
| 架构模式 | 单 Activity 无 ViewModel | MVVM |
| JSON 库 | org.json.JSONObject | Gson |

这些差异大多有合理原因（RayNeo SDK 强制 ViewBinding、手机端需要复杂的配置管理 UI），当前阶段可接受。

---

## 十二、快速上手

### 手机端

```bash
cd phone_app
./gradlew installDebug
# 打开 App → 「服务」→ 启动服务
# 「网络设置」→ 添加 API 配置 → 设为默认
# 确认通知栏有「药物识别服务运行中」持久通知
```

### 眼镜端

```bash
cd glass-app
# 将 MercuryAndroidSDK-v0.2.5.aar 放入 app/libs/
./gradlew installDebug
# 眼镜连接手机热点 → 打开 App
# 看到「单击拍照 / 双击退出」即表示已发现手机服务
# 单击镜腿按钮拍照测试
```

### 无眼镜调试

```bash
# 手机启动服务后，用 curl 测试
curl -X POST http://192.168.43.1:8080/analyzeDrug \
  -H "Content-Type: image/jpeg" \
  --data-binary @test.jpg
```

---

## 十三、数据流示意图

```
[眼镜端]
  镜腿 Click
    ↓
  Camera2 拍照 (YUV_420_888 1920×1080)
    ↓
  YUV → NV21 → YuvImage → Bitmap → JPEG(85%)
    ↓
  OkHttp POST /analyzeDrug  ──────────────────────┐
                                                   │
[手机端]                                           │
  NanoHTTPD :8080 ←────────────────────────────────┘
    ↓
  读取 inputStream (content-length 字节)
    ↓
  保存到 /cache/drug_images/ (调试)
    ↓
  [有 LLM 配置?]
    ├─ YES → LlmApiService.analyze()
    │         ├─ DataStore 读取默认 ApiConfig
    │         ├─ 拼接 Prompt + JSON 格式约束
    │         ├─ Base64 编码
    │         ├─ 按协议构造请求体
    │         ├─ OkHttp HTTPS → 大模型云端
    │         ├─ 提取输出文本
    │         └─ parseModelOutput() → DrugAnalyzeResponse
    └─ NO  → buildMockResponse()
    ↓
  Gson.toJson(response) → HTTP 200 ──────────────┐
                                                   │
[眼镜端]                                           │
  JSON 解析 ←──────────────────────────────────────┘
    ↓
  DrugResponse.Success / Failure / NetworkFailure
    ↓
  mBindingPair.updateView {} 双目显示
    ├─ tvDrugName: 药物名称
    ├─ tvConfidence: 置信度
    └─ tvWarning: 注意事项
```
