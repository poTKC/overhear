#!/usr/bin/env bash
# Builds build/st15i-player.apk without Gradle.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/36.0.0"
JAR="$SDK/platforms/android-36/android.jar"
OUT=build/st15i-player.apk

# Clean intermediates; build/assets and build/native are filled by other steps.
mkdir -p build
find build -mindepth 1 -maxdepth 1 ! -name assets ! -name native -exec rm -rf {} +
mkdir -p build/gen build/classes build/dex

"$BT/aapt2" compile --dir app/res -o build/res.zip

assets=()
[ -d build/assets ] && assets=(-A build/assets)
"$BT/aapt2" link -I "$JAR" --manifest app/AndroidManifest.xml \
    --min-sdk-version 16 --target-sdk-version 18 \
    ${assets[@]+"${assets[@]}"} --java build/gen -o build/base.apk build/res.zip

find app/src build/gen -name '*.java' -print0 \
    | xargs -0 javac -Xlint:-options --release 8 -cp "$JAR" -d build/classes

find build/classes -name '*.class' -print0 \
    | xargs -0 "$BT/d8" --min-api 16 --lib "$JAR" --output build/dex

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
    --out "$OUT" build/aligned.apk

echo "Built $OUT"
