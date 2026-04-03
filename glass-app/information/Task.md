你的任务是：基于 RayNeo X3 Pro AR 眼镜 SDK，实现一个“最小可运行 Demo（眼镜端）”。

⚠️ 我已经提供完整技术文档，请严格按照"information"文件夹中的文档实现，不允许自行简化 camera2、通信流程或 SDK 使用方式，或者编造API等。

========================
一、目标
========================

实现完整链路：

TempleAction.Click
→ camera2 拍照（YUV_420_888）
→ 转换为 JPEG byte[]
→ OkHttp POST（raw binary）
→ 手机返回 JSON
→ 解析并通过 mBindingPair.updateView 显示

这是唯一目标，不需要任何额外功能。

========================
二、通信方案（严格约束）
========================

- 使用 HTTP（禁止使用 IPC）
- URL: http://192.168.43.1:8080/analyzeDrug
- Method: POST
- Content-Type: image/jpeg
- Body: JPEG 原始字节（raw binary）

⚠️ 禁止：
- Base64
- multipart/form-data

响应 JSON：

{
"success": true,
"drugName": "...",
"confidence": 0.95,
"warningText": "...",
"needConfirm": true
}

========================
三、相机实现（必须严格按文档）
========================

必须完全按以下流程实现：

1. ImageReader：
    - 格式：ImageFormat.YUV_420_888
    - 分辨率：1920×1080
    - buffer >= 2

2. 拍照触发机制（关键）：

    - 使用 AtomicBoolean takePhoto
    - TempleAction.Click → takePhoto.set(true)
    - 在 ImageReader 回调中检测 takePhoto

3. 图像转换流程（必须一致）：

   Image
   → YUV planes
   → NV21 byte[]
   → YuvImage.compressToJpeg()
   → Bitmap
   → Bitmap.compress() → JPEG byte[]

4. ImageReader 回调必须：

    - 在 backgroundHandler 执行（非主线程）
    - 使用 acquireLatestImage()
    - 每次必须调用 image.close()

5. 相机启动条件：

    - 使用两个 TextureView（左右眼）
    - 必须等两个 Surface 都 ready
    - surfaceList.size == 2 后才能调用 setupCamera2()

6. CaptureSession：

   输出必须包含：
    - imageReader.surface
    - 左右两个 preview Surface

========================
四、RayNeo UI 约束（必须遵守）
========================

- Activity 必须继承 BaseMirrorActivity
- 所有 UI 更新必须使用：

  mBindingPair.updateView { }

- 事件绑定必须使用：

  mBindingPair.setLeft { }

页面必须包含：

- 拍照按钮
- 状态文本（识别中）
- 结果显示（drugName / confidence / warningText）

========================
五、手势系统（必须符合规范）
========================

使用 TempleActionViewModel：

- Click → 触发拍照
- DoubleClick → finish()

必须使用：

lifecycleScope + repeatOnLifecycle + collect

========================
六、线程与协程（必须正确）
========================

- ImageReader → backgroundHandler
- HTTP → Dispatchers.IO
- UI更新 → 主线程

========================
七、资源释放（必须实现）
========================

onStop：

- cameraCaptureSession.close()
- cameraDevice.close()
- imageReader.close()

onDestroy：

- HandlerThread.quitSafely()

========================
八、日志（必须输出）
========================

必须打印：

- 相机打开成功
- takePhoto 触发
- 图片字节大小
- HTTP请求发送
- HTTP响应结果
- JSON解析结果

========================
九、权限与配置（必须包含）
========================

Manifest 必须包含：

- CAMERA
- INTERNET
- usesCleartextTraffic = true
- meta-data: com.rayneo.mercury.app = true

========================
十、禁止事项（重要）
========================

禁止：

- 使用 IPC 进行图片传输
- 简化 camera2 流程
- 使用错误 ImageReader 格式
- 忽略 image.close()
- 在主线程执行网络请求
- 使用 Compose（使用 XML + ViewBinding）

========================
十一、输出要求
========================

请输出完整 Kotlin 代码，包括：

- Activity（完整实现）
- Camera逻辑（可独立类或内嵌）
- HTTP通信代码
- 数据类
- Manifest
- Gradle依赖

要求：

- 代码尽量可直接运行
- 若 RayNeo SDK API 签名不确定，请标注：
  “需根据本地 SDK 调整”

========================
开始生成代码
========================