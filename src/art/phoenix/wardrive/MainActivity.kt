package art.phoenix.wardrive

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.*
import android.graphics.SurfaceTexture
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.*
import android.view.*
import android.widget.*
import android.app.AlertDialog
import kotlin.math.ln
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Wardrive Companion v1.5 — clean-room rebuild of the shipped v1.4
 * (art.phoenix.wardrive, versionCode 7, 41 MB) from static analysis
 * of that APK, because no source survives in the workspace.
 *
 * WHAT WAS FIXED (the user's two asks):
 *  1. MAP — v1.4's osmdroid map had no my-location overlay, no
 *     follow mode, no built-in zoom controls, a bare tile User-Agent
 *     (OSM throttles that -> blank map), and no tile-failure feedback.
 *     v1.5's TileMapView (TileMap.kt) fixes all four, reuses v1.4's
 *     downloaded tiles (same osmdroid/tiles layout), and adds offline
 *     mode, download-area, marker tap details, and Follow.
 *  2. VIDEO / ZOOM — v1.4 had NO camera zoom code at all. v1.5 adds
 *     pinch-to-zoom, a zoom slider, and +/- buttons, applied through
 *     Camera2 CONTROL_ZOOM_RATIO (API 30+) / crop region (API 26-29)
 *     to the same repeating request that feeds preview AND the
 *     MediaRecorder, so zoom works in preview AND recorded video.
 *     Video settings: resolution 1080p/720p/480p, fps, torch.
 *
 * v1.6 OCR: Tesseract (tess-two 9.1.0, eng.traineddata, on-device,
 * offline) is back — stills are OCR'd on save and the live preview
 * is OCR'd every ~3s (throttled), auto-filling the plate field.
 * Plates stay unconfirmed until the operator confirms them in the
 * Data tab, same flow as v1.4. See Ocr.kt.
 *
 * Lawful / defensive scope only: passive capture of the operator's own
 * vehicle / authorised testing. No attack, jamming, deauth, or
 * spying-on-others features.
 */
class MainActivity : Activity() {

    private lateinit var db: DbHelper
    private lateinit var prefs: SharedPreferences
    private var lastFix: Location? = null
    private var locManager: LocationManager? = null
    private var wifiManager: WifiManager? = null
    private var latestSsids: List<String> = emptyList()
    private var latestRadios: List<String> = emptyList()

    // capture tab
    private var textureView: TextureView? = null
    private var camera: CameraController? = null
    private var zoomLabel: TextView? = null
    private var zoomSlider: SeekBar? = null
    private var statusLine: TextView? = null
    private var ocrLine: TextView? = null
    private var plateField: EditText? = null
    private var recordBtn: Button? = null
    private var burstOn = false
    private var burstTimer: Handler? = null
    // v1.6 OCR
    private var plateOcr: PlateOcr? = null
    private var liveOcrOn = true
    private var ocrFilledPlate = false
    private var liveOcrRunning = false
    private var lastLiveOcrMs = 0L
    private val liveOcrHandler = Handler(Looper.getMainLooper())

    // map tab
    private var mapView: TileMapView? = null
    private var mapStatus: TextView? = null
    private var mapSearch: EditText? = null

    private val dateFmt = SimpleDateFormat("MMM d, h:mm a", Locale.US)

    private val locListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            lastFix = loc
            mapView?.let {
                it.myLat = loc.latitude; it.myLon = loc.longitude
                if (it.followMe) it.centreOn(loc.latitude, loc.longitude)
                it.invalidate()
            }
            updateStatus()
        }
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
    }

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                val results = wifiManager?.scanResults ?: emptyList()
                latestSsids = results.mapNotNull { it.SSID?.takeIf { s -> s.isNotBlank() } }.distinct()
                for (r in results) {
                    val bssid = r.BSSID ?: continue
                    db.upsertNetwork(Network(bssid.uppercase(), r.SSID ?: "", r.capabilities ?: "",
                        r.frequency, r.level, lastFix?.latitude ?: 0.0, lastFix?.longitude ?: 0.0,
                        System.currentTimeMillis(), "scan"))
                }
                updateStatus()
            }
        }
    }

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) {
            val name = result.device.name ?: result.device.address
            if (!latestRadios.contains(name)) latestRadios = latestRadios + name
        }
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        db = DbHelper(this)
        prefs = getSharedPreferences("wardrive", MODE_PRIVATE)
        locManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        buildUi()
        requestPerms()
        // v1.6 OCR: init Tesseract off the UI thread (copies eng.traineddata, ~1s)
        Thread {
            val ocr = PlateOcr(this)
            val ok = ocr.init()
            runOnUiThread {
                plateOcr = ocr
                ocrLine?.text = if (ok) "OCR: ready — Tesseract, on-device, offline" else "OCR: unavailable — ${ocr.lastError ?: "init failed"} (type the plate)"
            }
        }.start()
    }

    private fun requestPerms() {
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.CAMERA)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.BLUETOOTH_SCAN)
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    // ---------- UI ----------

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val content = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        root.addView(tabs)
        root.addView(content)
        setContentView(root)

        val panels = mutableMapOf<String, View>()
        fun tabBtn(label: String, key: String, panel: View): Button {
            val b = Button(this).apply { text = label }
            b.setOnClickListener {
                panels.values.forEach { it.visibility = View.GONE }
                panel.visibility = View.VISIBLE
                if (key == "map") refreshMap()
                if (key == "data") refreshDataPanel()
            }
            tabs.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
            panels[key] = panel
            content.addView(panel)
            panel.visibility = View.GONE
            return b
        }
        tabBtn("Capture", "capture", buildCapturePanel())
        tabBtn("Map", "map", buildMapPanel())
        tabBtn("Data", "data", buildDataPanel())
        panels["capture"]!!.visibility = View.VISIBLE
    }

    private fun pad(v: View, p: Int = 16): View { v.setPadding(p, p, p, p); return v }

    private fun buildCapturePanel(): View {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col)
        textureView = TextureView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 900)
        }
        col.addView(textureView)
        // zoom controls — the video-settings fix
        zoomLabel = TextView(this).apply { text = "Zoom 1.0x"; textSize = 18f }
        col.addView(pad(zoomLabel!!))
        val zoomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val minus = Button(this).apply { text = "−" }
        minus.setOnClickListener { camera?.zoomStepDown() }
        val plus = Button(this).apply { text = "+" }
        plus.setOnClickListener { camera?.zoomStepUp() }
        zoomSlider = SeekBar(this).apply { max = 100 }
        zoomSlider!!.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) camera?.sliderZoom(p / 100f)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        zoomRow.addView(minus)
        zoomRow.addView(zoomSlider, LinearLayout.LayoutParams(0, -2, 1f))
        zoomRow.addView(plus)
        col.addView(zoomRow)
        val zoomHint = TextView(this).apply { text = "Pinch the preview to zoom, or use the slider / + / −. Zoom applies to the preview AND the recorded video." }
        col.addView(pad(zoomHint))

        // video settings
        val setRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val resSpinner = Spinner(this)
        val resAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, listOf("1080p", "720p", "480p"))
        resAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        resSpinner.adapter = resAdapter
        resSpinner.setSelection(prefs.getInt("resTier", 0))
        resSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                camera?.settings?.resTier = pos
                prefs.edit().putInt("resTier", pos).apply()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        val fpsSpinner = Spinner(this)
        val fpsAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, listOf("30 fps", "24 fps"))
        fpsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        fpsSpinner.adapter = fpsAdapter
        fpsSpinner.setSelection(if (prefs.getInt("fps", 30) == 24) 1 else 0)
        fpsSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                camera?.settings?.fps = if (pos == 1) 24 else 30
                prefs.edit().putInt("fps", camera?.settings?.fps ?: 30).apply()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        val torchBtn = Button(this).apply { text = "Torch" }
        torchBtn.setOnClickListener {
            val c = camera ?: return@setOnClickListener
            c.setTorch(!c.settings.torch)
            torchBtn.text = if (c.settings.torch) "Torch ON" else "Torch"
        }
        setRow.addView(TextView(this).apply { text = "Video: " })
        setRow.addView(resSpinner)
        setRow.addView(fpsSpinner)
        setRow.addView(torchBtn)
        col.addView(pad(setRow))

        plateField = EditText(this).apply { hint = "Plate, e.g. ABC123 — OCR fills this in, or type it" }
        col.addView(pad(plateField!!))
        ocrLine = TextView(this).apply { text = "OCR: starting…" }
        col.addView(pad(ocrLine!!))
        val liveOcrBtn = Button(this).apply { text = "Live OCR: ON" }
        liveOcrBtn.setOnClickListener {
            liveOcrOn = !liveOcrOn
            liveOcrBtn.text = if (liveOcrOn) "Live OCR: ON" else "Live OCR: OFF"
            ocrLine?.text = if (liveOcrOn) "OCR: live scanning preview (every ~3s)" else "OCR: live off — stills are still OCR'd"
        }
        col.addView(liveOcrBtn)
        val shootBtn = Button(this).apply { text = "📸 Take still (geotagged)" }
        shootBtn.setOnClickListener { takeStill() }
        col.addView(shootBtn)
        recordBtn = Button(this).apply { text = "● Record video" }
        recordBtn!!.setOnClickListener { toggleRecord() }
        col.addView(recordBtn)
        val burstBtn = Button(this).apply { text = "Burst: OFF" }
        burstBtn.setOnClickListener {
            burstOn = !burstOn
            burstBtn.text = if (burstOn) "Burst: ON (1 shot / 5s)" else "Burst: OFF"
            if (burstOn) scheduleBurst() else burstTimer?.removeCallbacksAndMessages(null)
        }
        col.addView(burstBtn)
        val scanBtn = Button(this).apply { text = "Scan WiFi + Bluetooth now" }
        scanBtn.setOnClickListener { refreshScan() }
        col.addView(scanBtn)
        statusLine = TextView(this).apply { text = "Starting…" }
        col.addView(pad(statusLine!!))

        // pinch-to-zoom + tap-to-focus on the preview
        val scaleDet = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                camera?.pinchZoom(d.scaleFactor)
                return true
            }
        })
        val tapDet = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                camera?.tapToFocus(e.x, e.y)
                return true
            }
        })
        textureView!!.setOnTouchListener { _, e ->
            scaleDet.onTouchEvent(e); tapDet.onTouchEvent(e); true
        }
        textureView!!.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { openCamera() }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
        return scroll
    }

    private fun openCamera() {
        camera?.stop()
        camera = CameraController(this, textureView!!).apply {
            settings.resTier = prefs.getInt("resTier", 0)
            settings.fps = prefs.getInt("fps", 30)
            onZoomChanged = { r ->
                zoomLabel?.text = "Zoom %.1fx".format(r)
                val max = maxZoom()
                zoomSlider?.progress = if (max <= 1f) 0 else (ln(r.toDouble()) / ln(max.toDouble()) * 100).toInt()
            }
            onError = { msg -> runOnUiThread { toast(msg) } }
            onStillSaved = { file -> runOcrThenSave(file) }
            onRecordingState = { rec -> runOnUiThread { recordBtn?.text = if (rec) "■ Stop recording" else "● Record video" } }
            start()
        }
    }

    private fun scheduleBurst() {
        burstTimer = Handler(Looper.getMainLooper())
        burstTimer!!.postDelayed(object : Runnable {
            override fun run() {
                if (!burstOn) return
                takeStill()
                burstTimer!!.postDelayed(this, 5000)
            }
        }, 5000)
    }

    private fun takeStill() {
        val fix = lastFix
        val plate = Plates.normalise(plateField?.text?.toString() ?: "")
        if (plate.isNotEmpty() && Plates.classify(plate) == null) {
            toast("That doesn't look like a plate — check it, or leave it blank")
            return
        }
        val dir = File(getExternalFilesDir(null), "photos").apply { mkdirs() }
        val file = File(dir, "cap_${System.currentTimeMillis()}.jpg")
        camera?.takeStill(file)
        // storeCapture happens in onPhotoSaved (needs the file to exist)
        pendingPlate = plate
        pendingFix = fix
    }

    private var pendingPlate: String = ""
    private var pendingFix: Location? = null

    /** v1.6: OCR the saved still, then save the capture with the OCR plate (unconfirmed). */
    private fun runOcrThenSave(file: File) {
        val ocr = plateOcr
        if (ocr == null || !ocr.ready) {
            runOnUiThread { onPhotoSaved(file, null) }
            return
        }
        ocrLine?.text = "OCR: reading plate…"
        ocr.recognizeFileAsync(file) { result ->
            runOnUiThread {
                if (result.plate != null) {
                    ocrLine?.text = "OCR: ${result.plate} (${result.confidence}% via ${result.method}) — tap Data to confirm"
                    plateField?.setText(result.plate)
                    ocrFilledPlate = true
                } else {
                    ocrLine?.text = "OCR: no plate found in this photo — type it if you can see one"
                }
                onPhotoSaved(file, result.plate)
            }
        }
    }

    /** Live preview OCR, throttled to ~3s, only when the capture tab is up. */
    private val liveOcrRunnable = object : Runnable {
        override fun run() {
            if (!liveOcrRunning) return
            val now = System.currentTimeMillis()
            val ocr = plateOcr
            val tv = textureView
            if (liveOcrOn && ocr != null && ocr.ready && tv != null && camera != null && !camera!!.isRecording()
                && OcrLogic.shouldRunOcr(lastLiveOcrMs, now)) {
                lastLiveOcrMs = now
                try {
                    val bmp = tv.bitmap ?: return
                    ocr.recognizeBitmapAsync(bmp) { result ->
                        if (result.plate != null && (plateField?.text?.toString().isNullOrEmpty() || ocrFilledPlate)) {
                            plateField?.setText(result.plate)
                            ocrFilledPlate = true
                            ocrLine?.text = "Live OCR: ${result.plate} (${result.confidence}%)"
                        }
                    }
                } catch (e: Exception) { }
            }
            liveOcrHandler.postDelayed(this, 3000)
        }
    }

    private fun onPhotoSaved(file: File, ocrPlate: String?) {
        val fix = pendingFix
        val plate = ocrPlate ?: pendingPlate
        val id = db.addCapture(Capture(
            takenAt = System.currentTimeMillis(),
            lat = fix?.latitude ?: 0.0, lon = fix?.longitude ?: 0.0,
            plate = plate, confirmed = false, photoPath = file.absolutePath,
            ssids = latestSsids.joinToString(","), radios = latestRadios.joinToString(",")
        ))
        val watched = plate.isNotEmpty() && db.isWatched(plate)
        toast("Saved capture #$id" + (if (plate.isNotEmpty()) " — plate $plate (unconfirmed)" else " — no plate") + if (watched) " — ⭐ WATCHLIST HIT" else "")
        pendingPlate = ""
        ocrFilledPlate = false
        updateStatus()
    }

    private fun toggleRecord() {
        val c = camera ?: return
        if (c.isRecording()) {
            val f = c.stopRecording()
            toast("Video saved: ${f?.name}")
        } else {
            val dir = File(getExternalFilesDir(null), "videos").apply { mkdirs() }
            val file = File(dir, "vid_${System.currentTimeMillis()}.mp4")
            if (c.startRecording(file)) toast("Recording ${c.settings.label()} @ ${c.settings.fps}fps, zoom ${"%.1f".format(c.zoom.ratio)}x")
        }
    }

    private fun refreshScan() {
        try { wifiManager?.startScan() } catch (e: Exception) { }
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            adapter?.bluetoothLeScanner?.startScan(bleCallback)
            Handler(Looper.getMainLooper()).postDelayed({
                try { adapter?.bluetoothLeScanner?.stopScan(bleCallback) } catch (e: Exception) { }
            }, 10000)
        } catch (e: Exception) { }
        toast("Scanning WiFi + Bluetooth…")
    }

    private fun updateStatus() {
        val (caps, nets, marks) = db.stats()
        val fix = lastFix
        statusLine?.text = "%d captures · %d networks · %d marks\nGPS: %s\nWiFi here: %s\nBT here: %s".format(
            caps, nets, marks,
            if (fix != null) "%.5f, %.5f".format(fix.latitude, fix.longitude) else "no fix yet",
            latestSsids.take(5).joinToString(", ").ifEmpty { "—" },
            latestRadios.take(5).joinToString(", ").ifEmpty { "—" }
        )
    }

    // ---------- Map tab (the map fix) ----------

    private fun buildMapPanel(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val searchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        mapSearch = EditText(this).apply { hint = "Search plate, SSID, BSSID…" }
        val searchBtn = Button(this).apply { text = "Search" }
        searchBtn.setOnClickListener { searchOnMap() }
        searchRow.addView(mapSearch, LinearLayout.LayoutParams(0, -2, 1f))
        searchRow.addView(searchBtn)
        col.addView(pad(searchRow))

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun mapBtn(label: String, fn: () -> Unit): Button {
            val b = Button(this).apply { text = label }
            b.setOnClickListener { fn() }
            btnRow.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
            return b
        }
        mapBtn("+") { mapView?.zoomIn() }
        mapBtn("−") { mapView?.zoomOut() }
        mapBtn("◎ Me") { centreOnMe() }
        mapBtn("Follow") {
            val m = mapView ?: return@mapBtn
            m.followMe = !m.followMe
            toast(if (m.followMe) "Follow ON — map follows your GPS" else "Follow OFF")
            if (m.followMe) centreOnMe()
        }
        col.addView(btnRow)

        val btnRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val offlineBtn = Button(this).apply { text = "Offline: OFF" }
        offlineBtn.setOnClickListener {
            val m = mapView ?: return@setOnClickListener
            m.offlineOnly = !m.offlineOnly
            offlineBtn.text = if (m.offlineOnly) "Offline: ON" else "Offline: OFF"
            m.invalidate()
            mapStatus?.text = if (m.offlineOnly) "Offline mode — cached tiles only" else "Online mode — tiles download as needed"
        }
        val dlBtn = Button(this).apply { text = "⬇ Download area" }
        dlBtn.setOnClickListener { downloadArea() }
        btnRow2.addView(offlineBtn, LinearLayout.LayoutParams(0, -2, 1f))
        btnRow2.addView(dlBtn, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(btnRow2)

        mapView = TileMapView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            onMarkerTap = { m -> showMarkerDetail(m) }
            onTileStatus = { msg -> runOnUiThread { mapStatus?.text = msg } }
        }
        col.addView(mapView)
        mapStatus = TextView(this).apply { text = "Map ready — OSM tiles, cached for offline. Tap a pin for details." }
        col.addView(pad(mapStatus!!))
        return col
    }

    private fun centreOnMe() {
        val fix = lastFix
        if (fix == null) { toast("No GPS fix yet — wait a moment, then tap ◎ again"); return }
        mapView?.centreOn(fix.latitude, fix.longitude, 16.0)
    }

    private fun downloadArea() {
        val m = mapView ?: return
        mapStatus?.text = "Downloading tiles z12–16 for the visible area…"
        m.downloadVisibleArea(
            onProgress = { ok, fail -> runOnUiThread { mapStatus?.text = "Downloading tiles… $ok ok, $fail failed" } },
            onDone = { ok, fail -> runOnUiThread { mapStatus?.text = "Offline download done: $ok tiles saved, $fail failed. Map works offline here now." } }
        )
    }

    private fun refreshMap() {
        val m = mapView ?: return
        val markers = mutableListOf<MapMarker>()
        for (c in db.listCaptures(300)) {
            if (c.lat == 0.0 && c.lon == 0.0) continue
            markers.add(MapMarker(c.lat, c.lon, "capture",
                if (c.plate.isNotEmpty()) c.plate else "Capture",
                dateFmt.format(Date(c.takenAt)) + if (c.confirmed) "" else " (unconfirmed)", c.id))
        }
        for (n in db.listNetworks(300)) {
            if (n.lat == 0.0 && n.lon == 0.0) continue
            markers.add(MapMarker(n.lat, n.lon, "network",
                if (n.ssid.isNotEmpty()) n.ssid else n.bssid,
                "${n.bssid} · ${n.rssi} dBm"))
        }
        for ((name, lon, lat) in db.listKmlPoints()) {
            markers.add(MapMarker(lat, lon, "kml", if (name.isNotEmpty()) name else "KML pin", "from KML"))
        }
        for (mk in db.listMarks()) {
            markers.add(MapMarker(mk.lat, mk.lon, "mark", mk.note.ifEmpty { "Mark" }, dateFmt.format(Date(mk.at)), mk.id))
        }
        m.markers = markers
        val (caps, nets, _) = db.stats()
        mapStatus?.text = "%d captures · %d networks · %d pins on map".format(caps, nets, markers.size)
        m.invalidate()
    }

    private fun searchOnMap() {
        val q = mapSearch?.text?.toString()?.trim() ?: ""
        if (q.isEmpty()) return
        val caps = db.searchCaptures(q)
        if (caps.isNotEmpty() && (caps[0].lat != 0.0 || caps[0].lon != 0.0)) {
            mapView?.centreOn(caps[0].lat, caps[0].lon, 17.0)
            toast("Found ${caps.size} capture(s) — centred on the first")
            return
        }
        val net = db.listNetworks(500).firstOrNull {
            it.ssid.contains(q, true) || it.bssid.contains(q, true)
        }
        if (net != null && (net.lat != 0.0 || net.lon != 0.0)) {
            mapView?.centreOn(net.lat, net.lon, 17.0)
            toast("Found network ${net.ssid} ${net.bssid}")
            return
        }
        toast("No match on the map for '$q'")
    }

    private fun showMarkerDetail(m: MapMarker) {
        val msg = StringBuilder("${m.title}\n${m.subtitle}\n%.5f, %.5f".format(m.lat, m.lon))
        if (m.kind == "capture") {
            val cap = db.listCaptures(500).firstOrNull { it.id == m.id }
            if (cap != null) {
                msg.append("\nPhoto: ${cap.photoPath.ifEmpty { "—" }}\nWiFi: ${cap.ssids.ifEmpty { "—" }}\nRadios: ${cap.radios.ifEmpty { "—" }}")
            }
        }
        AlertDialog.Builder(this)
            .setTitle(m.title)
            .setMessage(msg.toString())
            .setPositiveButton("Open in Maps") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${m.lat},${m.lon}?q=${m.lat},${m.lon}")))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---------- Data tab ----------

    private lateinit var dataList: ListView
    private var dataMode = "captures"

    private fun buildDataPanel(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun modeBtn(label: String, mode: String): Button {
            val b = Button(this).apply { text = label }
            b.setOnClickListener { dataMode = mode; refreshDataPanel() }
            row.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
            return b
        }
        modeBtn("Captures", "captures"); modeBtn("Networks", "networks"); modeBtn("Watchlist", "watch")
        col.addView(row)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun actBtn(label: String, fn: () -> Unit): Button {
            val b = Button(this).apply { text = label }
            b.setOnClickListener { fn() }
            row2.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
            return b
        }
        actBtn("Import CSV") { pickCsv() }
        actBtn("Export KML") { exportKml() }
        actBtn("Load KML") { pickKml() }
        col.addView(row2)
        dataList = ListView(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        col.addView(dataList)
        return col
    }

    private fun refreshDataPanel() {
        val items = mutableListOf<String>()
        when (dataMode) {
            "captures" -> for (c in db.listCaptures(200)) {
                items.add("${if (c.plate.isNotEmpty()) c.plate else "(no plate)"} · ${dateFmt.format(Date(c.takenAt))} · %.4f,%.4f".format(c.lat, c.lon) + if (c.confirmed) "" else " · unconfirmed")
            }
            "networks" -> for (n in db.listNetworks(200)) {
                items.add("${if (n.ssid.isNotEmpty()) n.ssid else "(hidden)"} · ${n.bssid} · ${n.rssi} dBm · ch ${n.channel}")
            }
            "watch" -> for (p in db.watchlist()) items.add("⭐ $p")
        }
        if (items.isEmpty()) items.add("(nothing yet)")
        dataList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
        dataList.setOnItemClickListener { _, _, pos, _ ->
            if (dataMode == "captures") {
                val caps = db.listCaptures(200)
                if (pos < caps.size) promptCorrectPlate(caps[pos])
            }
        }
    }

    private fun promptCorrectPlate(c: Capture) {
        val input = EditText(this).apply { setText(c.plate); hint = "Plate, e.g. ABC123" }
        AlertDialog.Builder(this)
            .setTitle("Correct / confirm plate")
            .setView(input)
            .setPositiveButton("Confirm") { _, _ ->
                val p = Plates.normalise(input.text.toString())
                if (p.isNotEmpty()) { db.correctPlate(c.id, p); refreshDataPanel(); toast("Plate confirmed: $p") }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickCsv() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
        startActivityForResult(Intent.createChooser(i, "Import WiGLE / Kismet / airodump CSV"), 10)
    }

    private fun pickKml() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
        startActivityForResult(Intent.createChooser(i, "Load KML"), 11)
    }

    private fun exportKml() {
        try {
            val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>Wardrive Companion</name>")
            for (c in db.listCaptures(1000)) {
                if (c.lat == 0.0 && c.lon == 0.0) continue
                sb.append(Kml.placemark(c.plate.ifEmpty { "Capture" }, dateFmt.format(Date(c.takenAt)), c.lon, c.lat))
            }
            for (n in db.listNetworks(1000)) {
                if (n.lat == 0.0 && n.lon == 0.0) continue
                sb.append(Kml.placemark(n.ssid.ifEmpty { n.bssid }, "${n.bssid} ${n.rssi} dBm", n.lon, n.lat))
            }
            sb.append("</Document></kml>")
            val dir = File(getExternalFilesDir(null), "exports").apply { mkdirs() }
            val f = File(dir, "wardrive_${System.currentTimeMillis()}.kml")
            f.writeText(sb.toString())
            shareFile(f, "application/vnd.google-earth.kml+xml", "Share KML export")
        } catch (e: Exception) { toast("KML export failed: ${e.message}") }
    }

    private fun shareFile(f: File, mime: String, title: String) {
        val uri = Uri.parse("content://art.phoenix.wardrive.fileprovider/exports/${f.name}")
        val i = Intent(Intent.ACTION_SEND).apply {
            type = mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, title))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        try {
            val text = contentResolver.openInputStream(data.data!!)?.bufferedReader()?.readText() ?: return
            when (requestCode) {
                10 -> {
                    val (ok, skip) = importNetworksCsv(db, text, System.currentTimeMillis())
                    toast("CSV import: $ok networks, $skip skipped")
                    refreshDataPanel()
                }
                11 -> {
                    val points = Kml.parsePoints(text)
                    db.saveKmlPoints(points)
                    toast("KML loaded: ${points.size} points plotted as purple ◆ pins on the map")
                    refreshMap()
                }
            }
        } catch (e: Exception) { toast("Import failed: ${e.message}") }
    }

    // ---------- lifecycle ----------

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                locManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 5f, locListener)
                locManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000, 10f, locListener)
                lastFix = locManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } catch (e: Exception) { }
        }
        registerReceiver(wifiReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        mapView?.onResume()
        updateStatus()
        liveOcrRunning = true
        liveOcrHandler.postDelayed(liveOcrRunnable, 3000)
    }

    override fun onPause() {
        super.onPause()
        liveOcrRunning = false
        liveOcrHandler.removeCallbacks(liveOcrRunnable)
        try { locManager?.removeUpdates(locListener) } catch (e: Exception) { }
        try { unregisterReceiver(wifiReceiver) } catch (e: Exception) { }
        mapView?.onPause()
        camera?.stop()
        camera = null
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

/** Minimal ContentProvider so KML/CSV exports can be shared (no AndroidX in this build). */
class ExportProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): android.database.Cursor? = null
    override fun getType(u: Uri): String? = "application/octet-stream"
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<String>?): Int = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int = 0
    override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor? {
        val name = uri.lastPathSegment ?: return null
        val dir = File(context?.getExternalFilesDir(null), "exports")
        val f = File(dir, name)
        if (!f.exists() || !f.canonicalPath.startsWith(dir.canonicalPath)) return null
        return android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
    }
}
