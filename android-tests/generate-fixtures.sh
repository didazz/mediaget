#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)/src/assets"
mkdir -p "$ROOT"
ffmpeg -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=160x90:rate=24' -t 2 -an -c:v libx264 -pix_fmt yuv420p -y "$ROOT/video.mp4"
ffmpeg -hide_banner -loglevel error -f lavfi -i 'sine=frequency=440:sample_rate=44100' -t 2 -vn -c:a aac -y "$ROOT/audio-negative.m4a"
ffmpeg -hide_banner -loglevel error -f lavfi -i 'sine=frequency=440:sample_rate=44100' -t 2 -vn -c:a aac -avoid_negative_ts make_zero -use_editlist 0 -y "$ROOT/audio.m4a"
