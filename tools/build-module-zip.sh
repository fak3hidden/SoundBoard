#!/usr/bin/env bash
# Packages the Magisk module (plus a freshly built APK, if present) into a
# flashable zip. Normally you do not need this — the app installs the module
# itself — but it is handy for recovery/Magisk Manager installs.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/app/src/main/assets/magisk-module"
OUT="$ROOT/dist"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

cp -r "$SRC"/* "$STAGE/"

APK=$(find "$ROOT/app/build/outputs/apk" -name '*.apk' 2>/dev/null | head -n1 || true)
if [ -n "$APK" ]; then
  mkdir -p "$STAGE/system/priv-app/QuestSoundboard"
  cp "$APK" "$STAGE/system/priv-app/QuestSoundboard/QuestSoundboard.apk"
  echo "-> bundled $(basename "$APK")"
else
  echo "-> no APK found; build one first with ./gradlew assembleRelease"
fi

mkdir -p "$STAGE/system/etc/permissions"
cp "$STAGE/common/privapp-permissions-questsoundboard.xml" \
   "$STAGE/system/etc/permissions/privapp-permissions-questsoundboard.xml"

mkdir -p "$OUT"
ZIP="$OUT/questsoundboard-vmic-$(grep '^version=' "$SRC/module.prop" | cut -d= -f2).zip"
rm -f "$ZIP"
(cd "$STAGE" && zip -qr "$ZIP" .)
echo "-> $ZIP"
