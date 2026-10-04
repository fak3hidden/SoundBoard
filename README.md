# Quest Soundboard

A root-powered soundboard for Meta Quest 1, 2, 3 and 3S. You supply your own
audio files; the app injects them into the **microphone** so anyone in the game
hears them — VRChat, Rec Room, Gorilla Tag, Phasmophobia, Among Us VR, Echo VR,
or any other app that records from the mic.

Drive it from the headset, or from your phone's browser while you stay in the
game.

```
   your clips  ──►  decoder  ──►  48 kHz mixer  ──┬──►  virtual mic  ──►  the game
 /sdcard/Soundboard                               └──►  your ears (monitor)
```

---

## What you get

| | |
|---|---|
| **Your own files** | Drop anything into `/sdcard/Soundboard/`. Sub-folders become tabs. mp3, wav, ogg, opus, m4a, aac, flac. |
| **Real mic injection** | Registers an Android `AudioPolicy` *injector* mix, so games read your audio as microphone input. Needs root + the bundled Magisk module. |
| **No-root fallback** | "Acoustic" mode plays loud through the headset speakers so the real mic picks it up. Works on a stock headset. |
| **Phone control panel** | The app serves a web UI on the headset. Open `http://<headset-ip>:8099` on your phone and tap pads without leaving VR. |
| **Global controller hotkeys** | With root the app reads `/dev/input` directly, so a bound Touch-controller button fires a clip *while a game has focus*, without stealing the input from the game. |
| **Polyphonic mixer** | Up to 8 clips at once, per-clip volume, looping, 10 ms fade-out, hard-clip protection, live level meter. |
| **Panic stop** | Left thumbstick click, `Esc` in the browser, or the big red button. |

---

## Quick start

```bash
# 1. build + sideload (headset in developer mode, USB connected)
./tools/install.sh

# 2. put your own audio on the headset
adb push ./my-sounds/. /sdcard/Soundboard/

# 3. launch "Quest Soundboard" from Unknown Sources in your library
```

In the app: **Install module → Reboot now**. After the reboot the privilege chip
turns green and `Mic + Me` becomes selectable.

Then grab the URL the app shows (e.g. `http://192.168.1.42:8099`), open it on
your phone, put the headset on, and start a game.

### Preview the control panel without a headset

```bash
python3 tools/mock_panel.py --port 8099   # then open http://localhost:8099
```

It serves the real UI against a simulated backend. `--tier ROOT` / `--tier NONE`
let you see the no-root states.

---

## Routing modes

| Mode | Who hears it | Requires |
|---|---|---|
| **Mic + Me** | teammates **and** you | root + module |
| **Mic only** | teammates only | root + module |
| **Me only** | just you (private test) | nothing |
| **Acoustic** | teammates, via the real mic picking up the speakers | nothing |

The app degrades gracefully: if you pick a mic mode and injection is not
available, it falls back to Acoustic and tells you why.

---

## Root requirements per headset

| Headset | Status |
|---|---|
| Quest 1 (`monterey`) | Bootloader unlockable, Magisk well supported. Easiest target. |
| Quest 2 (`hollywood`) | Rootable on older firmware / with an unlocked bootloader. |
| Quest 3 (`eureka`) | Needs an unlocked bootloader; not available on retail units out of the box. |
| Quest 3S (`panther`) | Same as Quest 3. |

See [`docs/ROOTING.md`](docs/ROOTING.md) for the detail, and for what still
works if you cannot root.

> Rooting a Quest voids the warranty, can get the device flagged, and a bad
> flash can brick it. The app never roots anything for you — it only detects an
> existing `su` and asks before touching `/data/adb/modules`.

---

## How mic injection actually works

1. The Magisk module copies the APK to `/system/priv-app` and installs a
   `privapp-permissions` allowlist, so Android grants the app
   `MODIFY_AUDIO_ROUTING` (signature|privileged).
2. `service.sh` sets `hidden_api_policy = 1`, because the
   `android.media.audiopolicy.*` classes are on the hidden-API denylist.
3. At runtime `MicInjector` reflects into `AudioPolicy.Builder`, registers an
   `AudioMix` with `MIX_ROLE_INJECTOR` matching
   `USAGE_VOICE_COMMUNICATION`, and gets back an `AudioTrack`.
4. The mixer writes 10 ms stereo buffers into that track. The audio policy
   manager feeds them into the capture path, so every `AudioRecord` in the
   system — i.e. the game's voice chat — reads your clip as mic input.

Silence is written continuously when nothing is playing, so the injected stream
never underruns and gets torn down.

Full write-up: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

---

## Layout

```
app/
  src/main/java/com/questsoundboard/
    root/        RootShell, RootManager, ModuleInstaller
    audio/       PcmDecoder, SoundboardEngine (mixer), MicInjector
    data/        SoundLibrary (your files + per-clip settings)
    service/     SoundboardService (foreground), HotkeyMonitor (/dev/input)
    net/         ControlServer (HTTP + SSE)
    ui/          MainActivity (Compose, 2D panel app)
  src/main/assets/
    web/           phone/PC control panel
    magisk-module/ the virtual-mic module, installed from inside the app
tools/
  install.sh           build + sideload + push clips
  build-module-zip.sh  flashable zip for Magisk Manager
  mock_panel.py        offline preview of the web UI
docs/
```

---

## Building

Needs JDK 17 and the Android SDK (API 34 + build-tools 34).

```bash
gradle wrapper          # first time only, to generate gradlew
./gradlew assembleRelease
```

The release build is signed with a throwaway keystore so `adb install` works.
Generate one with:

```bash
keytool -genkey -v -keystore app/sideload.keystore -alias soundboard \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass soundboard -keypass soundboard -dname "CN=Quest Soundboard"
```

---

## Be decent about it

Mic injection is indistinguishable from your voice to the game, which means
it is also indistinguishable to the people you are playing with. Most online
games' terms of service prohibit audio harassment, and several explicitly ban
soundboard spam. Use it with friends who are in on it. The panic stop exists
for a reason.
