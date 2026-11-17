package art.phoenix.wardrive

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.io.File

data class VideoSettings(
    /** 0 = 1080p, 1 = 720p, 2 = 480p */
    var resTier: Int = 0,
    var fps: Int = 30,
    var torch: Boolean = false
) {
    /** Pure dims (no android.util.Size, so JVM tests can use it). */
    fun videoDims(): Pair<Int, Int> = when (resTier) {
        0 -> Pair(1920, 1080)
        1 -> Pair(1280, 720)
        else -> Pair(854, 480)
    }
    fun videoSize(): Size = videoDims().let { Size(it.first, it.second) }
    fun bitRate(): Int = when (resTier) {
        0 -> 8_000_000
        1 -> 5_000_000
        else -> 2_500_000
    }
    fun label(): String = when (resTier) {
        0 -> "1080p"
        1 -> "720p"
        else -> "480p"
    }
}

/**
 * Camera2 controller — the v1.5 VIDEO / ZOOM FIX.
 *
 * v1.4 diagnosis (static analysis): CameraActivity and VideoActivity
 * contain NO zoom code at all — no zoomRatio, no ScaleGestureDetector,
 * no pinch, no slider, no CONTROL_ZOOM_RATIO. CameraSettings only
 * exposed resolution / fps / torch / lock / sample.
 *
 * This controller adds real optical/digital zoom:
 *  - Pinch-to-zoom on the preview (ScaleGestureDetector, wired in the
 *    activity), a zoom slider (log scale), and +/- buttons.
 *  - Zoom is applied with CaptureRequest.CONTROL_ZOOM_RATIO (API 30+)
 *    or a centred SCALER_CROP_REGION (API 26-29) on the SAME
 *    repeating CaptureRequest that feeds BOTH the preview surface and
 *    the MediaRecorder surface — so zoom works in live preview AND in
 *    the recorded video, not just as a UI label.
 *  - Video settings: resolution tier (1080p/720p/480p), fps, torch,
 *    persisted in SharedPreferences by the activity.
 */
class CameraController(
    private val ctx: Context,
    private val textureView: TextureView
) {
    var settings = VideoSettings()
    var zoom: ZoomController = ZoomController(1f)
        private set
    var onZoomChanged: ((Float) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onStillSaved: ((File) -> Unit)? = null
    var onRecordingState: ((Boolean) -> Unit)? = null

    private var camThread: HandlerThread? = null
    private var camHandler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recording = false
    private var cameraId: String? = null
    private var sensorOrientation = 0
    private var sensorRect: Rect? = null
    private var previewSize: Size = Size(1920, 1080)

    fun maxZoom(): Float = zoom.maxZoom

    fun start() {
        camThread = HandlerThread("wardrive-cam").also { it.start() }
        camHandler = Handler(camThread!!.looper)
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            cameraId = mgr.cameraIdList.firstOrNull { id ->
                mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: mgr.cameraIdList.firstOrNull()
            val id = cameraId ?: run { onError?.invoke("No camera found"); return }
            val chars = mgr.getCameraCharacteristics(id)
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val maxDigital = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            zoom = ZoomController(maxDigital.coerceAtLeast(1f))
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
            previewSize = sizes.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height }
                ?: Size(1920, 1080)
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { camera = c; startPreview() }
                override fun onDisconnected(c: CameraDevice) { c.close(); camera = null }
                override fun onError(c: CameraDevice, e: Int) { onError?.invoke("Camera error $e"); c.close(); camera = null }
            }, camHandler)
        } catch (e: SecurityException) {
            onError?.invoke("Camera permission missing")
        } catch (e: Exception) {
            onError?.invoke("Camera start failed: ${e.message}")
        }
    }

    fun stop() {
        try { session?.close() } catch (e: Exception) { }
        try { camera?.close() } catch (e: Exception) { }
        try { imageReader?.close() } catch (e: Exception) { }
        stopRecordingInternal()
        camThread?.quitSafely()
        camera = null; session = null
    }

    // ---- zoom (the fix) ----

    private fun applyZoom(builder: CaptureRequest.Builder) {
        val r = zoom.ratio
        if (Build.VERSION.SDK_INT >= 30) {
            try { builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, r) } catch (e: Exception) { applyCropZoom(builder, r) }
        } else {
            applyCropZoom(builder, r)
        }
    }

    private fun applyCropZoom(builder: CaptureRequest.Builder, @Suppress("UNUSED_PARAMETER") ratio: Float) {
        val rect = sensorRect ?: return
        val c = zoom.cropRegion(rect.width(), rect.height())
        builder.set(
            CaptureRequest.SCALER_CROP_REGION,
            Rect(rect.left + c[0], rect.top + c[1], rect.left + c[2], rect.top + c[3])
        )
    }

    private fun refreshPreviewRequest() {
        val b = previewBuilder ?: return
        applyZoom(b)
        applyTorch(b)
        try { session?.setRepeatingRequest(b.build(), null, camHandler) } catch (e: Exception) { }
    }

    fun setZoomRatio(r: Float) { zoom.setRatio(r); refreshPreviewRequest(); onZoomChanged?.invoke(zoom.ratio) }
    fun pinchZoom(scaleFactor: Float) { zoom.pinch(scaleFactor); refreshPreviewRequest(); onZoomChanged?.invoke(zoom.ratio) }
    fun zoomStepUp() { zoom.stepUp(); refreshPreviewRequest(); onZoomChanged?.invoke(zoom.ratio) }
    fun zoomStepDown() { zoom.stepDown(); refreshPreviewRequest(); onZoomChanged?.invoke(zoom.ratio) }
    fun sliderZoom(t: Float) { zoom.fromSlider(t); refreshPreviewRequest(); onZoomChanged?.invoke(zoom.ratio) }

    fun setTorch(on: Boolean) {
        settings.torch = on
        previewBuilder?.let { applyTorch(it); refreshPreviewRequest() }
    }

    private fun applyTorch(b: CaptureRequest.Builder) {
        b.set(CaptureRequest.FLASH_MODE, if (settings.torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
    }

    // ---- preview / stills ----

    private fun startPreview() {
        val cam = camera ?: return
        val st = textureView.surfaceTexture ?: return
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        val previewSurface = Surface(st)
        imageReader = ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.JPEG, 2)
        try {
            val builder = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(previewSurface)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            applyZoom(builder)
            applyTorch(builder)
            previewBuilder = builder
            cam.createCaptureSession(listOf(previewSurface, imageReader!!.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        try { s.setRepeatingRequest(builder.build(), null, camHandler) } catch (e: Exception) { onError?.invoke("Preview failed: ${e.message}") }
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) { onError?.invoke("Camera session failed to configure") }
                }, camHandler)
        } catch (e: Exception) { onError?.invoke("Preview failed: ${e.message}") }
    }

    fun takeStill(outFile: File) {
        val cam = camera ?: return
        val reader = imageReader ?: return
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val buf = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                outFile.writeBytes(bytes)
                onStillSaved?.invoke(outFile)
            } catch (e: Exception) { onError?.invoke("Save failed: ${e.message}") }
            finally { img.close() }
        }, camHandler)
        try {
            val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(reader.surface)
            applyZoom(b)
            b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())
            session?.capture(b.build(), null, camHandler)
        } catch (e: Exception) { onError?.invoke("Capture failed: ${e.message}") }
    }

    private fun jpegOrientation(): Int {
        // Sensor is landscape; display is portrait in this app.
        return (sensorOrientation + 90) % 360
    }

    fun tapToFocus(x: Float, y: Float) {
        val b = previewBuilder ?: return
        val rect = sensorRect ?: return
        val fx = (x / textureView.width.toFloat()).coerceIn(0f, 1f)
        val fy = (y / textureView.height.toFloat()).coerceIn(0f, 1f)
        val w = rect.width() / 5; val h = rect.height() / 5
        val cx = rect.left + (fx * rect.width()).toInt()
        val cy = rect.top + (fy * rect.height()).toInt()
        val area = Rect((cx - w / 2).coerceAtLeast(rect.left), (cy - h / 2).coerceAtLeast(rect.top),
            (cx + w / 2).coerceAtMost(rect.right), (cy + h / 2).coerceAtMost(rect.bottom))
        try {
            b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(MeteringRectangle(area, 1000)))
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            session?.setRepeatingRequest(b.build(), null, camHandler)
        } catch (e: Exception) { /* focus is best-effort */ }
    }

    // ---- video recording (zoom applies here too — same builder) ----

    fun isRecording(): Boolean = recording

    fun startRecording(outFile: File): Boolean {
        val cam = camera ?: return false
        if (recording) return false
        try {
            val rec = MediaRecorder()
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setVideoSize(settings.videoSize().width, settings.videoSize().height)
            rec.setVideoFrameRate(settings.fps)
            rec.setVideoEncodingBitRate(settings.bitRate())
            rec.setOutputFile(outFile.absolutePath)
            rec.prepare()
            recordingFile = outFile
            recorder = rec
            val st = textureView.surfaceTexture ?: return false
            st.setDefaultBufferSize(previewSize.width, previewSize.height)
            val previewSurface = Surface(st)
            val builder = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            builder.addTarget(previewSurface)
            builder.addTarget(rec.surface)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            applyZoom(builder) // <-- zoom in the recorded video, same value as preview
            applyTorch(builder)
            previewBuilder = builder
            cam.createCaptureSession(listOf(previewSurface, rec.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        try {
                            s.setRepeatingRequest(builder.build(), null, camHandler)
                            rec.start(); recording = true; onRecordingState?.invoke(true)
                        } catch (e: Exception) { onError?.invoke("Record start failed: ${e.message}") }
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) { onError?.invoke("Record session failed") }
                }, camHandler)
            return true
        } catch (e: Exception) {
            onError?.invoke("Recorder failed: ${e.message}")
            return false
        }
    }

    fun stopRecording(): File? {
        val f = recordingFile
        stopRecordingInternal()
        // return to preview-only session
        startPreview()
        return f
    }

    private fun stopRecordingInternal() {
        if (!recording) { try { recorder?.release() } catch (e: Exception) { }; recorder = null; return }
        try { recorder?.stop() } catch (e: Exception) { }
        try { recorder?.release() } catch (e: Exception) { }
        recorder = null; recording = false
        onRecordingState?.invoke(false)
    }
}
