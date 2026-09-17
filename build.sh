#!/usr/bin/env bash
set -euo pipefail
umask 077

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$PROJECT_DIR/app"
MANIFEST="$APP_DIR/src/main/AndroidManifest.xml"
RES_DIR="$APP_DIR/src/main/res"
JAVA_DIR="$APP_DIR/src/main/java"

JAVA_HOME="${JAVA_HOME:-/workspace/toolchains/jdk17}"
ANDROID_HOME="${ANDROID_HOME:-/workspace/toolchains/android-sdk}"
BUILD_TOOLS_VERSION="${ANDROID_BUILD_TOOLS_VERSION:-35.0.0}"
COMPILE_SDK="${ANDROID_COMPILE_SDK:-35}"

BUILD_TOOLS="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION"
ANDROID_JAR="$ANDROID_HOME/platforms/android-$COMPILE_SDK/android.jar"
AAPT2="$BUILD_TOOLS/aapt2"
D8="$BUILD_TOOLS/d8"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"
JAVAC="$JAVA_HOME/bin/javac"
ECJ_JAR="${DESCARGA_SOCIAL_ECJ_JAR:-}"
ZIP="${DESCARGA_SOCIAL_ZIP:-/usr/bin/zip}"

DIST_DIR="$PROJECT_DIR/dist"
KEYSTORE="${DESCARGA_SOCIAL_KEYSTORE:-}"
KEY_ALIAS="${DESCARGA_SOCIAL_KEY_ALIAS:-descargasocial}"
STORE_PASSWORD="${DESCARGA_SOCIAL_STORE_PASSWORD:-}"
KEY_PASSWORD="${DESCARGA_SOCIAL_KEY_PASSWORD:-$STORE_PASSWORD}"
FINAL_APK="$DIST_DIR/MediaGet-1.5.0.apk"
# NewPipe uses Rhino directly in interpreted mode, never its desktop javax.script adapter.
RUNTIME_JARS=()
for dependency in "$PROJECT_DIR"/vendor/runtime/*.jar; do
    if [[ "$(basename "$dependency")" != "rhino-engine.jar" ]]; then RUNTIME_JARS+=("$dependency"); fi
done
COMPAT_JAR="$PROJECT_DIR/vendor/compat/desugar.jar"
COMPAT_CONFIG_JAR="$PROJECT_DIR/vendor/compat/desugar-config.jar"
DEPENDENCY_CLASSPATH=$(IFS=:; printf '%s' "${RUNTIME_JARS[*]}")

EXPECTED_SIGNER_SHA256="3ff963c0fd75fe122b43e61bf0765e623b720d0e580a80cfb7f4417ff10f9936"

if [[ -z "$KEYSTORE" || -z "$STORE_PASSWORD" || -z "$KEY_PASSWORD" ]]; then
    echo "Define DESCARGA_SOCIAL_KEYSTORE y las dos contraseñas de firma." >&2
    exit 1
fi

require_file() {
    if [[ ! -f "$1" ]]; then
        echo "Falta el archivo requerido: $1" >&2
        exit 1
    fi
}

require_executable() {
    if [[ ! -x "$1" ]]; then
        echo "Falta la herramienta requerida: $1" >&2
        exit 1
    fi
}

require_file "$MANIFEST"
require_file "$ANDROID_JAR"
require_executable "$AAPT2"
require_executable "$D8"
require_executable "$ZIPALIGN"
require_executable "$APKSIGNER"
if [[ -n "$ECJ_JAR" ]]; then
    require_file "$ECJ_JAR"
    require_executable "$JAVA_HOME/bin/java"
else
    require_executable "$JAVAC"
fi
require_executable "$ZIP"
require_file "$KEYSTORE"
require_file "$COMPAT_JAR"
require_file "$COMPAT_CONFIG_JAR"
for dependency in "${RUNTIME_JARS[@]}"; do require_file "$dependency"; done
(cd "$PROJECT_DIR"; sha256sum --quiet -c vendor/SHA256SUMS)


mkdir -p "$DIST_DIR"
WORK_DIR="$(mktemp -d "$PROJECT_DIR/.build.XXXXXX")"
cleanup() {
    set +e
    find "$WORK_DIR" -depth -mindepth 1 -delete 2>/dev/null
    rmdir "$WORK_DIR" 2>/dev/null
}
trap cleanup EXIT

COMPILED_RESOURCES="$WORK_DIR/compiled-resources.zip"
GENERATED_DIR="$WORK_DIR/generated"
CLASSES_DIR="$WORK_DIR/classes"
DEX_DIR="$WORK_DIR/dex"
CLASSES_JAR="$WORK_DIR/classes.jar"
UNSIGNED_APK="$WORK_DIR/unsigned.apk"
ALIGNED_APK="$WORK_DIR/aligned.apk"
SIGNED_APK="$WORK_DIR/signed.apk"
COMPAT_DEX="$WORK_DIR/compat-dex"
COMPAT_JSON="$WORK_DIR/desugar.json"
RESOURCE_STAGE="$WORK_DIR/resources"


mkdir -p "$GENERATED_DIR" "$CLASSES_DIR" "$DEX_DIR" "$COMPAT_DEX" "$RESOURCE_STAGE"
unzip -p "$COMPAT_CONFIG_JAR" META-INF/desugar/d8/desugar.json > "$COMPAT_JSON"


python3 "$PROJECT_DIR/generate_resource_ids.py"

"$AAPT2" compile \
    --dir "$RES_DIR" \
    -o "$COMPILED_RESOURCES"

"$AAPT2" link \
    -o "$UNSIGNED_APK" \
    -I "$ANDROID_JAR" \
    --manifest "$MANIFEST" \
    -A "$APP_DIR/src/main/assets" \
    --java "$GENERATED_DIR" \
    --min-sdk-version 24 \
    --target-sdk-version 35 \
    --version-code 9 \
    --version-name 1.5.0 \
    "$COMPILED_RESOURCES"

mapfile -d '' JAVA_SOURCES < <(
    find "$JAVA_DIR" "$GENERATED_DIR" -type f -name '*.java' -print0 | sort -z
)

if [[ "${#JAVA_SOURCES[@]}" -eq 0 ]]; then
    echo "No hay fuentes Java para compilar." >&2
    exit 1
fi

if [[ -n "$ECJ_JAR" ]]; then
    "$JAVA_HOME/bin/java" -jar "$ECJ_JAR" -proc:none -encoding UTF-8 -source 8 -target 8 \
        -classpath "$ANDROID_JAR:$DEPENDENCY_CLASSPATH" -d "$CLASSES_DIR" "${JAVA_SOURCES[@]}"
else
    "$JAVAC" -encoding UTF-8 --release 8 -classpath "$ANDROID_JAR:$DEPENDENCY_CLASSPATH" \
        -d "$CLASSES_DIR" "${JAVA_SOURCES[@]}"
fi

(
    cd "$CLASSES_DIR"
    "$ZIP" -q -r "$CLASSES_JAR" .
)

"$D8" \
    --release \
    --min-api 24 \
    --lib "$ANDROID_JAR" \
    --desugared-lib "$COMPAT_JSON" \
    --output "$DEX_DIR" \
    "$CLASSES_JAR" "${RUNTIME_JARS[@]}"

"$JAVA_HOME/bin/java" -cp "$BUILD_TOOLS/lib/d8.jar" com.android.tools.r8.L8 \
    --release --min-api 24 --lib "$ANDROID_JAR" \
    --desugared-lib "$COMPAT_JSON" --output "$COMPAT_DEX" \
    "$COMPAT_JAR" "$COMPAT_CONFIG_JAR"

# Add the compatibility DEX files after the app's DEX files (Android 7 loads multidex natively).
dex_index=1
while [[ -f "$DEX_DIR/classes${dex_index}.dex" || ( "$dex_index" -eq 1 && -f "$DEX_DIR/classes.dex" ) ]]; do
    dex_index=$((dex_index + 1))
done
for compat_dex in "$COMPAT_DEX"/classes*.dex; do
    cp "$compat_dex" "$DEX_DIR/classes${dex_index}.dex"
    dex_index=$((dex_index + 1))
done

# Java resource files are needed by jsoup/Rhino. Never copy class files or JAR signatures.
python3 "$PROJECT_DIR/package_resources.py" "$RESOURCE_STAGE" "${RUNTIME_JARS[@]}"
(
    cd "$RESOURCE_STAGE"
    "$ZIP" -q -r "$UNSIGNED_APK" .
)

(
    cd "$DEX_DIR"
    "$ZIP" -q -u "$UNSIGNED_APK" classes*.dex
)
"$ZIPALIGN" -f -p 4 "$UNSIGNED_APK" "$ALIGNED_APK"

if [[ -f "$FINAL_APK.idsig" ]]; then
    find "$FINAL_APK.idsig" -maxdepth 0 -type f -delete
fi

export DESCARGA_SOCIAL_BUILD_STORE_PASSWORD="$STORE_PASSWORD"
export DESCARGA_SOCIAL_BUILD_KEY_PASSWORD="$KEY_PASSWORD"

"$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias "$KEY_ALIAS" \
    --ks-pass env:DESCARGA_SOCIAL_BUILD_STORE_PASSWORD \
    --key-pass env:DESCARGA_SOCIAL_BUILD_KEY_PASSWORD \
    --v4-signing-enabled false \
    --out "$SIGNED_APK" \
    "$ALIGNED_APK"

VERIFY_OUTPUT="$("$APKSIGNER" verify --verbose --print-certs --Werr "$SIGNED_APK")"
printf '%s\n' "$VERIFY_OUTPUT"
ACTUAL_SIGNER_SHA256="$(printf '%s\n' "$VERIFY_OUTPUT" \
    | awk -F': ' '/Signer #1 certificate SHA-256 digest:/ {print $2; exit}' \
    | tr '[:upper:]' '[:lower:]')"
if [[ "$ACTUAL_SIGNER_SHA256" != "$EXPECTED_SIGNER_SHA256" ]]; then
    echo "La clave no corresponde a la firma oficial de Descarga Social." >&2
    exit 1
fi
"$ZIPALIGN" -c -p 4 "$SIGNED_APK"
mv -f "$SIGNED_APK" "$FINAL_APK"

cleanup
trap - EXIT
echo "APK creada: $FINAL_APK"
