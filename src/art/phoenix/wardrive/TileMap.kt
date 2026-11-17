package art.phoenix.wardrive

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.*

/**
 * Self-contained OSM tile map — the v1.5 MAP FIX.
 *
 * v1.4 (osmdroid) problems found by static analysis of the shipped APK:
 *  1. No MyLocation overlay anywhere in MainActivity — the map never
 *     showed where you are and never followed you while driving.
 *  2. No built-in zoom controls — pinch only.
 *  3. Bare user-agent on MAPNIK tile requests (OSM throttles/403s
 *     unidentified clients) and no tile-failure feedback: a blocked
 *     tile server left a blank grey map with markers on nothing.
 *  4. No offline fallback rendering when tiles are missing.
 *
 * This view fixes all four:
 *  - Identifying User-Agent ("WardriveCompanion/1.5 (art.phoenix.wardrive)")
 *    + per-tile error reporting to the status line.
 *  - Disk tile cache in the SAME layout v1.4 used
 *    (getExternalFilesDir/osmdroid/tiles/<z>/<x>/<y>.png) so areas
 *    downloaded in v1.4 are reused; 200 MB LRU cap.
 *  - Offline mode: cached tiles only; uncached areas draw a grid +
 *    markers, never a dead blank screen.
 *  - My-location dot + Follow mode, centre-on-me, zoom +/- buttons,
 *    pinch, double-tap zoom, tap-a-marker detail callback.
 *  - Markers for captures (red), networks (blue), KML pins (purple),
 *    marks (amber). Tap a marker -> onMarkerTap.
 */
data class MapMarker(
    val lat: Double, val lon: Double, val kind: String,
    val title: String, val subtitle: String, val id: Long = 0
)

class TileMapView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    companion object {
        const val USER_AGENT = "WardriveCompanion/1.5 (art.phoenix.wardrive)"
        const val TILE_SIZE = 256
        const val MAX_CACHE_BYTES = 200L * 1024 * 1024
        const val MIN_ZOOM = 3.0
        const val MAX_ZOOM = 19.0
    }

    var centreLat: Double = 21.3099
    var centreLon: Double = -157.858
    var zoom: Double = 15.0
        private set
    var offlineOnly: Boolean = false
    var followMe: Boolean = false
    var myLat: Double? = null
    var myLon: Double? = null
    var markers: List<MapMarker> = emptyList()
        set(v) { field = v; postInvalidate() }
    var onMarkerTap: ((MapMarker) -> Unit)? = null
    var onTileStatus: ((String) -> Unit)? = null
    var onMapMoved: (() -> Unit)? = null

    private val tileDir: File = File(ctx.getExternalFilesDir(null), "osmdroid/tiles")
    private val memCache = object : LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Bitmap>): Boolean = size > 96
    }
    private val missing = mutableSetOf<String>()
    private val fetching = mutableSetOf<String>()
    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastTileError: String? = null

    private val paintTile = Paint(Paint.FILTER_BITMAP_FLAG)
    private val paintGrid = Paint().apply { color = Color.rgb(200, 200, 200); style = Paint.Style.STROKE; strokeWidth = 1f }
    private val paintText = Paint().apply { color = Color.DKGRAY; textSize = 30f; isAntiAlias = true }
    private val paintDot = Paint().apply { color = Color.BLUE; isAntiAlias = true }
    private val paintDotRing = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f; isAntiAlias = true }
    private val paintMarker = Paint().apply { isAntiAlias = true }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            setZoomAt(zoom * det.scaleFactor.toDouble(), det.focusX.toDouble(), det.focusY.toDouble())
            return true
        }
    })
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            setZoomAt((zoom + 1).coerceAtMost(MAX_ZOOM), e.x.toDouble(), e.y.toDouble())
            return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            markers.minByOrNull { m ->
                val p = latLonToScreen(m.lat, m.lon)
                hypot(p.first - e.x, p.second - e.y)
            }?.let { m ->
                val p = latLonToScreen(m.lat, m.lon)
                if (hypot(p.first - e.x, p.second - e.y) < 60) onMarkerTap?.invoke(m)
            }
            return true
        }
    })
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    fun setZoom(z: Double) { zoom = z.coerceIn(MIN_ZOOM, MAX_ZOOM); invalidate() }

    /** Lifecycle passthroughs (tiles are fetched on demand; nothing to pause). */
    fun onResume() {}
    fun onPause() {}
    fun zoomIn() = setZoom(zoom + 1)
    fun zoomOut() = setZoom(zoom - 1)

    fun centreOn(lat: Double, lon: Double, z: Double? = null) {
        centreLat = lat; centreLon = lon
        if (z != null) zoom = z.coerceIn(MIN_ZOOM, MAX_ZOOM)
        invalidate()
    }

    fun setZoomAt(z: Double, fx: Double, fy: Double) {
        val (lat, lon) = screenToLatLon(fx, fy)
        zoom = z.coerceIn(MIN_ZOOM, MAX_ZOOM)
        // keep the focal point under the finger
        val (nx, ny) = latLonToScreen(lat, lon)
        val dx = fx - nx; val dy = fy - ny
        panBy(dx, dy)
        invalidate()
    }

    private fun panBy(dx: Double, dy: Double) {
        val scale = tileScale()
        val worldPx = 256.0 * (1 shl tileZoom()) * scale
        centreLon = ((centreLon - dx / worldPx * 360.0 + 540.0) % 360.0) - 180.0
        val latRad = Math.toRadians(centreLat)
        val y = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0
        var ny = y + dy / worldPx
        ny = ny.coerceIn(0.0, 1.0)
        centreLat = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * ny))))
        if (followMe) followMe = false
        onMapMoved?.invoke()
    }

    private fun tileZoom(): Int = floor(zoom).toInt().coerceIn(3, 19)
    private fun tileScale(): Double = 2.0.pow(zoom - tileZoom())

    /** Screen px -> lat/lon, given current centre/zoom. */
    fun screenToLatLon(x: Double, y: Double): Pair<Double, Double> {
        val z = tileZoom(); val s = tileScale()
        val world = 256.0 * (1 shl z) * s
        val cx = TileMath.lonToTileX(centreLon, z) + TileMath.latLonToTilePixel(centreLat, centreLon, z).first / 256.0
        val cy = TileMath.latToTileY(centreLat, z) + TileMath.latLonToTilePixel(centreLat, centreLon, z).second / 256.0
        val wx = cx * 256 * s + (x - width / 2.0)
        val wy = cy * 256 * s + (y - height / 2.0)
        val lon = wx / world * 360.0 - 180.0
        val lat = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * wy / world))))
        return Pair(lat, lon)
    }

    fun latLonToScreen(lat: Double, lon: Double): Pair<Float, Float> {
        val z = tileZoom(); val s = tileScale()
        val world = 256.0 * (1 shl z) * s
        val cx = (TileMath.lonToTileX(centreLat, z) + TileMath.latLonToTilePixel(centreLat, centreLon, z).first / 256.0) * 256 * s
        val cy = (TileMath.latToTileY(centreLat, z) + TileMath.latLonToTilePixel(centreLat, centreLon, z).second / 256.0) * 256 * s
        val px = (TileMath.lonToTileX(lon, z) + TileMath.latLonToTilePixel(lat, lon, z).first / 256.0) * 256 * s
        val py = (TileMath.latToTileY(lat, z) + TileMath.latLonToTilePixel(lat, lon, z).second / 256.0) * 256 * s
        return Pair((width / 2.0 + px - cx).toFloat(), (height / 2.0 + py - cy).toFloat())
    }

    fun visibleBounds(): DoubleArray {
        val (lat1, lon1) = screenToLatLon(0.0, 0.0)
        val (lat2, lon2) = screenToLatLon(width.toDouble(), height.toDouble())
        return doubleArrayOf(min(lat1, lat2), max(lat1, lat2), min(lon1, lon2), max(lon1, lon2))
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        gestureDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y; dragging = true }
            MotionEvent.ACTION_MOVE -> {
                if (dragging && e.pointerCount == 1 && !scaleDetector.isInProgress) {
                    panBy((e.x - lastX).toDouble(), (e.y - lastY).toDouble())
                    lastX = e.x; lastY = e.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        canvas.drawColor(Color.rgb(238, 238, 238))
        val z = tileZoom(); val s = tileScale()
        val tilePx = (TILE_SIZE * s).toFloat()
        val (lat0, lon0) = screenToLatLon(0.0, 0.0)
        val (lat1, lon1) = screenToLatLon(width.toDouble(), height.toDouble())
        val x0 = TileMath.lonToTileX(min(lon0, lon1), z)
        val x1 = TileMath.lonToTileX(max(lon0, lon1), z)
        val y0 = TileMath.latToTileY(max(lat0, lat1), z)
        val y1 = TileMath.latToTileY(min(lat0, lat1), z)
        var anyTile = false
        var anyMissing = false
        for (tx in x0..x1) for (ty in y0..y1) {
            val (sx, sy) = tileScreenPos(tx, ty)
            val bmp = getTile(z, tx, ty)
            if (bmp != null) {
                anyTile = true
                canvas.drawBitmap(bmp, null, RectF(sx, sy, sx + tilePx, sy + tilePx), paintTile)
            } else {
                anyMissing = true
                canvas.drawRect(sx, sy, sx + tilePx, sy + tilePx, paintGrid)
            }
        }
        if (!anyTile && anyMissing) {
            val msg = if (offlineOnly) "Offline — no cached tiles here. Use 'Download area' while online."
            else "Tiles not loaded — check connection, or use 'Download area'."
            canvas.drawText(msg, 24f, height / 2f, paintText)
        }
        // markers
        for (m in markers) {
            val (x, y) = latLonToScreen(m.lat, m.lon)
            if (x < -40 || y < -40 || x > width + 40 || y > height + 40) continue
            paintMarker.color = when (m.kind) {
                "capture" -> Color.RED
                "network" -> Color.BLUE
                "kml" -> Color.rgb(128, 0, 128)
                "mark" -> Color.rgb(255, 165, 0)
                else -> Color.GRAY
            }
            canvas.drawCircle(x, y, 16f, paintMarker)
            canvas.drawCircle(x, y, 16f, paintDotRing)
            paintText.color = Color.BLACK
            canvas.drawText(m.title, x + 22f, y + 10f, paintText)
            paintText.color = Color.DKGRAY
        }
        // my location
        val mlLat = myLat; val mlLon = myLon
        if (mlLat != null && mlLon != null) {
            val (x, y) = latLonToScreen(mlLat, mlLon)
            canvas.drawCircle(x, y, 18f, paintDotRing)
            canvas.drawCircle(x, y, 12f, paintDot)
        }
        // zoom label
        paintText.color = Color.DKGRAY
        canvas.drawText("z%.1f  %s".format(zoom, if (offlineOnly) "OFFLINE" else "online"), 24f, height - 24f, paintText)
    }

    private fun tileScreenPos(tx: Int, ty: Int): Pair<Float, Float> {
        val z = tileZoom(); val s = tileScale()
        val lon = TileMath.tileXToLon(tx, z)
        val lat = TileMath.tileYToLat(ty, z)
        val (x, y) = latLonToScreen(lat, lon)
        return Pair(x, y)
    }

    private fun tileFile(z: Int, x: Int, y: Int): File =
        File(tileDir, "$z/$x/$y.png")

    private fun getTile(z: Int, x: Int, y: Int): Bitmap? {
        val key = "$z/$x/$y"
        synchronized(memCache) { memCache[key]?.let { return it } }
        val f = tileFile(z, x, y)
        if (f.exists()) {
            val bmp = BitmapFactory.decodeFile(f.absolutePath)
            if (bmp != null) { synchronized(memCache) { memCache[key] = bmp }; return bmp }
        }
        if (missing.contains(key)) return null
        if (offlineOnly) return null
        if (fetching.add(key)) {
            executor.execute {
                try {
                    val conn = URL(TileMath.tileUrl(z, x, y)).openConnection() as HttpURLConnection
                    conn.setRequestProperty("User-Agent", USER_AGENT)
                    conn.connectTimeout = 8000; conn.readTimeout = 8000
                    if (conn.responseCode == 200) {
                        f.parentFile?.mkdirs()
                        conn.inputStream.use { inp ->
                            FileOutputStream(f).use { out -> inp.copyTo(out) }
                        }
                        pruneCache()
                        val bmp = BitmapFactory.decodeFile(f.absolutePath)
                        if (bmp != null) synchronized(memCache) { memCache[key] = bmp }
                        mainHandler.post { invalidate() }
                    } else {
                        val msg = "Tile server HTTP ${conn.responseCode} — tiles may be throttled. Showing cached tiles."
                        if (lastTileError != msg) { lastTileError = msg; mainHandler.post { onTileStatus?.invoke(msg) } }
                        if (conn.responseCode == 403 || conn.responseCode == 429) {
                            synchronized(missing) { missing.add(key) }
                        }
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    val msg = "Tile download failed (${e.javaClass.simpleName}) — showing cached tiles."
                    if (lastTileError != msg) { lastTileError = msg; mainHandler.post { onTileStatus?.invoke(msg) } }
                } finally {
                    fetching.remove(key)
                }
            }
        }
        return null
    }

    /** Download tiles for the visible area (z12-16) for offline use. Runs on a worker thread; progress via callback. */
    fun downloadVisibleArea(onProgress: (Int, Int) -> Unit, onDone: (Int, Int) -> Unit) {
        executor.execute {
            val b = visibleBounds()
            var ok = 0; var fail = 0
            for (z in TileMath.OFFLINE_ZOOMS) {
                val x0 = TileMath.lonToTileX(b[2], z); val x1 = TileMath.lonToTileX(b[3], z)
                val y0 = TileMath.latToTileY(b[1], z); val y1 = TileMath.latToTileY(b[0], z)
                val total = (x1 - x0 + 1) * (y1 - y0 + 1)
                if (total > 4000) continue // too big an area at this zoom; skip
                for (tx in x0..x1) for (ty in y0..y1) {
                    val f = tileFile(z, tx, ty)
                    if (f.exists()) { ok++; continue }
                    try {
                        val conn = URL(TileMath.tileUrl(z, tx, ty)).openConnection() as HttpURLConnection
                        conn.setRequestProperty("User-Agent", USER_AGENT)
                        conn.connectTimeout = 8000; conn.readTimeout = 8000
                        if (conn.responseCode == 200) {
                            f.parentFile?.mkdirs()
                            conn.inputStream.use { inp -> FileOutputStream(f).use { out -> inp.copyTo(out) } }
                            ok++
                        } else fail++
                        conn.disconnect()
                    } catch (e: Exception) { fail++ }
                    if ((ok + fail) % 25 == 0) mainHandler.post { onProgress(ok, fail) }
                }
            }
            pruneCache()
            mainHandler.post { onDone(ok, fail); invalidate() }
        }
    }

    private fun pruneCache() {
        try {
            val files = tileDir.walkTopDown().filter { it.isFile }.sortedBy { it.lastModified() }
            var total = files.sumOf { it.length() }
            for (f in files) {
                if (total <= MAX_CACHE_BYTES) break
                total -= f.length(); f.delete()
            }
        } catch (e: Exception) { /* cache prune is best-effort */ }
    }
}
