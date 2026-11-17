# Wardrive Companion v1.6 — plate OCR is back (Tesseract, on-device)

Clean-room rebuild of the shipped v1.4 APK (`art.phoenix.wardrive`,
versionCode 7, 41 MB), because no source survives in the workspace.
Rebuilt from static analysis of v1.4 (dexdump / aapt2 / strings) with
the manual kotlinc + aapt2 + d8 + apksigner pipeline (no Gradle).

## v1.6 — OCR (the fix for "pointless to hand plate reader that doesn't read plates")

Engine: **Tesseract via tess-two 9.1.0** (com.rmtheis), `eng.traineddata`
(fast, 4.0 MB) in `assets/tessdata/`, native libs `libtess/liblept/
libjpgt/libpngt` for arm64-v8a + armeabi-v7a, vendored in `libs/`.
**On-device, offline, no cloud, no photo leaves the phone.**

Why Tesseract and not MLKit: Gradle is still blocked in this VM —
Java TCP to localhost is intercepted (SockTest reads 109 'm', the
first byte of the runtime denial, instead of 42), so the MLKit Gradle
build cannot run here. Tesseract builds with the manual pipeline and
was the specified fallback. Re-tested 2026-11-15: the socket test
still returns 109, so no Gradle retry was attempted.

How it reads a plate (`Ocr.kt`, `OcrLogic` in `Logic.kt`):
- Stills are OCR'd on save; the live preview is OCR'd every ~3s
  (throttled, toggleable "Live OCR: ON/OFF" button). A found plate
  auto-fills the plate field and is saved **unconfirmed** — confirm
  it in the Data tab, same flow as v1.4.
- 4 passes per image: full frame PSM single-line, centre-band crop
  PSM single-line, Otsu-thresholded binary PSM single-word, full
  frame PSM auto. Best-scoring plate wins; minimum confidence 55
  (a wrong plate is worse than no plate).
- Positional confusion repair: Hawaii plates are 3 letters + 3
  digits, so 0/O, 1/I, 5/S, 8/B, 2/Z are repaired by position.
  Split tokens are joined in both orders ("ABC 123", "C123 AB").
  Generic plates must contain a digit, so "PARKING" is not a plate.
- EXIF rotation is corrected before OCR; small images are upscaled
  to ~1200px wide for Tesseract.

## Tests — v1.6
- **63/63 JVM unit tests pass** (`./build.sh`): all 44 v1.5 tests
  plus 19 OCR tests (extraction, confusion repair, reverse-pair,
  Otsu, throttle, crop, false-positive rejection).
- **Synthetic-plate proxy OCR: 6/7 pass** — RapidOCR (a real OCR
  engine) on PIL-generated plates, raw text through the same
  extraction rules: clean ABC123 / KLM456 / XYZ789, blurred HAW777,
  rotated ABC123 (reversed tokens), and "PARKING LOT" correctly
  rejected. The 1 fail is a plate with 800 random noise dots,
  which garbles any OCR engine. Test script + images in
  `test-ocr/` (`run_proxy_test.py`).
- **Honest limit:** the Android native Tesseract .so cannot run on
  a Linux JVM, so Tesseract itself was NOT run here — the proxy
  proves the pipeline, not Tesseract on a real photo. Live phone
  test still needed: daylight plate, night plate, angled plate.
  The 2 tests above that caught real bugs (sliding-window
  hallucination "KINGLO" -> "KIN610", pure-word generic plates)
  were fixed before this build.

## Install — v1.6 installs OVER v1.5
- Same debug keystore / signing certificate as v1.5
  (SHA-256 ea4254815b8e3622acbbb6c9f81d306adee9ac1524ef79c570829c32671b80d5),
  so **install v1.6 over v1.5 — no uninstall, no data loss**.
  (v1.4 still needs export + uninstall first, different key.)
- v1.6 is 14 MB (vs 641 KB v1.5, 41 MB v1.4) — the size is the
  Tesseract native libs + eng.traineddata.

## How to test on the phone
1. Install over v1.5, grant Camera + Location.
2. Capture tab: point at a plate, watch "OCR: …" — the plate
   field should fill itself within ~3s (live OCR) or right after
   you take a still. Check the confidence shown.
3. Take a still of a plate — it appears as a red pin on the Map
   tab, plate in the label, marked unconfirmed. Tap it in Data
   to confirm / correct it.
4. Try a night / angled plate and tell me the OCR line — that's
   the real accuracy test.


Clean-room rebuild of the shipped v1.4 APK (`art.phoenix.wardrive`,
versionCode 7, 41 MB), because no source survives in the workspace.
Rebuilt from static analysis of v1.4 (dexdump / aapt2 / strings) with
the manual kotlinc + aapt2 + d8 + apksigner pipeline (no Gradle).

## What was wrong in v1.4 (verified statically, no live device here)

### Map
- osmdroid MAPNIK, centre Honolulu 21.3099/-157.858, zoom 15, pinch only.
- **No MyLocation overlay anywhere in MainActivity** — the map never
  showed where you are and never followed you while driving.
- **No built-in zoom controls** — pinch only.
- Bare tile User-Agent (OSM throttles/403s unidentified clients) and
  **no tile-failure feedback** — a blocked tile server left a blank
  grey map with markers floating on nothing.
- No offline fallback rendering when tiles were missing.

### Video / zoom
- CameraActivity and VideoActivity contain **no zoom code at all**:
  no zoomRatio, no ScaleGestureDetector, no pinch, no slider.
  CameraSettings only had resolution / fps / torch / lock / sample.

## What v1.5 fixes

### Map — `TileMap.kt` (self-contained OSM tile view, no osmdroid dep)
- Identifying User-Agent `WardriveCompanion/1.5 (art.phoenix.wardrive)`,
  HTTP 403/429 and download failures reported on the map status line.
- Disk tile cache in the **same layout v1.4 used**
  (`getExternalFilesDir/osmdroid/tiles/<z>/<x>/<y>.png`), so areas
  downloaded in v1.4 are reused; 200 MB LRU cap.
- Offline mode (cached tiles only; uncached areas draw a grid +
  markers, never a dead blank screen) + "⬇ Download area" (z12–16
  for the visible area, with progress).
- My-location blue dot + **Follow** mode (map follows GPS) + ◎ Me
  centre button.
- Zoom **+ / − buttons**, pinch, double-tap zoom.
- Markers: captures (red), networks (blue), KML pins (purple),
  marks (amber). **Tap a marker** → detail dialog (plate/SSID/BSSID,
  time, coords, photo, radios) + "Open in Maps".
- Search box (plate / SSID / BSSID) jumps the map to the match.

### Video / zoom — `Camera.kt`
- **Pinch-to-zoom** on the preview, **zoom slider** (log scale,
  fine control at 1–2x where plates are), **+ / − buttons** (0.5x steps),
  zoom label (e.g. "Zoom 2.5x").
- Applied via `CaptureRequest.CONTROL_ZOOM_RATIO` (API 30+) with a
  centred `SCALER_CROP_REGION` fallback (API 26–29), on the **same
  repeating request that feeds preview AND MediaRecorder** — zoom works
  in live preview **and** in the recorded video.
- Video settings: resolution **1080p / 720p / 480p**, fps **30 / 24**,
  torch — persisted in SharedPreferences. Tap-to-focus.
- Stills: geotagged JPEG, burst mode (1 shot / 5 s), plate field,
  WiFi + Bluetooth pairing at capture time, watchlist hit alert.

### Kept from v1.4
- SQLite DB (captures / networks / marks / watchlist / KML points),
  unconfirmed → confirmed plate flow, CSV import (WiGLE / Kismet /
  airodump — dialect sniffed by header), KML export + import,
  share via ContentProvider, ESP32 status is NOT in this build.

## Honest gap — plate OCR is NOT in v1.5
v1.4's plate extraction used bundled MLKit OCR models (~30 MB of
proprietary assets inside the 41 MB APK). Those assets cannot be
rebuilt from source without Gradle + the MLKit dependency, so v1.5
does **not** auto-OCR plates: type the plate (regex-validated,
Hawaii ABC123 recognised) and confirm it in the Data tab. The
`CameraController.onStillSaved` hook is left for a future Gradle
build that re-adds MLKit.

## Install — IMPORTANT
- v1.5 is signed with a **different debug key** than v1.4, so it will
  NOT install over v1.4. **Export KML/CSV from v1.4 first, uninstall
  v1.4, then install v1.5** (its database starts empty; import the
  CSV/KML back in from the Data tab).
- v1.5 is 641 KB (vs 41 MB) because the MLKit models are not bundled.

## Tests
- 44/44 JVM unit tests pass (`./build.sh` runs them first):
  tile math (Honolulu round-trip), zoom mapping (log slider, pinch,
  crop region), plate regex, CSV dialects, KML parse/escape,
  video tiers/bitrates.
- APK verified: `apksigner verify` — v2 + v3 signatures, 1 signer;
  `aapt2 dump badging` — package art.phoenix.wardrive, v1.5 (code 8),
  minSdk 26, targetSdk 34, launchable MainActivity; dex contains
  TileMapView, CameraController, ZoomController, DbHelper.
- **Not tested live on a phone** (no device here): map tiles,
  GPS follow, camera zoom, and recording all need Kevin's phone test.

## How to test on the phone
1. Install, grant Camera + Location (+ Bluetooth on Android 12+).
2. Map tab: tiles should load (Honolulu first). Tap ◎ Me — blue dot
   appears and the map centres on you. Tap Follow, drive — the map
   should move with you. Tap + / − — map zooms. Turn on Offline,
   go somewhere with no signal — cached areas still show.
3. Capture tab: pinch the preview — zoom label changes (e.g. 2.5x).
   Use the slider and + / −. Set video to 720p, tap ● Record, zoom
   while recording, stop — play the video back: it should be zoomed
   in, not full-wide.
4. Take a still with a plate typed in, check it appears as a red pin
   on the Map tab; tap the pin for details.

## Build
`./build.sh` — kotlinc + aapt2 + d8 + zipalign + apksigner.
Source: `src/art/phoenix/wardrive/` (Logic, Db, TileMap, Camera,
MainActivity). Tests: `test/art/phoenix/wardrive/Tests.kt`.
