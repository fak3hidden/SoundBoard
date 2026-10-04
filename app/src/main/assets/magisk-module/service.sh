#!/system/bin/sh
# Late-start service: the framework is up, so settings/pm/appops all work.

MODDIR=${0%/*}
PKG=com.questsoundboard

# Wait for the package manager to settle.
until [ "$(getprop sys.boot_completed)" = "1" ]; do
  sleep 2
done
sleep 5

# Reflection into android.media.audiopolicy.* is blocked by the hidden-API
# denylist on Android 10+. Unlock it for every app (cheapest reliable option on
# a headset that is already rooted).
settings put global hidden_api_policy 1
settings put global hidden_api_policy_pre_p_apps 1
settings put global hidden_api_policy_p_apps 1

# Belt and braces: grant explicitly in case the priv-app allowlist was ignored.
pm grant $PKG android.permission.MODIFY_AUDIO_ROUTING 2>/dev/null
pm grant $PKG android.permission.CAPTURE_AUDIO_OUTPUT 2>/dev/null
appops set $PKG PROJECT_MEDIA allow 2>/dev/null

# Keep the mixer alive while a game is in the foreground.
dumpsys deviceidle whitelist +$PKG 2>/dev/null
cmd appops set $PKG RUN_IN_BACKGROUND allow 2>/dev/null
cmd appops set $PKG RUN_ANY_IN_BACKGROUND allow 2>/dev/null

log -t QuestSoundboard "virtual mic module ready"
