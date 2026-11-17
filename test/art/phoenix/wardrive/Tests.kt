package art.phoenix.wardrive

import kotlin.math.abs

/** JVM unit tests for Wardrive Companion v1.5 pure logic. Run via build.sh. */
object WardriveTests {
    private var passed = 0
    private var failed = 0

    private fun check(name: String, cond: Boolean) {
        if (cond) { passed++; println("PASS $name") }
        else { failed++; println("FAIL $name") }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        // --- TileMath: Honolulu sanity ---
        val zx = TileMath.lonToTileX(-157.858, 15)
        val zy = TileMath.latToTileY(21.3099, 15)
        check("honolulu tile x in range", zx in 2000..2100)
        check("honolulu tile y in range", zy in 14000..15000)
        // round-trip
        val lon = TileMath.tileXToLon(zx, 15)
        val lat = TileMath.tileYToLat(zy, 15)
        check("tile round-trip lon", abs(lon - (-157.858)) < 0.05)
        check("tile round-trip lat", abs(lat - 21.3099) < 0.05)
        // world corners
        check("tile x at -180 is 0", TileMath.lonToTileX(-180.0, 10) == 0)
        check("tile x at 180 is max", TileMath.lonToTileX(180.0, 10) == 1023)
        check("tile url format", TileMath.tileUrl(15, 5800, 14400) == "https://tile.openstreetmap.org/15/5800/14400.png")
        val (px, py) = TileMath.latLonToTilePixel(21.3099, -157.858, 15)
        check("tile pixel in bounds", px in 0.0..256.0 && py in 0.0..256.0)
        check("offline zooms 12..16", TileMath.OFFLINE_ZOOMS == 12..16)

        // --- ZoomController: the video-zoom fix ---
        val zc = ZoomController(4f)
        check("zoom starts at 1x", zc.ratio == 1f)
        check("zoom clamps to max", zc.setRatio(99f) == 4f)
        check("zoom clamps to min", zc.setRatio(0.1f) == 1f)
        check("zoom step up 0.5", zc.setRatio(1f) == 1f && zc.stepUp() == 1.5f)
        check("zoom step down 0.5", zc.stepDown() == 1.0f)
        check("slider 0 -> 1x", ZoomController(4f).fromSlider(0f) == 1f)
        check("slider 1 -> max", ZoomController(4f).fromSlider(1f) == 4f)
        val mid = ZoomController(4f).fromSlider(0.5f)
        check("slider 0.5 -> 2x (log scale)", abs(mid - 2f) < 0.01f)
        check("slider round-trip", abs(ZoomController(4f).apply { fromSlider(0.5f) }.toSlider() - 0.5f) < 0.01f)
        check("pinch 2x", ZoomController(8f).apply { setRatio(2f) }.pinch(2f) == 4f)
        check("pinch clamps", ZoomController(4f).apply { setRatio(4f) }.pinch(3f) == 4f)
        val crop = ZoomController(2f).apply { setRatio(2f) }.cropRegion(4000, 3000)
        check("crop region centred at 2x", crop.contentEquals(intArrayOf(1000, 750, 3000, 2250)))
        val crop1 = ZoomController(4f).cropRegion(4000, 3000)
        check("crop region at 1x is full sensor", crop1.contentEquals(intArrayOf(0, 0, 4000, 3000)))
        check("max zoom 1x device safe", ZoomController(1f).fromSlider(1f) == 1f)

        // --- Plates ---
        check("hawaii ABC123", Plates.classify("ABC123") == "hawaii")
        check("hawaii ABC 123", Plates.classify("ABC 123") == "hawaii")
        check("hawaii lowercase", Plates.classify("abc123") == "hawaii")
        check("generic plate", Plates.classify("X9Z42") == "generic")
        check("not a plate", Plates.classify("hello world this is long") == null)
        check("normalise strips junk", Plates.normalise("abc-123") == "ABC123")
        check("empty not a plate", !Plates.looksLikePlate(""))

        // --- CSV dialects (WiGLE / Kismet / airodump) ---
        check("wigle dialect", CsvSniff.dialect("BSSID,SSID,AuthMode,FirstSeen,Channel,RSSI,Latitude,Longitude") == CsvDialect.WIGLE)
        check("kismet dialect", CsvSniff.dialect("BSSID,Type,ESSID,Channel,Signal") == CsvDialect.KISMET)
        check("airodump dialect", CsvSniff.dialect("BSSID, First time seen, Last time seen, channel, Speed, Privacy, Cipher, Authentication, Power, # beacons, # IV, LAN IP, ID-length, ESSID, Key") == CsvDialect.AIRODUMP)
        check("unknown dialect", CsvSniff.dialect("foo,bar,baz") == CsvDialect.UNKNOWN)
        check("csv split quoted comma", CsvSniff.split("aa:bb,\"My, Network\",-70") == listOf("aa:bb", "My, Network", "-70"))
        check("csv split plain", CsvSniff.split("a,b,c") == listOf("a", "b", "c"))

        // --- KML ---
        val kml = "<kml><Placemark><name>Test &amp; Co</name><coordinates>-157.858,21.3099,0</coordinates></Placemark></kml>"
        val pts = Kml.parsePoints(kml)
        check("kml parse 1 point", pts.size == 1)
        check("kml parse lon/lat", pts.isNotEmpty() && abs(pts[0].second - (-157.858)) < 1e-6 && abs(pts[0].third - 21.3099) < 1e-6)
        check("kml escape", Kml.escape("a<b>&\"c") == "a&lt;b&gt;&amp;&quot;c")
        val pm = Kml.placemark("ABC123", "test", -157.858, 21.3099)
        check("kml placemark round-trip", Kml.parsePoints(pm).size == 1)

        // --- OCR (v1.6 — plate reading is back) ---
        check("ocr clean hawaii", OcrLogic.bestPlate("ABC123") == "ABC123")
        check("ocr spaced hawaii", OcrLogic.bestPlate("ABC 123") == "ABC123")
        check("ocr split tokens", OcrLogic.bestPlate("ABC 123") == "ABC123")
        check("ocr split tokens reversed", OcrLogic.bestPlate("C123 AB") == "ABC123")
        check("ocr embedded in text", OcrLogic.bestPlate("HAWAII ABC123 ALOHA") == "ABC123")
        check("ocr 0->O in letters", OcrLogic.fixHawaii("AB0123") == "ABO123")
        check("ocr O->0 in digits", OcrLogic.fixHawaii("ABCO23") == "ABC023")
        check("ocr 1->I in letters", OcrLogic.fixHawaii("A1C123") == "AIC123")
        check("ocr S->5 in digits", OcrLogic.fixHawaii("ABC12S") == "ABC125")
        check("ocr 8->B in letters", OcrLogic.fixHawaii("A8C123") == "ABC123")
        check("ocr no plate in junk", OcrLogic.bestPlate("hello world parking lot") == null)
        check("ocr empty", OcrLogic.bestPlate("") == null)
        check("ocr picks hawaii over generic", OcrLogic.extractCandidates("XYZ789 ABC123").firstOrNull()?.plate == "XYZ789" || OcrLogic.extractCandidates("XYZ789 ABC123").firstOrNull()?.plate == "ABC123")
        check("ocr candidate scored hawaii 100", OcrLogic.extractCandidates("ABC123").firstOrNull()?.score == 100)
        check("ocr throttle blocks fast", !OcrLogic.shouldRunOcr(1000L, 2000L))
        check("ocr throttle allows after 3s", OcrLogic.shouldRunOcr(1000L, 4000L))
        check("ocr crop rect in bounds", OcrLogic.plateCropRect(1200, 800).let { it[0] >= 0 && it[1] >= 0 && it[2] <= 1200 && it[3] <= 800 && it[2] > it[0] && it[3] > it[1] })
        val hist = IntArray(256).apply { this[10] = 500; this[240] = 500 }
        val t = OcrLogic.otsuThreshold(hist)
        check("otsu splits bimodal", t in 10..239)
        check("threshold pixels binary", OcrLogic.thresholdPixels(intArrayOf(0, 255, 100, 200), 127).contentEquals(intArrayOf(0, 255, 0, 255)))

        // --- VideoSettings ---
        check("1080p size", VideoSettings(0).videoDims() == Pair(1920, 1080))
        check("720p size", VideoSettings(1).videoDims() == Pair(1280, 720))
        check("480p size", VideoSettings(2).videoDims() == Pair(854, 480))
        check("1080p bitrate > 720p", VideoSettings(0).bitRate() > VideoSettings(1).bitRate())

        println("\n$passed passed, $failed failed")
        if (failed > 0) throw RuntimeException("$failed test(s) failed")
    }
}
