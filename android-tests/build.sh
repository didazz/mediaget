#!/bin/bash
# Builds only a separate instrumentation APK, never test code inside MediaGet.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
: "${ANDROID_HOME:?Set ANDROID_HOME}"
: "${DESCARGA_SOCIAL_ECJ_JAR:?Set DESCARGA_SOCIAL_ECJ_JAR}"
: "${DESCARGA_SOCIAL_KEYSTORE:?Use the same signing key as the app}"
: "${DESCARGA_SOCIAL_STORE_PASSWORD:?Set the signing password}"
BUILD="$ANDROID_HOME/build-tools/35.0.0"
PLATFORM="$ANDROID_HOME/platforms/android-35/android.jar"
WORK=$(mktemp -d "$ROOT/.build.XXXXXX")
trap 'find "$WORK" -depth -mindepth 1 -delete; rmdir "$WORK"' EXIT
mkdir -p "$WORK/classes" "$WORK/dex"
java -jar "$DESCARGA_SOCIAL_ECJ_JAR" -proc:none -source 8 -target 8 -classpath "$PLATFORM" -d "$WORK/classes" "$ROOT/src/com/didazz/descargasocial/tests/NativeChecks.java"
(cd "$WORK/classes" && zip -qr "$WORK/classes.jar" .)
"$BUILD/d8" --min-api 33 --lib "$PLATFORM" --output "$WORK/dex" "$WORK/classes.jar"
"$BUILD/aapt2" link -I "$PLATFORM" --manifest "$ROOT/src/AndroidManifest.xml" -A "$ROOT/src/assets" -o "$WORK/unsigned.apk"
(cd "$WORK/dex" && zip -qu "$WORK/unsigned.apk" classes.dex)
"$BUILD/zipalign" -f 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
"$BUILD/apksigner" sign --ks "$DESCARGA_SOCIAL_KEYSTORE" --ks-pass env:DESCARGA_SOCIAL_STORE_PASSWORD --out "$ROOT/MediaGet-native-tests.apk" "$WORK/aligned.apk"
printf '%s\n' 'Install the app and this separate test APK on a test device, then run:' 'adb shell am instrument -w com.didazz.descargasocial.tests/.NativeChecks'
