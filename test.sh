#!/usr/bin/env bash
set -euo pipefail
umask 077

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"
JAVA_HOME="${JAVA_HOME:-/workspace/toolchains/jdk17}"
JSON_JAR="${DESCARGA_SOCIAL_JSON_JAR:-}"
ECJ_JAR="${DESCARGA_SOCIAL_ECJ_JAR:-}"
JAVAC="$JAVA_HOME/bin/javac"
JAVA="$JAVA_HOME/bin/java"

if [[ -z "$JSON_JAR" || ! -f "$JSON_JAR" ]]; then
    echo "Define DESCARGA_SOCIAL_JSON_JAR con una implementación org.json para escritorio." >&2
    exit 1
fi
if [[ ! -x "$JAVA" || ( -z "$ECJ_JAR" && ! -x "$JAVAC" ) ]]; then
    echo "No se encuentra el JDK configurado en JAVA_HOME." >&2
    exit 1
fi
if [[ -n "$ECJ_JAR" && ! -f "$ECJ_JAR" ]]; then
    echo "DESCARGA_SOCIAL_ECJ_JAR no corresponde a un archivo." >&2
    exit 1
fi

WORK_DIR="$(mktemp -d "$PROJECT_DIR/.test.XXXXXX")"
cleanup() {
    set +e
    find "$WORK_DIR" -depth -mindepth 1 -delete 2>/dev/null
    rmdir "$WORK_DIR" 2>/dev/null
}
trap cleanup EXIT

MAIN="$PROJECT_DIR/app/src/main/java/com/didazz/descargasocial"
TESTS="$PROJECT_DIR/tests/com/didazz/descargasocial"
TEST_CLASSPATH="$JSON_JAR:$PROJECT_DIR/vendor/runtime/*"
SOURCES=("$TESTS/TestResources.java" "$TESTS/LocalizationTest.java" "$MAIN/Messages.java" "$MAIN/ResourceKeys.java" 
    "$MAIN/StorageException.java" "$MAIN/DownloadDiagnostics.java"
    "$MAIN/YoutubeLink.java" "$MAIN/YoutubeException.java" "$MAIN/YoutubeQuality.java"
    "$MAIN/YoutubeRange.java" "$MAIN/YoutubeWorkFiles.java" "$MAIN/YoutubeHttp.java"
    "$MAIN/YoutubeTransfer.java" "$MAIN/YoutubeExtractor.java"
    "$MAIN/SocialPlatform.java"
    "$MAIN/MediaItem.java"
    "$MAIN/ExtractionResult.java"
    "$MAIN/SocialLinkParser.java"
    "$MAIN/PublicWebUrlPolicy.java"
    "$MAIN/MediaValidator.java"
    "$MAIN/DownloadControl.java"
    "$MAIN/DownloadPolicy.java"
    "$MAIN/DownloadQueue.java"
    "$MAIN/CompactLayout.java"
    "$MAIN/GenericExtractor.java"
    "$MAIN/XhamsterUrlDecoder.java"
    "$MAIN/GenericHlsDownloader.java"
    "$MAIN/GenericWebExtractor.java"
    "$MAIN/FacebookExtractor.java"
    "$TESTS/SocialLinkParserTest.java"
    "$TESTS/FacebookExtractorParserTest.java"
    "$TESTS/GenericExtractorParserTest.java"
    "$TESTS/XhamsterUrlDecoderTest.java"
    "$TESTS/GenericWebExtractorPolicyTest.java"
    "$TESTS/GenericHlsDownloaderTest.java"
    "$TESTS/MediaValidatorTest.java"
    "$TESTS/DownloadRuntimeTest.java"
    "$TESTS/DownloadQueueTest.java"
    "$TESTS/CompactLayoutTest.java"
    "$TESTS/YoutubeModuleTest.java" "$TESTS/YoutubeLiveSmoke.java"
    "$TESTS/DownloadDiagnosticsTest.java"
)

# ECJ does not expand classpath wildcards.
COMPILE_CLASSPATH="$JSON_JAR"
for jar in "$PROJECT_DIR"/vendor/runtime/*.jar; do COMPILE_CLASSPATH="$COMPILE_CLASSPATH:$jar"; done

if [[ -n "$ECJ_JAR" ]]; then
    "$JAVA" -jar "$ECJ_JAR" \
        -proc:none \
        -encoding UTF-8 \
        -source 8 \
        -target 8 \
        -classpath "$COMPILE_CLASSPATH" \
        -d "$WORK_DIR" \
        "${SOURCES[@]}"
else
    "$JAVAC" \
        -encoding UTF-8 \
        --release 8 \
        -classpath "$COMPILE_CLASSPATH" \
        -d "$WORK_DIR" \
        "${SOURCES[@]}"
fi

"$JAVA" -cp "$WORK_DIR:$JSON_JAR" com.didazz.descargasocial.SocialLinkParserTest
"$JAVA" -cp "$WORK_DIR:$JSON_JAR" com.didazz.descargasocial.FacebookExtractorParserTest
"$JAVA" -cp "$WORK_DIR:$JSON_JAR" com.didazz.descargasocial.GenericExtractorParserTest
"$JAVA" -cp "$WORK_DIR:$JSON_JAR" com.didazz.descargasocial.XhamsterUrlDecoderTest
"$JAVA" -cp "$WORK_DIR:$JSON_JAR" com.didazz.descargasocial.GenericWebExtractorPolicyTest
"$JAVA" -cp "$WORK_DIR:$JSON_JAR" com.didazz.descargasocial.GenericHlsDownloaderTest
"$JAVA" -cp "$WORK_DIR:$JSON_JAR" com.didazz.descargasocial.MediaValidatorTest
"$JAVA" -cp "$WORK_DIR:$JSON_JAR" com.didazz.descargasocial.DownloadRuntimeTest
"$JAVA" -cp "$WORK_DIR:$JSON_JAR" com.didazz.descargasocial.DownloadQueueTest
"$JAVA" -cp "$WORK_DIR:$JSON_JAR" com.didazz.descargasocial.CompactLayoutTest
"$JAVA" -cp "$WORK_DIR:$TEST_CLASSPATH" com.didazz.descargasocial.YoutubeModuleTest
"$JAVA" -cp "$WORK_DIR:$JSON_JAR" com.didazz.descargasocial.DownloadDiagnosticsTest

"$JAVA" -cp "$WORK_DIR:$JSON_JAR" com.didazz.descargasocial.LocalizationTest

# Run the actual Android storage class against isolated doubles, never packaged in the APK.
STORAGE_CLASSES="$WORK_DIR/storage"
mkdir -p "$STORAGE_CLASSES"
mapfile -d '' STORAGE_STUBS < <(find "$PROJECT_DIR/tests/storage-stubs" -name '*.java' -print0)
STORAGE_SOURCES=("$MAIN/Messages.java" "$MAIN/ResourceKeys.java" "${STORAGE_STUBS[@]}" "$MAIN/DownloadStorage.java" "$MAIN/DownloadControl.java"
    "$MAIN/StorageException.java" "$MAIN/DownloadDiagnostics.java" "$MAIN/DownloadPolicy.java" "$MAIN/YoutubeException.java"
    "$MAIN/MediaItem.java" "$MAIN/SocialPlatform.java" "$MAIN/MediaValidator.java" "$MAIN/YoutubeWorkFiles.java"
    "$TESTS/DownloadStorageConcurrencyTest.java")
if [[ -n "$ECJ_JAR" ]]; then
    "$JAVA" -jar "$ECJ_JAR" -proc:none -encoding UTF-8 -source 8 -target 8 \
        -d "$STORAGE_CLASSES" "${STORAGE_SOURCES[@]}"
else
    "$JAVAC" -encoding UTF-8 --release 8 -d "$STORAGE_CLASSES" "${STORAGE_SOURCES[@]}"
fi
"$JAVA" -cp "$STORAGE_CLASSES" com.didazz.descargasocial.DownloadStorageConcurrencyTest
bash "$PROJECT_DIR/test-mux.sh"
bash "$PROJECT_DIR/test-cache.sh"

# Explicit opt-in only: metadata of Blender Foundation's public test video, no user URLs.
if [[ "${1:-}" == "--live-youtube" ]]; then
    "$JAVA" -cp "$WORK_DIR:$TEST_CLASSPATH" com.didazz.descargasocial.YoutubeLiveSmoke
fi
