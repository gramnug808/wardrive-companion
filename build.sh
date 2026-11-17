#!/bin/bash
# Manual APK build — no Gradle daemon, no localhost TCP.
# kotlinc + aapt2 + d8 + zipalign + apksigner, all single-process tools.
# Pattern proven by ~/workspace/iot-lights-simple and ~/workspace/who-is-home.
# v1.6: + Tesseract OCR (tess-two 9.1.0, vendored in libs/, no Gradle).
# Gradle is still blocked in this VM (Java localhost TCP is intercepted:
# SockTest reads 109 'm' instead of 42), so do NOT switch this to Gradle.
set -e
cd "$(dirname "$0")"
export JAVA_HOME=$HOME/jdk17
export PATH=$JAVA_HOME/bin:$PATH
unset JAVA_TOOL_OPTIONS
BT=$HOME/Android/sdk/build-tools/34.0.0
ANDROID_JAR=$HOME/Android/sdk/platforms/android-34/android.jar
KOTLINC=$HOME/workspace/iot-lights-simple/tools/kotlinc/bin/kotlinc
STDLIB=$HOME/workspace/iot-lights-simple/tools/kotlinc/lib/kotlin-stdlib.jar
TESS_JAR=libs/tess-two-classes.jar
mkdir -p build/classes build/dex build/test-classes

test -f "$TESS_JAR" || { echo "MISSING $TESS_JAR — vendor tess-two first"; exit 1; }
test -f assets/tessdata/eng.traineddata || { echo "MISSING assets/tessdata/eng.traineddata"; exit 1; }

echo "== unit tests =="
"$KOTLINC" -cp "$ANDROID_JAR" -d build/test-classes \
  src/art/phoenix/wardrive/Logic.kt src/art/phoenix/wardrive/Db.kt \
  src/art/phoenix/wardrive/Camera.kt \
  test/art/phoenix/wardrive/Tests.kt
"$JAVA_HOME/bin/java" -cp "build/test-classes:$ANDROID_JAR:$STDLIB" \
  art.phoenix.wardrive.WardriveTests

echo "== kotlinc compile =="
rm -rf build/classes; mkdir -p build/classes
"$KOTLINC" -cp "$ANDROID_JAR:$TESS_JAR" -d build/classes src/art/phoenix/wardrive/*.kt

echo "== aapt2 link (assets: tessdata/eng.traineddata) =="
"$BT/aapt2" link -o build/app-unsigned.apk --manifest AndroidManifest.xml -I "$ANDROID_JAR" --auto-add-overlay -A assets

echo "== d8 dex (app + stdlib + tess-two) =="
rm -rf build/dex; mkdir -p build/dex
jar cf build/app-classes.jar -C build/classes .
"$BT/d8" --output build/dex --lib "$ANDROID_JAR" --min-api 26 --release build/app-classes.jar "$STDLIB" "$TESS_JAR"

echo "== add classes.dex + native libs to APK =="
python3 -c "
import zipfile, os
# .so files must be stored uncompressed in the APK
with zipfile.ZipFile('build/app-unsigned.apk','a',zipfile.ZIP_DEFLATED) as z:
    z.write('build/dex/classes.dex','classes.dex')
    for abi in ('arm64-v8a','armeabi-v7a'):
        d = os.path.join('libs/native', abi)
        for name in sorted(os.listdir(d)):
            if name.endswith('.so'):
                z.write(os.path.join(d, name), 'lib/%s/%s' % (abi, name), compress_type=zipfile.ZIP_STORED)
print('dex + native libs added')
"

echo "== zipalign =="
"$BT/zipalign" -f 4 build/app-unsigned.apk build/app-aligned.apk

echo "== keystore (same key as v1.5, so v1.6 installs OVER v1.5) =="
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -alias debug -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname "CN=Wardrive Companion, OU=Dev, O=Pip, C=US" -noprompt
fi

echo "== apksigner =="
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android --out build/wardrive-companion-v1.6.apk build/app-aligned.apk

echo "== verify =="
"$BT/apksigner" verify --verbose build/wardrive-companion-v1.6.apk
"$BT/aapt2" dump badging build/wardrive-companion-v1.6.apk | head -20
python3 -c "
import zipfile
z = zipfile.ZipFile('build/wardrive-companion-v1.6.apk')
print('APK entries:', len(z.namelist()))
for n in z.namelist():
    if 'tess' in n or 'tessdata' in n or n.startswith('lib/') or n == 'classes.dex':
        print(' ', n, z.getinfo(n).file_size)
"
ls -lh build/wardrive-companion-v1.6.apk
sha256sum build/wardrive-companion-v1.6.apk
