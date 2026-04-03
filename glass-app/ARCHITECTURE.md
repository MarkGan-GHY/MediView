# GlassTest 项目技术文档

> **项目**：RayNeo X3 Pro 智能用药辅助系统 — 眼镜端最小可运行 Demo
> **版本**：v0.1（通信链路验证阶段）
> **更新**：2026-04-01

---

## 1. 项目概述

本项目是运行在 **RayNeo X3 Pro AR 眼镜**上的 Android 应用，实现以下单一链路：

```
用户按下镜腿按钮（TempleAction.Click）
    → Camera2 拍照（YUV_420_888 1920×1080）
    → YUV → NV21 → YuvImage → Bitmap → JPEG byte[]
    → OkHttp POST http://192.168.43.1:8080/analyzeDrug（raw binary）
    → 手机返回 JSON { drugName, confidence, warningText, needConfirm }
    → 解析结果通过 mBindingPair.updateView 双目同步显示
```

网络拓扑：手机开启个人热点（IP 固定为 192.168.43.1:8080），眼镜连入该热点，局域网 HTTP 通信。

---

## 2. 技术栈

| 层次 | 技术 |
|------|------|
| 语言 | Kotlin |
| 最低 SDK | API 31（Android 12） |
| 编译 SDK | API 36 |
| 构建工具 | Gradle 9.2.1 + AGP 9.0.1 |
| UI 框架 | XML + ViewBinding（禁止 Compose） |
| AR 平台 SDK | MercuryAndroidSDK-v0.2.5（本地 AAR） |
| 相机 | Camera2 API |
| 网络 | OkHttp 4.12.0 |
| 异步 | Kotlin Coroutines 1.7.3 + lifecycle-runtime-ktx |

---

## 3. 文件结构

```
GlassTest/
├── app/
│   ├── libs/
│   │   └── MercuryAndroidSDK-v0.2.5-*.aar        # RayNeo 平台 SDK（本地依赖）
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/example/glasstest/
│   │   │   ├── MyApplication.kt                   # Application，初始化 MercurySDK
│   │   │   ├── MainActivity.kt                    # 保留的原始入口（已弃用，非 launcher）
│   │   │   └── ui/activity/capture/
│   │   │       └── DrugCaptureActivity.kt         # ★ 核心 Activity（唯一 launcher）
│   │   └── res/
│   │       └── layout/
│   │           └── activity_drug_capture.xml      # ★ 双目 UI 布局
│   └── build.gradle.kts
├── information/                                   # 技术文档目录
│   ├── Task.md
│   ├── 眼镜端对接技术文档.md
│   └── 眼镜拍照传输全流程对接文档.md
└── ARCHITECTURE.md                                # 本文档
```

---

## 4. 模块职责

### 4.1 MyApplication

- 继承 `Application`，在 `onCreate()` 中调用 `MercurySDK.init(this)`
- 必须早于任何 SDK 类使用，否则 `BaseMirrorActivity`、`TempleActionViewModel` 均不可用
- 在 `AndroidManifest.xml` 中声明为 `android:name=".MyApplication"`

### 4.2 DrugCaptureActivity（核心）

继承 `BaseMirrorActivity<ActivityDrugCaptureBinding>`，整合五个步骤：

#### Step 1 — Camera2 初始化

```
onCreate
  └─ setupCameraPreview()
       └─ mBindingPair.updateView { 为两眼各注册 SurfaceTextureListener }
            └─ onSurfaceTextureAvailable × 2 → surfaceList.size == 2
                 └─ delay(100ms) → setupCamera2()
                      └─ CameraManager.openCamera()
                           └─ stateCallback.onOpened()
                                └─ delay(100ms) → setUpImageReader()
                                     ├─ ImageReader(1920×1080, YUV_420_888, buffer=10)
                                     ├─ setOnImageAvailableListener(..., backHandler)
                                     └─ createCaptureSession(ImageReader + 2×Surface)
```

关键设计决策：
- `updateView {}` 对左右两眼各执行一次，因此两个 `SurfaceTextureListener` 产生两个 `Surface`，`surfaceList.size == 2` 是相机启动的前提
- `ImageReader` 使用后台 `HandlerThread`（`camera-bg`）处理回调，避免主线程阻塞
- `acquireLatestImage()` 丢弃积压旧帧，始终处理最新帧

#### Step 2 — TempleAction 手势监听

```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.RESUMED) {
        templeActionViewModel.state.collect { action -> ... }
    }
}
```

- `Click` → `takePhoto.set(true)`（AtomicBoolean，线程安全）
- `DoubleClick` → `finish()`（平台 UX 约定）
- `repeatOnLifecycle(RESUMED)` 确保后台时自动停止收集

#### Step 3 — YUV 图像转换

```
Image (YUV_420_888)
  planes[0] → Y buffer (ySize bytes)
  planes[2] → V buffer (vSize bytes)   ← NV21 中 V 在 U 之前
  planes[1] → U buffer (uSize bytes)
  → nv21[ySize + vSize + uSize]
  → YuvImage(NV21).compressToJpeg(quality=100)
  → BitmapFactory.decodeByteArray()   → Bitmap
  → Bitmap.compress(JPEG, quality=85) → ByteArray（用于 HTTP）
```

注意：先保存三个平面的 `remaining()` 大小，再调用 `get()`，避免 Buffer position 移动导致后续读取错误。

#### Step 4 — HTTP POST

```kotlin
suspend fun sendToPhone(jpegBytes: ByteArray): DrugResult? =
    withContext(Dispatchers.IO) {
        jpegBytes.toRequestBody("image/jpeg".toMediaType())
        // POST http://192.168.43.1:8080/analyzeDrug
        // Content-Type: image/jpeg（raw binary，禁止 Base64/multipart）
    }
```

- `OkHttpClient` 为成员变量，复用同一实例
- 连接超时 5s，读取超时 15s
- 完全在 `Dispatchers.IO` 执行，不阻塞主线程

#### Step 5 — 结果显示

- `mBindingPair.updateView {}` 同时更新左右两眼 UI
- `mBindingPair.setLeft {}` 用于左眼单侧初始化（提示文本）
- `FToast.show()` 显示 SDK 级 Toast

### 4.3 activity_drug_capture.xml

FrameLayout 根布局（无 ConstraintLayout 依赖），包含：

| View ID | 类型 | 用途 |
|---------|------|------|
| `cameraPreview` | TextureView | 全屏相机预览，双目各一个实例 |
| `tvCaptureHint` | TextView | 右上角操作提示"单击拍照 / 双击退出" |
| `tvStatus` | TextView | 居中状态提示"识别中..."（默认 gone） |
| `resultCard` | LinearLayout | 底部结果卡片（默认 gone） |
| `tvDrugName` | TextView | 药物名称 |
| `tvConfidence` | TextView | 置信度百分比 |
| `tvWarning` | TextView | 用药警告文本（黄色） |

---

## 5. 生命周期与资源管理

```
onCreate   → backHandlerThread.start()
           → setupTempleActions()
           → setupCameraPreview()

onStop     → closeCamera()
               ├─ cameraCaptureSession.close()
               ├─ cameraDevice.close()
               ├─ imageReader.close()
               └─ surfaceList.clear()

onDestroy  → backHandlerThread.quitSafely()
           → httpClient.dispatcher.executorService.shutdown()
```

---

## 6. 线程模型

| 操作 | 线程 |
|------|------|
| UI 初始化、手势响应 | 主线程（Main） |
| ImageReader 回调 | `camera-bg` HandlerThread |
| YUV→Bitmap 转换 | `camera-bg` HandlerThread（在回调内） |
| Bitmap→JPEG 压缩 | `Dispatchers.IO` |
| HTTP 请求/响应 | `Dispatchers.IO` |
| UI 更新（结果显示） | 主线程（`Dispatchers.Main`） |

---

## 7. AndroidManifest 关键配置

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.INTERNET" />

<application android:usesCleartextTraffic="true">
    <!-- Android 9+ 默认禁止 HTTP 明文，局域网热点必须开启 -->

    <meta-data android:name="com.rayneo.mercury.app" android:value="true" />
    <!-- RayNeo 平台识别标识，缺少则 SDK 功能不可用 -->
</application>
```

---

## 8. Gradle 依赖

```kotlin
// 本地 SDK
implementation(fileTree("libs"))              // MercuryAndroidSDK-v0.2.5.aar

// AndroidX
implementation("androidx.appcompat:appcompat:1.3.0")
implementation("androidx.fragment:fragment-ktx:1.5.3")
implementation("androidx.core:core-ktx:1.8.0")
implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")

// 协程
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

// HTTP
implementation("com.squareup.okhttp3:okhttp:4.12.0")
```

---

## 9. 关键约束与限制

| 约束 | 原因 |
|------|------|
| 禁止 Compose | RayNeo SDK 的 `BaseMirrorActivity` 基于 XML ViewBinding |
| 禁止 IPC 传图 | 通信方案固定为 HTTP，架构简单、跨设备互通 |
| 禁止 Base64/multipart | 增大传输体积，手机端不做解码，raw binary 最直接 |
| ImageReader 必须 close | 不 close 会耗尽 buffer（默认 10 个），相机停止出帧 |
| usesCleartextTraffic=true | Android 9+ 网络安全策略默认拒绝 http:// 明文流量 |

---

## 11. 已知修正

| 问题 | 原因 | 修正 |
|------|------|------|
| `Unresolved reference 'YuvImage'` | 技术文档中示例代码错误地写成 `android.media.YuvImage` | 正确包路径为 `android.graphics.YuvImage` |


| 阶段 | 内容 |
|------|------|
| v0.2 | 接入本地 TFLite 药物识别模型，替换手机端模拟响应 |
| v0.3 | 数据库记录用药历史（Room DAO） |
| v0.4 | needConfirm=true 时的二次确认对话框（FDialog） |
| v0.5 | 监护人端推送通知（独立推送模块） |
