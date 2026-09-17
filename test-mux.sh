#!/usr/bin/env bash
set -euo pipefail
umask 077
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"
JAVA_HOME="${JAVA_HOME:-/workspace/toolchains/jdk17}"
ECJ_JAR="${DESCARGA_SOCIAL_ECJ_JAR:-}"
MAIN="$PROJECT_DIR/app/src/main/java/com/didazz/descargasocial"
# Optional, explicit old-source path to reproduce 1.4.0 with the same test contract.
MUX_SOURCE="${DESCARGA_SOCIAL_MUX_BASELINE:-$MAIN/YoutubeDownload.java}"
WORK="$(mktemp -d "$PROJECT_DIR/.test-mux.XXXXXX")"
cleanup(){ find "$WORK" -depth -mindepth 1 -delete; rmdir "$WORK"; }
trap cleanup EXIT
mapfile -d '' STUBS < <(find "$PROJECT_DIR/tests/mux-stubs" -name '*.java' -print0)
SOURCES=("$PROJECT_DIR/tests/com/didazz/descargasocial/TestResources.java" "$MAIN/Messages.java" "$MAIN/ResourceKeys.java" "${STUBS[@]}" "$MUX_SOURCE" "$MAIN/YoutubeQuality.java" "$MAIN/YoutubeException.java"
    "$MAIN/StorageException.java" "$MAIN/DownloadDiagnostics.java"
    "$MAIN/DownloadControl.java" "$MAIN/DownloadPolicy.java" "$MAIN/MediaValidator.java"
    "$MAIN/MediaItem.java" "$MAIN/SocialPlatform.java"
    "$PROJECT_DIR/tests/com/didazz/descargasocial/YoutubeMuxContractTest.java")
if [[ -n "$ECJ_JAR" ]]; then
    "$JAVA_HOME/bin/java" -jar "$ECJ_JAR" -proc:none -encoding UTF-8 -source 8 -target 8 -d "$WORK" "${SOURCES[@]}"
else
    "$JAVA_HOME/bin/javac" -encoding UTF-8 --release 8 -d "$WORK" "${SOURCES[@]}"
fi
"$JAVA_HOME/bin/java" -cp "$WORK" com.didazz.descargasocial.YoutubeMuxContractTest "${@}"
