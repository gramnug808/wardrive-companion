package art.phoenix.wardrive

import kotlin.math.*

/**
 * Pure logic for Wardrive Companion v1.5 — no Android classes here,
 * so it can be unit-tested on the JVM.
 *
 * MAP FIX (v1.4 diagnosis, static analysis of the shipped 1.4 APK):
 *  - v1.4 used osmdroid MAPNIK with a bare user-agent and NO
 *    MyLocation overlay at all (0 references in MainActivity) — the map
 *    never showed where you are and never followed you while driving.
 *  - v1.4 had no built-in map zoom controls (only pinch) and no
 *    tile-failure feedback: when OSM throttled/403'd the tile requests
 *    the map went blank grey with markers floating on nothing.
 *  - v1.5 replaces osmdroid with a self-contained OSM tile view
 *    (TileMap.kt): identifying User-Agent, disk tile cache in the same
 *    osmdroid/tiles layout so v1.4 downloads are reused, offline mode,
 *    my-location dot + Follow, zoom buttons, and a tile-error status.
 *
 * VIDEO/ZOOM FIX (v1.4 diagnosis):
 *  - v1.4 CameraActivity/VideoActivity contain NO zoom code at all:
 *    no zoomRatio, no ScaleGestureDetector, no pinch, no slider.
 *    CameraSettings only had resolution / fps / torch / lock / sample.
 *  - v1.5 adds ZoomController below + Camera2 CONTROL_ZOOM_RATIO
 *    (API 30+) with a crop-region fallback (API 26-29), applied to the
 *    SAME repeating request that feeds preview and MediaRecorder, so
 *    zoom works in live preview AND in the recorded video.
 */

// ---------- OSM tile math (Web Mercator) ----------

object TileMath {
    fun lonToTileX(lon: Double, zoom: Int): Int {
        val n = 1 shl zoom
        return floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    }

    fun latToTileY(lat: Double, zoom: Int): Int {
        val n = 1 shl zoom
        val rad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        val y = (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * n
        return floor(y).toInt().coerceIn(0, n - 1)
    }

    fun tileXToLon(x: Int, zoom: Int): Double {
        val n = 1 shl zoom
        return x.toDouble() / n * 360.0 - 180.0
    }

    fun tileYToLat(y: Int, zoom: Int): Double {
        val n = 1 shl zoom
        val z = PI * (1.0 - 2.0 * y.toDouble() / n)
        return Math.toDegrees(atan(sinh(z)))
    }

    /** Pixels of a lat/lon within its tile, for a 256px tile. */
    fun latLonToTilePixel(lat: Double, lon: Double, zoom: Int): Pair<Double, Double> {
        val n = (1 shl zoom).toDouble()
        val x = (lon + 180.0) / 360.0 * n
        val rad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        val y = (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * n
        return Pair((x - floor(x)) * 256.0, (y - floor(y)) * 256.0)
    }

    fun tileUrl(zoom: Int, x: Int, y: Int): String =
        "https://tile.openstreetmap.org/$zoom/$x/$y.png"

    /** Zoom levels we pre-download for an offline area. */
    val OFFLINE_ZOOMS = 12..16
}

// ---------- Camera zoom ----------

/**
 * Maps UI zoom (slider 0..1, +/- steps, pinch scale) to a real
 * Camera2 zoom ratio, clamped to the device's max digital zoom.
 * Log-scale slider: fine control at 1x-2x where plates matter,
 * coarse at the top end.
 */
class ZoomController(val maxZoom: Float) {
    var ratio: Float = 1f
        private set

    fun setRatio(r: Float): Float {
        ratio = r.coerceIn(1f, maxZoom.coerceAtLeast(1f))
        return ratio
    }

    /** Slider position 0..1 -> zoom ratio. */
    fun fromSlider(t: Float): Float =
        setRatio(exp(ln(maxZoom.toDouble()) * t.coerceIn(0f, 1f).toDouble()).toFloat())

    /** Current slider position for the current ratio. */
    fun toSlider(): Float =
        if (maxZoom <= 1f) 0f else (ln(ratio.toDouble()) / ln(maxZoom.toDouble())).toFloat()

    fun stepUp(): Float = setRatio(ratio + 0.5f)
    fun stepDown(): Float = setRatio(ratio - 0.5f)

    /** Pinch: multiply by the gesture's scale factor. */
    fun pinch(scaleFactor: Float): Float = setRatio(ratio * scaleFactor)

    /** Crop region for API 26-29 (no CONTROL_ZOOM_RATIO): centred rect. */
    fun cropRegion(sensorW: Int, sensorH: Int): IntArray {
        val cw = (sensorW / ratio).toInt()
        val ch = (sensorH / ratio).toInt()
        val left = (sensorW - cw) / 2
        val top = (sensorH - ch) / 2
        return intArrayOf(left, top, left + cw, top + ch)
    }
}

// ---------- Plates ----------

object Plates {
    /** Hawaii-style ABC123 / ABC 123, plus generic 2-8 char plates. */
    private val HAWAII = Regex("^[A-Z]{3}\\s?\\d{3}$")
    private val GENERIC = Regex("^[A-Z0-9]{2,8}$")

    fun normalise(raw: String): String =
        raw.uppercase().replace(Regex("[^A-Z0-9]"), "")

    /** Returns "hawaii", "generic", or null if it does not look like a plate. */
    fun classify(raw: String): String? {
        val n = normalise(raw)
        val spaced = raw.uppercase().trim()
        return when {
            HAWAII.matches(spaced) || HAWAII.matches(n) -> "hawaii"
            GENERIC.matches(n) -> "generic"
            else -> null
        }
    }

    fun looksLikePlate(raw: String): Boolean = classify(raw) != null
}

// ---------- OCR (v1.6 — plate reading is back) ----------

/**
 * Pure OCR pipeline logic — no Android classes, JVM-testable.
 *
 * Engine on the phone is Tesseract (tess-two 9.1.0, eng.traineddata,
 * on-device, offline). Tesseract misreads look-alike characters
 * (0/O, 1/I, 5/S, 8/B, 2/Z), so this layer repairs them *positionally*:
 * a Hawaii plate is 3 letters then 3 digits, so a "0" in a letter
 * slot is really "O", and an "O" in a digit slot is really "0".
 * It also scans raw OCR text for plate-shaped tokens, including
 * split tokens ("ABC 123", "ABC" + "123") and plates embedded in
 * longer strings ("HAWAII ABC123 ALOHA").
 */
data class OcrCandidate(val plate: String, val score: Int, val source: String)

object OcrLogic {
    const val MIN_CONFIDENCE = 55
    const val LIVE_INTERVAL_MS = 3000L

    private val letterFix = mapOf('0' to 'O', '1' to 'I', '5' to 'S', '8' to 'B', '2' to 'Z', '7' to 'T')
    private val digitFix = mapOf('O' to '0', 'Q' to '0', 'I' to '1', 'L' to '1', 'S' to '5', 'B' to '8', 'Z' to '2', 'G' to '6')

    /** Positionally repair a 6-char token as a Hawaii plate, or null. */
    fun fixHawaii(token: String): String? {
        val t = token.uppercase().replace(Regex("[^A-Z0-9]"), "")
        if (t.length != 6) return null
        val sb = StringBuilder()
        for (i in 0..2) {
            val c = t[i]
            sb.append(if (c.isDigit()) letterFix[c] ?: return null else c)
        }
        for (i in 3..5) {
            val c = t[i]
            sb.append(if (c.isLetter()) digitFix[c] ?: return null else c)
        }
        val out = sb.toString()
        return if (Plates.classify(out) == "hawaii") out else null
    }

    fun extractCandidates(rawText: String): List<OcrCandidate> {
        val out = mutableListOf<OcrCandidate>()
        val seen = mutableSetOf<String>()
        fun add(plate: String, score: Int, source: String) {
            if (plate !in seen && Plates.looksLikePlate(plate)) { seen.add(plate); out.add(OcrCandidate(plate, score, source)) }
        }
        val cleaned = rawText.uppercase()
        val tokens = cleaned.split(Regex("[^A-Z0-9]+")).filter { it.isNotEmpty() }
        // single tokens
        for (tok in tokens) {
            fixHawaii(tok)?.let { add(it, 100, "token:$tok") }
            // Generic plates must contain at least one digit — otherwise
            // ordinary words ("PARKING", "HAWAII") pass as plates.
            if (tok.length in 6..8 && tok.any { it.isDigit() } && Plates.classify(tok) == "generic") add(tok, 60, "token:$tok")
        }
        // adjacent token pairs ("ABC" + "123", "AB" + "C123"), both orders —
        // OCR sometimes returns split tokens in reversed reading order
        // ("C123 AB" for a rotated frame), so try the reverse join too.
        for (i in 0 until tokens.size - 1) {
            val joined = tokens[i] + tokens[i + 1]
            if (joined.length == 6) fixHawaii(joined)?.let { add(it, 95, "pair:${tokens[i]}+${tokens[i+1]}") }
            val rev = tokens[i + 1] + tokens[i]
            if (rev.length == 6) fixHawaii(rev)?.let { add(it, 90, "pair-rev:${tokens[i+1]}+${tokens[i]}") }
        }
        // sliding 6-char window over the whole cleaned string (plates embedded in text).
        // Strict here: at least 2 of the 3 digit slots must already be digits and
        // at least 2 of the 3 letter slots already letters — otherwise ordinary
        // words hallucinate plates (e.g. "KINGLO" -> "KIN610").
        val all = cleaned.replace(Regex("[^A-Z0-9]"), "")
        for (i in 0..all.length - 6) {
            val win = all.substring(i, i + 6)
            val lettersOk = win.take(3).count { it.isLetter() }
            val digitsOk = win.drop(3).count { it.isDigit() }
            if (lettersOk >= 2 && digitsOk >= 2) {
                fixHawaii(win)?.let { add(it, 80, "window") }
            }
        }
        return out.sortedByDescending { it.score }
    }

    fun bestPlate(rawText: String): String? = extractCandidates(rawText).firstOrNull()?.plate

    /** Otsu threshold for a 256-bin grayscale histogram. */
    fun otsuThreshold(hist: IntArray): Int {
        var total = 0L; var sum = 0L
        for (i in hist.indices) { total += hist[i]; sum += i.toLong() * hist[i] }
        var sumB = 0L; var wB = 0L; var maxVar = -1.0; var threshold = 127
        for (i in hist.indices) {
            wB += hist[i]; if (wB == 0L) continue
            val wF = total - wB; if (wF == 0L) break
            sumB += i.toLong() * hist[i]
            val mB = sumB.toDouble() / wB
            val mF = (sum - sumB).toDouble() / wF
            val between = wB.toDouble() * wF.toDouble() * (mB - mF) * (mB - mF)
            if (between > maxVar) { maxVar = between; threshold = i }
        }
        return threshold
    }

    fun thresholdPixels(gray: IntArray, threshold: Int): IntArray =
        IntArray(gray.size) { if (gray[it] > threshold) 255 else 0 }

    /** Centre band where a plate usually sits in a preview frame (fractions). */
    fun plateCropRect(w: Int, h: Int): IntArray {
        val left = (w * 0.05).toInt(); val top = (h * 0.25).toInt()
        val right = (w * 0.95).toInt(); val bottom = (h * 0.75).toInt()
        return intArrayOf(left, top, right, bottom)
    }

    fun shouldRunOcr(lastRunMs: Long, nowMs: Long, minIntervalMs: Long = LIVE_INTERVAL_MS): Boolean =
        nowMs - lastRunMs >= minIntervalMs
}

// ---------- CSV import dialects ----------

enum class CsvDialect { WIGLE, KISMET, AIRODUMP, UNKNOWN }

object CsvSniff {
    fun dialect(header: String): CsvDialect {
        val h = header.lowercase()
        return when {
            h.contains("bssid") && h.contains("ssid") && h.contains("rssi") && h.contains("channel") -> CsvDialect.WIGLE
            h.contains("bssid") && h.contains("beacons") && h.contains("essid") -> CsvDialect.AIRODUMP
            h.contains("bssid") && h.contains("essid") && h.contains("channel") -> CsvDialect.KISMET
            else -> CsvDialect.UNKNOWN
        }
    }

    /** Split one CSV line respecting quotes. */
    fun split(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQ = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQ && i + 1 < line.length && line[i + 1] == '"') { cur.append('"'); i++ }
                    else inQ = !inQ
                }
                c == ',' && !inQ -> { out.add(cur.toString()); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out.add(cur.toString())
        return out
    }
}

// ---------- KML ----------

object Kml {
    fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    fun placemark(name: String, desc: String, lon: Double, lat: Double): String =
        "<Placemark><name>${escape(name)}</name><description>${escape(desc)}</description>" +
            "<Point><coordinates>$lon,$lat,0</coordinates></Point></Placemark>"

    /** Very small KML point parser: returns (name, lon, lat) triples. */
    fun parsePoints(kml: String): List<Triple<String, Double, Double>> {
        val out = mutableListOf<Triple<String, Double, Double>>()
        val pm = Regex("<Placemark>(.*?)</Placemark>", RegexOption.DOT_MATCHES_ALL)
        val nameRe = Regex("<name>(.*?)</name>", RegexOption.DOT_MATCHES_ALL)
        val coordRe = Regex("<coordinates>\\s*(-?\\d+\\.?\\d*)\\s*,\\s*(-?\\d+\\.?\\d*)", RegexOption.DOT_MATCHES_ALL)
        for (m in pm.findAll(kml)) {
            val body = m.groupValues[1]
            val coord = coordRe.find(body) ?: continue
            val lon = coord.groupValues[1].toDoubleOrNull() ?: continue
            val lat = coord.groupValues[2].toDoubleOrNull() ?: continue
            val name = nameRe.find(body)?.groupValues?.get(1)?.trim().orEmpty()
            out.add(Triple(name, lon, lat))
        }
        return out
    }
}
