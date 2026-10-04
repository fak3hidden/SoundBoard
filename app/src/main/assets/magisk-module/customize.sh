#!/system/bin/sh
# Flashable-zip entry point (Magisk Manager / recovery install).

SKIPUNZIP=0

ui_print "***********************************"
ui_print " Quest Soundboard — Virtual Mic"
ui_print "***********************************"

ABI=$(getprop ro.product.cpu.abi)
if [ "$ABI" != "arm64-v8a" ]; then
  ui_print "! Unsupported ABI: $ABI (expected arm64-v8a)"
  abort "! Aborting"
fi

DEVICE=$(getprop ro.product.device)
case "$DEVICE" in
  monterey|hollywood|eureka|panther|seacliff)
    ui_print "- Detected Meta headset: $DEVICE" ;;
  *)
    ui_print "- Warning: untested device '$DEVICE', continuing anyway" ;;
esac

if [ ! -f "$MODPATH/system/priv-app/QuestSoundboard/QuestSoundboard.apk" ]; then
  ui_print "! No APK bundled in this zip."
  ui_print "! Install the Quest Soundboard APK first, then use the"
  ui_print "! in-app 'Install module' button instead of flashing this."
  abort "! Aborting"
fi

ui_print "- Installing privileged app + permission allowlist"
touch "$MODPATH/needs_dex_clear"

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/system/priv-app/QuestSoundboard/QuestSoundboard.apk" 0 0 0644
set_perm "$MODPATH/system/etc/permissions/privapp-permissions-questsoundboard.xml" 0 0 0644

ui_print "- Done. Reboot to activate mic injection."
