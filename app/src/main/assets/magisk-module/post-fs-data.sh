#!/system/bin/sh
# Runs before the Android framework starts — the only safe point to stage
# /system overlays and clear stale dex caches for the priv-app copy.

MODDIR=${0%/*}
PKG=com.questsoundboard

# Make sure the overlaid APK is readable by the system.
APK="$MODDIR/system/priv-app/QuestSoundboard/QuestSoundboard.apk"
if [ -f "$APK" ]; then
  chmod 0644 "$APK"
  chown 0:0 "$APK"
  [ -x /system/bin/restorecon ] && restorecon "$APK"
fi

PERMS="$MODDIR/system/etc/permissions/privapp-permissions-questsoundboard.xml"
if [ -f "$PERMS" ]; then
  chmod 0644 "$PERMS"
  chown 0:0 "$PERMS"
  [ -x /system/bin/restorecon ] && restorecon "$PERMS"
fi

# A stale optimised dex from the old (non-privileged) install location makes the
# framework skip the priv-app copy. Drop it once, right after install.
if [ -f "$MODDIR/needs_dex_clear" ]; then
  rm -rf /data/dalvik-cache/arm64/*questsoundboard* 2>/dev/null
  rm -f "$MODDIR/needs_dex_clear"
fi
