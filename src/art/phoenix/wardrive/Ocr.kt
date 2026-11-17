package art.phoenix.wardrive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.media.ExifInterface
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream

data class OcrResult(
    val plate: String?,
    val rawText: String,
    val confidence: Int,
    val method: String
)

/**
 * v1.6 — plate OCR is back, on-device, offline, no cloud.
 *
 * Engine: Tesseract via tess-two 9.1.0 (com.rmtheis), eng.traineddata
 * (fast) bundled in assets/tessdata/, native libs libtess/liblept/
 * libjpgt/libpngt for arm64-v8a + armeabi-v7a. Nothing leaves the
 * phone: no network, no cloud OCR, no photo upload.
 *
 * Why Tesseract and not MLKit: Gradle is still blocked in this VM
 * (Java TCP to localhost is intercepted — the SockTest reads 109 'm',
 * the first byte of the runtime denial, instead of 42), so the
 * MLKit Gradle build cannot run here. Tesseract builds with the
 * proven manual kotlinc + aapt2 + d8 pipeline, and it is the
 * fallback the task specified.
 *
 * Pipeline per image: try (1) full frame, PSM single-line,
 * (2) centre-band crop, PSM single-line, (3) Otsu-thresholded
 * binary, PSM single-word, (4) full frame, PSM auto. Each pass
 * is scored by OcrLogic (Hawaii plates 100, generic 60) times
 * Tesseract's mean confidence; the best plate wins. Minimum
 * confidence 55 or the result is "no plate" — a wrong plate is
 * worse than no plate.
 */
class PlateOcr(private val ctx: Context) {
    @Volatile var ready: Boolean = false
        private set
    @Volatile var lastError: String? = null
        private set

    private var api: TessBaseAPI? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Copy traineddata + init Tesseract. Call off the UI thread. */
    fun init(): Boolean {
        return try {
            val tessDir = File(ctx.filesDir, "tessdata").apply { mkdirs() }
            val target = File(tessDir, "eng.traineddata")
            val assetSize = ctx.assets.open("tessdata/eng.traineddata").use { it.available().toLong() }
            if (!target.exists() || target.length() != assetSize) {
                ctx.assets.open("tessdata/eng.traineddata").use { inp ->
                    FileOutputStream(target).use { out -> inp.copyTo(out) }
                }
            }
            val tess = TessBaseAPI()
            if (!tess.init(ctx.filesDir.absolutePath, "eng")) {
                lastError = "Tesseract init failed"; return false
            }
            tess.setVariable(TessBaseAPI.VAR_CHAR_WHITELIST, "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")
            tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_LINE)
            api = tess
            ready = true
            true
        } catch (e: Exception) {
            lastError = "OCR init failed: ${e.message}"
            false
        }
    }

    fun close() {
        try { api?.end() } catch (e: Exception) { }
        api = null; ready = false
    }

    private fun runPass(bmp: Bitmap, psm: Int): Pair<String, Int> {
        val tess = api ?: return Pair("", 0)
        return try {
            tess.setPageSegMode(psm)
            tess.setImage(bmp)
            val text = tess.getUTF8Text() ?: ""
            val conf = try { tess.meanConfidence() } catch (e: Exception) { 0 }
            tess.clear()
            Pair(text, conf)
        } catch (e: Exception) {
            Pair("", 0)
        }
    }

    /** Grayscale + Otsu threshold to a black-on-white bitmap. */
    fun binarize(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = IntArray(pixels.size)
        val hist = IntArray(256)
        for (i in pixels.indices) {
            val p = pixels[i]
            val g = (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)).toInt()
            gray[i] = g; hist[g]++
        }
        val t = OcrLogic.otsuThreshold(hist)
        val out = OcrLogic.thresholdPixels(gray, t)
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (i in out.indices) {
            val v = out[i]
            pixels[i] = Color.rgb(v, v, v)
        }
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }

    fun centreCrop(src: Bitmap): Bitmap {
        val r = OcrLogic.plateCropRect(src.width, src.height)
        return Bitmap.createBitmap(src, r[0], r[1], r[2] - r[0], r[3] - r[1])
    }

    /** Scale up small images — Tesseract reads plates better at ~1200px wide. */
    fun upscale(src: Bitmap): Bitmap {
        if (src.width >= 1200) return src
        val scale = 1200f / src.width
        return Bitmap.createScaledBitmap(src, 1200, (src.height * scale).toInt(), true)
    }

    fun recognizeBitmap(src: Bitmap): OcrResult {
        if (!ready) return OcrResult(null, "", 0, "not-ready")
        val base = upscale(src)
        var best: OcrResult? = null
        fun consider(text: String, conf: Int, method: String) {
            val plate = OcrLogic.bestPlate(text) ?: return
            if (conf < OcrLogic.MIN_CONFIDENCE) return
            val score = (OcrLogic.extractCandidates(text).firstOrNull()?.score ?: 0) * conf
            val cur = best
            if (cur == null || score > (OcrLogic.extractCandidates(cur.rawText).firstOrNull()?.score ?: 0) * cur.confidence) {
                best = OcrResult(plate, text, conf, method)
            }
        }
        try {
            val (t1, c1) = runPass(base, TessBaseAPI.PageSegMode.PSM_SINGLE_LINE)
            consider(t1, c1, "full-psm7")
            val (t2, c2) = runPass(centreCrop(base), TessBaseAPI.PageSegMode.PSM_SINGLE_LINE)
            consider(t2, c2, "crop-psm7")
            val (t3, c3) = runPass(binarize(base), TessBaseAPI.PageSegMode.PSM_SINGLE_WORD)
            consider(t3, c3, "binary-psm8")
            val (t4, c4) = runPass(base, TessBaseAPI.PageSegMode.PSM_AUTO)
            consider(t4, c4, "full-psm6")
        } catch (e: Exception) {
            lastError = "OCR failed: ${e.message}"
        }
        return best ?: OcrResult(null, "", 0, "no-plate")
    }

    fun recognizeFile(file: File): OcrResult {
        val bmp = decodeWithRotation(file) ?: return OcrResult(null, "", 0, "decode-failed")
        return try { recognizeBitmap(bmp) } finally { try { bmp.recycle() } catch (e: Exception) { } }
    }

    private fun decodeWithRotation(file: File): Bitmap? {
        val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        return try {
            val exif = ExifInterface(file.absolutePath)
            val orient = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val degrees = when (orient) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees == 0f) bmp else {
                val m = Matrix().apply { postRotate(degrees) }
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
        } catch (e: Exception) { bmp }
    }

    fun recognizeFileAsync(file: File, cb: (OcrResult) -> Unit) {
        Thread {
            val r = recognizeFile(file)
            mainHandler.post { cb(r) }
        }.start()
    }

    fun recognizeBitmapAsync(bmp: Bitmap, cb: (OcrResult) -> Unit) {
        Thread {
            val r = recognizeBitmap(bmp)
            mainHandler.post { cb(r) }
        }.start()
    }
}
