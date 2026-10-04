# Rooting a Meta Quest — what the app needs, and what it does without it

The soundboard checks for privileges at three tiers and adapts. You do **not**
have to root to use it; you have to root to get *clean* mic injection.

| Tier | Detected by | What unlocks |
|---|---|---|
| `NONE` | no `su` on the device | Acoustic mode, web panel, in-headset pads |
| `ROOT` | a working `su` returning uid 0 | + global controller hotkeys (`/dev/input`), module install, battery-optimisation whitelist |
| `PRIVILEGED` | `MODIFY_AUDIO_ROUTING` granted | + true microphone injection |

The app probes `su`, `/system/bin/su`, `/system/xbin/su`, `/sbin/su`,
`/debug_ramdisk/su` (Magisk 24+ on Android 10+), `/sbin/.magisk/busybox/su` and
`/data/adb/ksud` (KernelSU), so Magisk, KernelSU and APatch all work.

---

## Device reality check

Meta ships Horizon OS with a locked bootloader and verified boot. Rooting means
unlocking the bootloader, which wipes the device and is **not officially
supported on any Quest**.

### Quest 1 — `monterey`
Android 10, Snapdragon 835. The bootloader can be unlocked and Magisk patching
of `boot.img` works. This is the device the root path is most reliably exercised
on.

### Quest 2 — `hollywood`
Android 10 → Horizon OS (Android 12L), XR2. Unlock is possible on some units /
firmware revisions. Later firmware tightened things considerably. If you are on
a recent build, assume you cannot.

### Quest 3 — `eureka` / Quest 3S — `panther`
Horizon OS on Android 12L, XR2 Gen 2. Retail units ship locked with no public
unlock path. Unless something changes, these headsets run the app in `NONE` or
(for dev units) `ROOT` tier.

> **If you cannot root:** Acoustic mode genuinely works. It is noticeably
> lower quality — the headset mic re-records the speakers, so you get room
> tone and your own voice mixed in — but teammates hear your clips.

---

## The general procedure (Quest 1 / unlockable Quest 2)

1. **Enable developer mode** in the Meta mobile app, then USB debugging in the
   headset.
2. **Unlock the bootloader.** `adb reboot bootloader`, then
   `fastboot flashing unlock`. This factory-resets the headset.
3. **Pull the matching `boot.img`** for your exact firmware build
   (`adb shell getprop ro.build.display.id`). It has to match exactly.
4. **Patch it with Magisk**: install the Magisk APK on the headset (or a
   phone), *Install → Select and Patch a File*, pick `boot.img`.
5. **Flash**: `fastboot flash boot magisk_patched.img`, then `fastboot reboot`.
6. **Verify**: `adb shell su -c id` should print `uid=0(root)`.

Horizon OS updates re-flash `boot`, so expect to redo steps 3–5 after every
system update. Disabling auto-updates is advisable.

---

## Installing the virtual-mic module

You almost certainly do not need the flashable zip. Open the app, tap
**Install module**, then **Reboot now**. That path:

- writes `/data/adb/modules/questsoundboard_vmic/`
- copies the running APK into the module's `system/priv-app/QuestSoundboard/`
- installs `system/etc/permissions/privapp-permissions-questsoundboard.xml`
- flips `hidden_api_policy` to `1`
- flags the dalvik cache for a one-time clear in `post-fs-data.sh`

On reboot, Magisk overlays it onto `/system`, Android sees a privileged app
whose permissions are allowlisted, and `MODIFY_AUDIO_ROUTING` is granted.

For a recovery / Magisk Manager install instead:

```bash
./gradlew assembleRelease
./tools/build-module-zip.sh      # → dist/questsoundboard-vmic-1.0.0.zip
```

---

## Troubleshooting

**"root: none" but I have Magisk.**
Magisk's denylist may be hiding root from the app, or the superuser prompt was
missed. Open Magisk → Superuser and grant Quest Soundboard, and make sure it is
not in the denylist. Then tap **Re-check**.

**Module installed, rebooted, still "root: ROOT".**
The priv-app overlay did not take. Check:
```bash
adb shell su -c 'ls -l /system/priv-app/QuestSoundboard/'
adb shell dumpsys package com.questsoundboard | grep -A3 MODIFY_AUDIO_ROUTING
```
If the directory is missing, Magisk did not mount the module — confirm
`/data/adb/modules/questsoundboard_vmic/disable` does not exist. A common cause
is installing the module and *then* reinstalling the APK: the module ships a
copy of the old APK. Reinstall the module after any app update.

**"AudioPolicy API not reachable".**
`hidden_api_policy` is not set. `adb shell su -c 'settings put global
hidden_api_policy 1'` and restart the app.

**`registerAudioPolicy returned -1`.**
Another app already owns a conflicting audio policy (screen recorders and some
streaming apps do this), or the permission is not actually granted. Kill the
other app and re-check.

**Game hears nothing even though mic injection is ACTIVE.**
Some titles open the mic with `VOICE_RECOGNITION` or `CAMCORDER` rather than
`MIC`/`VOICE_COMMUNICATION`. Try `Mic + Me` versus `Mic only`, and confirm the
level meter in the panel moves when you fire a pad.

**Hotkeys do nothing.**
`getevent` needs root. Check with `adb shell su -c 'getevent -lq'` and press a
controller button — if nothing streams, the controllers are not exposed as
evdev devices on your firmware, and you should drive the board from the phone
panel instead.

**SELinux.**
The module does not need permissive mode. If `getevent` is being denied anyway,
`su -c setenforce 0` will tell you quickly whether SELinux is the cause — but
leaving the headset permissive is a bad idea, so prefer the phone panel.
