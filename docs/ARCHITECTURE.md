# Architecture

## The problem

A VR game owns the foreground exclusively. Horizon OS backgrounds everything
else, and a backgrounded app gets no input events and, on Android 12L, loses
its audio focus. So a soundboard that works "in any game" needs to solve three
separate things:

1. **Stay alive and keep mixing** while another app owns the compositor.
2. **Get audio into the game's microphone capture**, not just the speakers.
3. **Be triggerable** without stealing input from the game.

The answers are: a foreground service, an `AudioPolicy` injector mix, and a
combination of raw `/dev/input` reading plus an HTTP panel on your phone.

---

## Module map

```
SoundboardApp
└── AppContainer                    one instance of each piece
    ├── RootManager                 privilege tier detection
    │   └── RootShell               su discovery, exec, long-lived streams
    ├── ModuleInstaller             writes /data/adb/modules from assets
    ├── MicInjector                 AudioPolicy injector mix (reflection)
    ├── SoundboardEngine            decode cache + polyphonic mixer + routing
    │   └── PcmDecoder              anything → 48 kHz stereo s16
    ├── SoundLibrary                /sdcard/Soundboard scan + per-clip metadata
    └── Settings                    SharedPreferences

SoundboardService (foreground, mediaPlayback)
├── HotkeyMonitor                   getevent -lq → global controller hotkeys
└── ControlServer                   HTTP + SSE on :8099, serves assets/web
```

---

## Audio path

```
file ──MediaExtractor+MediaCodec──► s16 PCM ──downmix──► stereo ──resample──► 48 kHz
                                                                                │
                                                                      decode cache (LRU, 192 MB)
                                                                                │
                                         ┌──────────── mixer thread (10 ms) ────┘
                                         │  sum ≤8 voices × per-clip gain × master
                                         │  fade-out on stop, hard clip, peak meter
                                         ▼
                           ┌─────────────┴─────────────┐
                           │                           │
                 MicInjector.write()           AudioTrack (monitor)
                 → AudioPolicy injector        → USAGE_MEDIA
                 → game's AudioRecord          → your ears
```

Everything is normalised to 48 kHz stereo s16 at decode time so the mixer is a
tight loop with no per-voice format branching. Buffers are 480 frames (10 ms),
which is the sweet spot between "pad feels instant" and "underruns on a busy
XR2".

**Why silence is written continuously:** an injector mix with no writer gets
reaped by the policy manager, and re-registering mid-game causes an audible
glitch in the game's voice stream. So the mixer always writes, even when idle.

**Fade-out on stop:** cutting a buffer mid-waveform produces a click that is
much more obvious over a compressed voice codec than it is locally. Stopping a
voice ramps it to zero over one buffer.

**Re-trigger behaviour:** firing a pad that is already playing restarts it
rather than stacking a second copy, which is what people expect from a
soundboard and also stops accidental double-taps from clipping the mix.

---

## Routing modes

`Route` decides which sinks the mixer writes to:

| Route | Sinks | Notes |
|---|---|---|
| `MIC_AND_MONITOR` | injector + `USAGE_MEDIA` track | monitor has its own volume so you can keep it quiet |
| `MIC` | injector only | |
| `MONITOR` | `USAGE_MEDIA` track | nothing leaves the headset |
| `ACOUSTIC` | `USAGE_VOICE_COMMUNICATION` track, speakerphone, `MODE_IN_COMMUNICATION` | no root; the real mic re-records the speakers |

Acoustic deliberately uses the voice-call stream: it resists being ducked by
the game and is routed to the loudest output, which maximises what the real mic
picks up.

If `MicInjector.start()` fails while a mic route is selected, the engine
downgrades to `ACOUSTIC` and surfaces the reason in the UI rather than silently
playing nothing.

---

## Privilege escalation chain

```
su exists?                    ──no──►  Tier.NONE     (acoustic only)
   │yes
   ▼
Tier.ROOT  ──► getevent hotkeys, deviceidle whitelist, module install
   │
   │ ModuleInstaller writes /data/adb/modules/questsoundboard_vmic
   │   • system/priv-app/QuestSoundboard/QuestSoundboard.apk
   │   • system/etc/permissions/privapp-permissions-questsoundboard.xml
   │   • service.sh: hidden_api_policy=1, pm grant, appops
   │ reboot
   ▼
Tier.PRIVILEGED  ──► MODIFY_AUDIO_ROUTING granted ──► AudioPolicy injection
```

The reflection in `MicInjector` is deliberately defensive: every failure mode
(`ClassNotFoundException` → hidden API still locked, `SecurityException` →
permission missing, `NoSuchMethodException` → different AOSP vintage) maps to a
distinct human-readable message, because "it doesn't work" on a rooted headset
is otherwise extremely hard to debug.

---

## Input

`getevent -lq` is spawned under `su` and its stdout parsed for `EV_KEY … DOWN`.
This is **observation only** — evdev delivers to all readers, so the game still
receives every button press normally. `KEY_HOME`, `KEY_POWER` and `BTN_MODE`
are ignored so the Horizon system UI is never fought over.

`BTN_THUMBL` (left stick click) is hard-wired to panic-stop.

Binding is done by arming `captureMode`, which routes the next press to the
control server instead of firing a clip.

---

## Control server

A ~400-line zero-dependency HTTP/1.1 server. Static assets come from
`assets/web`; state is pushed over SSE at 2.5 Hz, which is frequent enough for
the progress bars and level meter without flattening the battery.

Uploads are raw-body `POST /api/upload?name=&category=` rather than multipart —
the browser side already has `File.arrayBuffer()`, and it keeps the server
parser small. The library sanitises filenames and rejects unsupported
extensions.

The panel is deliberately a web app rather than a companion APK: it works from
a phone, a laptop, a tablet or a second headset, with nothing to install.

---

## Things deliberately not done

- **No OpenXR overlay.** Horizon OS has no public composition-layer overlay API
  for third-party apps, so an in-game visual soundboard is not possible without
  patching the runtime. The phone panel is the pragmatic answer.
- **No audio HAL replacement.** Swapping `audio.primary.*.so` would give
  injection without priv-app, but it is per-firmware, easy to bootloop, and
  gains nothing over the AudioPolicy route.
- **No TTS / voice changer.** Out of scope for v1; the mixer is the right place
  to add an effects chain later.
