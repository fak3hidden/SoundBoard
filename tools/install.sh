#!/usr/bin/env bash
# One-shot sideload helper: build, install, push sounds, open the panel URL.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "== pre-flight check =="
python3 tools/precompile.py

echo "== building =="
./gradlew assembleRelease

APK=$(find app/build/outputs/apk/release -name '*.apk' | head -n1)
echo "== installing $APK =="
adb install -r "$APK"

echo "== creating sound folders on the headset =="
adb shell mkdir -p /sdcard/Soundboard/Memes /sdcard/Soundboard/"Voice Lines" \
                   /sdcard/Soundboard/Music /sdcard/Soundboard/Stingers

if [ -d "${1:-}" ]; then
  echo "== pushing clips from $1 =="
  adb push "$1"/. /sdcard/Soundboard/
fi

echo "== granting what we can without root =="
adb shell pm grant com.questsoundboard android.permission.RECORD_AUDIO || true
adb shell appops set com.questsoundboard MANAGE_EXTERNAL_STORAGE allow || true

echo "== done. Launch 'Quest Soundboard' from Unknown Sources. =="
adb shell am start -n com.questsoundboard/.ui.MainActivity
