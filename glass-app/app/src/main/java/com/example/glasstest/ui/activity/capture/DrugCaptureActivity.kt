package com.example.glasstest.ui.activity.capture

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.graphics.YuvImage
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.annotation.RequiresApi
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.glasstest.databinding.ActivityDrugCaptureBinding
import com.example.glasstest.network.PhoneServiceDiscovery
import com.ffalcon.mercury.android.sdk.touch.TempleAction
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
import com.ffalcon.mercury.android.sdk.ui.toast.FToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "DrugCapture"

// ★ BaseMirrorActivity 泛型参数为 ViewBinding 类名，需根据本地 SDK 调整
class DrugCaptureActivity : BaseMirrorActivity<ActivityDrugCaptureBinding>() {

    // ──────────────── 相机相关 ────────────────
    private var cameraDevice: CameraDevice? = null
    private lateinit var cameraManager: CameraManager
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private val surfaceList = mutableListOf<Surface>()

    // 后台线程处理 ImageReader 回调，避免主线程阻塞
    private lateinit var backHandler: Handler
    private val backHandlerThread = object : HandlerThread("camera-bg") {
        override fun onLooperPrepared() {
            backHandler = Handler(this.looper)
        }
    }

    // 拍照触发标志（AtomicBoolean 保证线程安全）
    val takePhoto = AtomicBoolean(false)

    // 过滤开机后前 1 秒不稳定帧
    private var openTime = -1L

    // ──────────────── 网络相关 ────────────────
    // 协议异步化后单次请求都是秒级响应（submit 返回 taskId、poll 返回当前状态），
    // 短超时即可。LLM 慢响应在轮询过程中等待，不靠 HTTP 长连接顶。
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    // NSD 服务发现，动态获取手机 host:port
    // lazy 延迟初始化：Activity 构造时 attachBaseContext 尚未调用，getSystemService 会 NPE
    private val phoneDiscovery by lazy { PhoneServiceDiscovery(this) }

    @Volatile private var phoneHost: String? = null
    @Volatile private var phonePort: Int = 8080

    // ──────────────── 状态 ────────────────
    private var isProcessing = false

    // ════════════════════════════════════════
    //  生命周期
    // ════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backHandlerThread.start()
        setupTempleActions()
        setupCameraPreview()
        startServiceDiscovery()

        // 左眼初始化提示文本（仅左眼设置，符合 setLeft 使用规范）
        mBindingPair.setLeft {
            tvCaptureHint.visibility = View.VISIBLE
        }
    }

    override fun onStop() {
        super.onStop()
        closeCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        phoneDiscovery.stop()
        backHandlerThread.quitSafely()
        httpClient.dispatcher.executorService.shutdown()
    }

    // ════════════════════════════════════════
    //  NSD 服务发现
    // ════════════════════════════════════════

    private fun startServiceDiscovery() {
        // 初始显示搜索提示
        mBindingPair.updateView {
            tvCaptureHint.text = "正在搜索手机服务..."
        }

        phoneDiscovery.start(object : PhoneServiceDiscovery.Listener {
            override fun onPhoneFound(host: String, port: Int) {
                phoneHost = host
                phonePort = port
                Log.i(TAG, "手机服务已发现: $host:$port")
                runOnUiThread {
                    mBindingPair.updateView {
                        tvCaptureHint.text = "单击拍照 / 双击退出"
                    }
                    FToast.show("已连接手机服务")
                }
            }

            override fun onPhoneLost() {
                phoneHost = null
                Log.w(TAG, "手机服务已消失，清除缓存地址")
                runOnUiThread {
                    mBindingPair.updateView {
                        tvCaptureHint.text = "正在搜索手机服务..."
                    }
                    FToast.show("手机服务已断开，重新搜索中...")
                }
            }
        })
    }

    // ════════════════════════════════════════
    //  Step 2：手势监听（TempleAction）
    // ════════════════════════════════════════

    private fun setupTempleActions() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                templeActionViewModel.state.collect { action ->
                    when (action) {
                        is TempleAction.Click -> {
                            Log.d(TAG, "takePhoto 触发")
                            if (!isProcessing && !takePhoto.get()) {
                                takePhoto.set(true)
                            }
                        }
                        is TempleAction.DoubleClick -> finish()
                        else -> Unit
                    }
                }
            }
        }
    }

    // ════════════════════════════════════════
    //  Step 1：相机初始化（Camera2）
    // ════════════════════════════════════════

    private fun setupCameraPreview() {
        // updateView 对左右两眼 binding 各执行一次，产生两个 Surface
        mBindingPair.updateView {
            cameraPreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                var mSurface: Surface? = null

                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    // 固定缓冲分辨率，不依赖 View 的测量值
                    st.setDefaultBufferSize(1920, 1080)
                    val s = Surface(cameraPreview.surfaceTexture)
                    surfaceList.add(s)
                    mSurface = s
                    Log.d(TAG, "Surface ready, count=${surfaceList.size}")

                    // 两个 Surface 都就绪后才初始化相机
                    if (surfaceList.size == 2) {
                        lifecycleScope.launch {
                            delay(100L)
                            setupCamera2()
                        }
                    }
                }

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    mSurface?.release()
                    return true
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupCamera2() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
            return
        }
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.first()
        Log.d(TAG, "打开相机 id=$cameraId")
        cameraManager.openCamera(cameraId, stateCallback, null)
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        @RequiresApi(Build.VERSION_CODES.P)
        override fun onOpened(camera: CameraDevice) {
            Log.d(TAG, "相机打开成功")
            lifecycleScope.launch {
                cameraDevice = camera
                delay(100L)
                if (cameraDevice == camera) {
                    setUpImageReader(camera)
                }
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.w(TAG, "相机断开连接")
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "相机错误 error=$error")
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun setUpImageReader(camera: CameraDevice) {
        imageReader?.close()
        // YUV_420_888，buffer=10，1920×1080
        imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.YUV_420_888, 10)
        openTime = -1L

        imageReader!!.setOnImageAvailableListener({ reader ->
            // 过滤开机后前 1 秒的不稳定帧
            if (openTime == -1L) {
                openTime = System.currentTimeMillis()
                return@setOnImageAvailableListener
            }
            if (System.currentTimeMillis() - openTime < 1000L) return@setOnImageAvailableListener

            // 取最新帧，丢弃积压旧帧
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

            if (takePhoto.get()) {
                takePhoto.set(false)                   // 立即复位，防止重复触发
                val bitmap = imageToBitmap(image)
                image.close()                          // ★ 必须 close，否则 buffer 耗尽
                bitmap?.let {
                    lifecycleScope.launch(Dispatchers.Main) {
                        onPhotoCaptured(it)
                    }
                }
            } else {
                image.close()                          // 即使不拍照也要 close
            }
        }, backHandler)                                // ★ 在后台线程处理

        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(imageReader!!.surface)
            surfaceList.forEach { addTarget(it) }
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(5, 10))
        }

        // CaptureSession 输出：ImageReader + 左右两个预览 Surface
        val outputs = listOf(
            OutputConfiguration(imageReader!!.surface),
            OutputConfiguration(surfaceList[0]),
            OutputConfiguration(surfaceList[1])
        )
        camera.createCaptureSession(
            SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                Executors.newSingleThreadExecutor(),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        session.setRepeatingRequest(builder.build(), null, null)
                        cameraCaptureSession = session
                        Log.d(TAG, "CaptureSession 配置成功")
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "CaptureSession 配置失败")
                    }
                }
            )
        )
    }

    private fun closeCamera() {
        try {
            cameraCaptureSession?.close(); cameraCaptureSession = null
            cameraDevice?.close();         cameraDevice = null
            imageReader?.close();          imageReader = null
            surfaceList.clear()
        } catch (e: Exception) {
            Log.w(TAG, "关闭相机异常: ${e.message}")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                setupCamera2()
            } else {
                Log.e(TAG, "相机权限被拒绝，无法启动")
                FToast.show("需要相机权限才能运行")
                finish()
            }
        }
    }

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 100

        /** 轮询识别结果的间隔；与手机端 LLM 实际耗时匹配，1s 反馈足够流畅 */
        private const val POLL_INTERVAL_MS = 1000L
        /** 单次识别最长等待时间，超过则放弃 */
        private const val POLL_TIMEOUT_MS = 90_000L
        /** 连续多少次轮询网络失败才放弃，防止短暂抖动误判 */
        private const val MAX_POLL_FAILURES = 3
    }

    // ════════════════════════════════════════
    //  Step 3：YUV_420_888 → Bitmap
    // ════════════════════════════════════════

    /**
     * 将 ImageReader 输出的 YUV_420_888 Image 转为 Bitmap。
     * 路径：YUV_420_888 → NV21 字节数组 → YuvImage.compressToJpeg → Bitmap
     * ★ 调用方负责在本函数返回后调用 image.close()
     */
    private fun imageToBitmap(image: Image): Bitmap? {
        val planes  = image.planes
        val yBuffer = planes[0].buffer   // Y 分量
        val vBuffer = planes[2].buffer   // V 分量（NV21: YVU，V 在 U 之前）
        val uBuffer = planes[1].buffer   // U 分量

        // 先保存 remaining()，get() 后 position 会移动
        val ySize = yBuffer.remaining()
        val vSize = vBuffer.remaining()
        val uSize = uBuffer.remaining()

        // 组装 NV21：Y 平面 + 交错的 VU 数据
        val nv21 = ByteArray(ySize + vSize + uSize)
        yBuffer.get(nv21, 0,             ySize)
        vBuffer.get(nv21, ySize,         vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)
        val jpegBytes = out.toByteArray()
        out.close()

        Log.d(TAG, "图片字节大小（YuvImage→JPEG）: ${jpegBytes.size}")
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }

    /** Bitmap → JPEG 字节数组，用于 HTTP 传输 */
    private fun Bitmap.toJpegBytes(quality: Int = 85): ByteArray {
        val out = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    // ════════════════════════════════════════
    //  Step 4：HTTP POST → 手机
    // ════════════════════════════════════════

    /**
     * 异步识别协议：
     *  1. submitAnalyze(): POST /analyzeDrug 上传图片，立刻拿到 taskId
     *  2. pollAnalyzeResult(taskId): 每 1s GET /analyzeResult?taskId=xxx，直到 done/error 或超时
     *
     * 轮询过程中通过 onProgress 回调向 UI 反馈"识别中…Ns"。
     */
    private suspend fun sendToPhone(
        jpegBytes: ByteArray,
        onProgress: (elapsedSec: Long) -> Unit = {}
    ): DrugResponse = withContext(Dispatchers.IO) {
        val host = phoneHost ?: return@withContext DrugResponse.Failure("尚未发现手机服务，请稍候")
        val port = phonePort

        val submitResult = submitAnalyze(host, port, jpegBytes)
        val taskId = when (submitResult) {
            is SubmitResult.Failure -> return@withContext submitResult.response
            is SubmitResult.Success -> submitResult.taskId
        }
        Log.i(TAG, "任务已提交: taskId=$taskId")

        pollAnalyzeResult(host, port, taskId, onProgress)
    }

    private sealed class SubmitResult {
        data class Success(val taskId: String) : SubmitResult()
        data class Failure(val response: DrugResponse) : SubmitResult()
    }

    private fun submitAnalyze(host: String, port: Int, jpegBytes: ByteArray): SubmitResult {
        val url = "http://$host:$port/analyzeDrug"
        return try {
            Log.d(TAG, "提交识别请求: url=$url, 图片字节=${jpegBytes.size}")
            val body = jpegBytes.toRequestBody("image/jpeg".toMediaType())
            val request = Request.Builder().url(url).post(body).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return SubmitResult.Failure(
                    DrugResponse.Failure("服务端错误 (${response.code})，请检查手机服务")
                )
            }
            val json = JSONObject(response.body!!.string())
            if (!json.getBoolean("success")) {
                return SubmitResult.Failure(
                    DrugResponse.Failure("提交失败：${json.optString("error", "未知原因")}")
                )
            }
            SubmitResult.Success(json.getString("taskId"))
        } catch (e: java.net.ConnectException) {
            Log.e(TAG, "提交连接失败: ${e.message}")
            SubmitResult.Failure(
                DrugResponse.NetworkFailure("网络连接失败，请确认手机热点已开启且服务正在运行")
            )
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "提交超时: ${e.message}")
            SubmitResult.Failure(DrugResponse.NetworkFailure("提交超时，手机服务可能未启动"))
        } catch (e: Exception) {
            Log.e(TAG, "提交异常: ${e.javaClass.simpleName}: ${e.message}")
            SubmitResult.Failure(DrugResponse.Failure("提交失败：${e.message}"))
        }
    }

    private suspend fun pollAnalyzeResult(
        host: String,
        port: Int,
        taskId: String,
        onProgress: (elapsedSec: Long) -> Unit
    ): DrugResponse {
        val startMs = System.currentTimeMillis()
        var consecutiveFailures = 0
        while (true) {
            val elapsedMs = System.currentTimeMillis() - startMs
            if (elapsedMs > POLL_TIMEOUT_MS) {
                return DrugResponse.NetworkFailure("识别超时（${POLL_TIMEOUT_MS / 1000}s），请重试")
            }
            onProgress(elapsedMs / 1000)

            val outcome = pollOnce(host, port, taskId)
            when (outcome) {
                is PollOutcome.Running -> {
                    consecutiveFailures = 0
                    delay(POLL_INTERVAL_MS)
                }
                is PollOutcome.Done -> return DrugResponse.Success(outcome.result)
                is PollOutcome.Error -> return DrugResponse.Failure("识别失败：${outcome.error}")
                is PollOutcome.NetworkFailure -> {
                    consecutiveFailures++
                    Log.w(TAG, "轮询网络异常 ($consecutiveFailures/$MAX_POLL_FAILURES): ${outcome.reason}")
                    if (consecutiveFailures >= MAX_POLL_FAILURES) {
                        return DrugResponse.NetworkFailure("网络不稳定，请检查连接后重试")
                    }
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        return DrugResponse.Failure("轮询逻辑异常")
    }

    private sealed class PollOutcome {
        object Running : PollOutcome()
        data class Done(val result: DrugResult) : PollOutcome()
        data class Error(val error: String) : PollOutcome()
        data class NetworkFailure(val reason: String) : PollOutcome()
    }

    private fun pollOnce(host: String, port: Int, taskId: String): PollOutcome {
        val url = "http://$host:$port/analyzeResult?taskId=$taskId"
        return try {
            val request = Request.Builder().url(url).get().build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return PollOutcome.NetworkFailure("HTTP ${response.code}")
            }
            val json = JSONObject(response.body!!.string())
            if (!json.optBoolean("success", false)) {
                return PollOutcome.Error(json.optString("error", "未知错误"))
            }
            when (json.optString("status")) {
                "running" -> PollOutcome.Running
                "done" -> PollOutcome.Done(
                    DrugResult(
                        drugName = json.getString("drugName"),
                        usage = json.getString("usage"),
                        dosage = json.getString("dosage"),
                        contraindications = json.getString("contraindications")
                    )
                )
                "error" -> PollOutcome.Error(json.optString("error", "识别失败"))
                else -> PollOutcome.NetworkFailure("未知 status")
            }
        } catch (e: java.net.ConnectException) {
            PollOutcome.NetworkFailure("connect: ${e.message}")
        } catch (e: java.net.SocketTimeoutException) {
            PollOutcome.NetworkFailure("timeout: ${e.message}")
        } catch (e: org.json.JSONException) {
            PollOutcome.NetworkFailure("json: ${e.message}")
        } catch (e: Exception) {
            PollOutcome.NetworkFailure("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 响应数据类，对应手机端返回的 JSON 字段 */
    data class DrugResult(
        val drugName: String,
        val usage: String,
        val dosage: String,
        val contraindications: String
    )

    /** 网络请求结果：成功或各类失败 */
    sealed class DrugResponse {
        data class Success(val result: DrugResult) : DrugResponse()
        /** 业务层失败（服务端返回错误、JSON 解析失败等），不触发重连 */
        data class Failure(val toast: String) : DrugResponse()
        /** 网络层失败（连接拒绝、超时），触发清除缓存地址并重新发现 */
        data class NetworkFailure(val toast: String) : DrugResponse()
    }

    // ════════════════════════════════════════
    //  Step 5：解析响应并双目显示
    // ════════════════════════════════════════

    /**
     * 拍照成功后的主流程（在主线程调用）。
     * 通过 mBindingPair.updateView 保证左右两眼同时更新 UI。
     */
    private fun onPhotoCaptured(bitmap: Bitmap) {
        if (isProcessing) return
        isProcessing = true

        // 显示"识别中"
        mBindingPair.updateView {
            tvStatus.text       = "识别中..."
            tvStatus.visibility = View.VISIBLE
            resultCard.visibility = View.GONE
        }
        FToast.show("正在识别，请稍候")

        lifecycleScope.launch {
            // Bitmap → JPEG（IO 线程）
            val jpegBytes = withContext(Dispatchers.IO) { bitmap.toJpegBytes(85) }

            // 异步识别：submit → poll，期间持续更新 UI
            val response = sendToPhone(jpegBytes) { elapsedSec ->
                runOnUiThread {
                    mBindingPair.updateView {
                        tvStatus.text = "识别中…${elapsedSec}s"
                    }
                }
            }
            isProcessing = false

            when (response) {
                is DrugResponse.Failure -> {
                    FToast.show(response.toast)
                    mBindingPair.updateView {
                        tvStatus.visibility = View.GONE
                    }
                }
                is DrugResponse.NetworkFailure -> {
                    // 网络层失败：清除缓存地址，重新发现
                    phoneHost = null
                    Log.w(TAG, "网络失败，清除缓存地址，重新发现服务")
                    FToast.show(response.toast)
                    mBindingPair.updateView {
                        tvStatus.visibility = View.GONE
                        tvCaptureHint.text = "正在搜索手机服务..."
                    }
                    phoneDiscovery.stop()
                    startServiceDiscovery()
                }
                is DrugResponse.Success -> {
                    val result = response.result
                    // ★ 双目同时显示结果
                    mBindingPair.updateView {
                        tvStatus.visibility        = View.GONE
                        resultCard.visibility      = View.VISIBLE
                        tvDrugName.text            = result.drugName
                        tvUsage.text               = "用法：${result.usage}"
                        tvDosage.text              = "用量：${result.dosage}"
                        tvContraindications.text   = "禁忌：${result.contraindications}"
                    }
                }
            }
        }
    }
}
