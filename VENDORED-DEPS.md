# Vendored / excluded files

The GitHub snapshot tool only accepts UTF-8 text files (max 16 MiB
total, no binaries). The following files are in the local build tree
but are NOT in this repo snapshot:

- `build/` — build outputs (APKs, dex, compiled classes). Regenerated
  by `./build.sh`.
- `debug.keystore` — local debug signing key. Do NOT commit a keystore.
  `./build.sh` generates one if missing; v1.6 must be signed with the
  SAME key as v1.5 to install over it, so keep that key safe locally.
- `libs/` — tess-two 9.1.0: `tess-two-classes.jar`,
  `tess-two-9.1.0.aar`, and native `.so` files
  (`libtess/liblept/libjpgt/libpngt`, arm64-v8a + armeabi-v7a).
  Binary files; `build.sh` refuses to run without
  `libs/tess-two-classes.jar`. Vendor tess-two 9.1.0 locally before
  building.
- `assets/tessdata/eng.traineddata` — Tesseract English trained data
  (binary, ~4 MB). `build.sh` refuses to run without it. Download the
  tessdata_fast `eng.traineddata` and place it in
  `assets/tessdata/` before building.
- `test-ocr/*.png` — synthetic test plate images (binary). The proxy
  OCR test script `test-ocr/run_proxy_test.py` IS in the repo; the
  images it reads are not. Regenerate them locally or run the JVM
  unit tests in `./build.sh` instead.

Everything else needed to build — `AndroidManifest.xml`, `build.sh`,
`src/**/*.kt`, `test/**/*.kt`, `test-ocr/run_proxy_test.py`,
`README.md` — is in this repo.
