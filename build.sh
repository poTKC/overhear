#!/usr/bin/env bash
# Builds build/st15i-player.apk without Gradle.
# Overrides: ANDROID_HOME, BUILD_TOOLS (build-tools dir), ANDROID_PLATFORM_JAR,
# DEBUGGABLE=1 to add android:debuggable (off by default; enables run-as and jdwp),
# SKIP_RUNTIME=1 to package build/assets as it is instead of running runtime/build.sh.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="${BUILD_TOOLS:-$SDK/build-tools/36.0.0}"
JAR="${ANDROID_PLATFORM_JAR:-$SDK/platforms/android-18/android.jar}"
OUT=build/st15i-player.apk

die() { echo "build.sh: $*" >&2; exit 1; }
for tool in aapt2 d8 zipalign apksigner; do
    [ -x "$BT/$tool" ] || die "missing $BT/$tool (set BUILD_TOOLS)"
done
[ -f "$JAR" ] || die "missing $JAR (set ANDROID_PLATFORM_JAR)"
for tool in javac keytool zip; do
    command -v "$tool" >/dev/null || die "$tool not found on PATH"
done

# Clean intermediates; build/assets, build/native and build/cache belong to
# runtime/build.sh and native/build.sh.
mkdir -p build
find build -mindepth 1 -maxdepth 1 ! -name assets ! -name native ! -name cache -exec rm -rf {} +

# Runtime bundle (assets/runtime); cached and reproducible, so cheap to rerun.
[ "${SKIP_RUNTIME:-0}" = 1 ] || runtime/build.sh
mkdir -p build/gen build/classes build/dex

"$BT/aapt2" compile --dir app/res -o build/res.zip

link_opts=()
[ -d build/assets ] && link_opts=(-A build/assets)
# The app's own committed assets (fonts/st15i-digits.ttf from tools/make_fonts.py).
[ -d app/assets ] && link_opts+=(-A app/assets)
[ "${DEBUGGABLE:-0}" = 1 ] && link_opts+=(--debug-mode)
"$BT/aapt2" link -I "$JAR" --manifest app/AndroidManifest.xml \
    --min-sdk-version 16 --target-sdk-version 18 \
    ${link_opts[@]+"${link_opts[@]}"} --java build/gen -o build/base.apk build/res.zip

# Compile against the Android jar (not --release) so only Android APIs resolve.
find app/src build/gen -name '*.java' > build/sources.txt
javac -source 8 -target 8 -bootclasspath "$JAR" -Xlint:-options \
    -d build/classes @build/sources.txt

find build/classes -name '*.class' > build/classes.txt
"$BT/d8" --min-api 16 --lib "$JAR" --output build/dex @build/classes.txt

# aapt2 link already packed build/assets (via -A); add the dex.
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)

"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk

if [ ! -f keys/debug.jks ]; then
    mkdir -p keys
    keytool -genkeypair -keystore keys/debug.jks -storepass android -keypass android \
        -alias debug -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=ST15i Player Debug" >/dev/null
fi
"$BT/apksigner" sign --ks keys/debug.jks --ks-pass pass:android --key-pass pass:android \
    --v4-signing-enabled false --out "$OUT" build/aligned.apk

echo "Built $OUT"
