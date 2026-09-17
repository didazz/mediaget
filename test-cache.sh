#!/usr/bin/env bash
set -euo pipefail
umask 077
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"
JAVA_HOME="${JAVA_HOME:-/workspace/toolchains/jdk17}"
MAIN="$PROJECT_DIR/app/src/main/java/com/didazz/descargasocial"
SOURCE="${DESCARGA_SOCIAL_CACHE_BASELINE:-$MAIN/YoutubeWorkFiles.java}"
WORK="$(mktemp -d "$PROJECT_DIR/.test-cache.XXXXXX")"
cleanup(){ find "$WORK" -depth -mindepth 1 -delete; rmdir "$WORK"; }
trap cleanup EXIT
SOURCES=("$MAIN/Messages.java" "$MAIN/ResourceKeys.java" "$SOURCE" "$PROJECT_DIR/tests/com/didazz/descargasocial/YoutubeCacheAliasTest.java")
if [[ -f "$MAIN/StorageException.java" ]]; then SOURCES+=("$MAIN/StorageException.java"); fi
if [[ -n "${DESCARGA_SOCIAL_ECJ_JAR:-}" ]]; then
    "$JAVA_HOME/bin/java" -jar "$DESCARGA_SOCIAL_ECJ_JAR" -proc:none -encoding UTF-8 -source 8 -target 8 -d "$WORK" "${SOURCES[@]}"
else
    "$JAVA_HOME/bin/javac" -encoding UTF-8 --release 8 -d "$WORK" "${SOURCES[@]}"
fi
"$JAVA_HOME/bin/java" -cp "$WORK" com.didazz.descargasocial.YoutubeCacheAliasTest "${@}"
