#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_JAR:?Укажи путь к android.jar}"
: "${ANDROID_BUILD_TOOLS:?Укажи каталог Android Build Tools}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
tools="$ANDROID_BUILD_TOOLS"
out="../build/android"
mkdir -p "$out" "$work/classes" "$work/dex"
"$tools/aapt2" compile --dir res -o "$work/res.zip"
"$tools/aapt2" link --manifest AndroidManifest.xml -I "$ANDROID_JAR" -o "$work/resources.apk" "$work/res.zip"
javac --release 11 -classpath "$ANDROID_JAR" -d "$work/classes" src/mentor/focus/*.java
jar --create --file "$work/classes.jar" -C "$work/classes" .
"$tools/d8" --min-api 26 --lib "$ANDROID_JAR" --output "$work/dex" "$work/classes.jar"
(cd "$work/dex" && zip -q "$work/resources.apk" classes.dex)
"$tools/zipalign" -f 4 "$work/resources.apk" "$work/aligned.apk"
if [[ ! -f "$out/debug.keystore" ]]; then
    keytool -genkeypair -keystore "$out/debug.keystore" -storepass android -keypass android \
        -alias mentor-focus -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=Mentor Focus local build'
fi
"$tools/apksigner" sign --ks "$out/debug.keystore" --ks-pass pass:android \
    --out "$out/mentor-focus.apk" "$work/aligned.apk"
"$tools/apksigner" verify "$out/mentor-focus.apk"
