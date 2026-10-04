#!/usr/bin/env python3
"""
Offline preview of the Quest Soundboard web control panel.

Serves the exact same assets the headset app serves (app/src/main/assets/web)
and fakes the /api surface so the UI can be developed and reviewed without a
rooted headset. Playback is simulated with a clock.

    python3 tools/mock_panel.py --port 8099
"""

import argparse
import json
import mimetypes
import os
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs, unquote

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                    "app", "src", "main", "assets", "web")

SAMPLE = [
    ("Airhorn", "Memes", 0.3, 1),
    ("Bruh", "Memes", 0.9, 2),
    ("Windows XP Error", "Memes", 1.4, 3),
    ("Vine Boom", "Memes", 1.1, 4),
    ("Among Us Roundstart", "Memes", 2.6, 0),
    ("Metal Pipe Falling", "Memes", 2.3, 5),
    ("Nice Shot", "Voice Lines", 1.0, 6),
    ("Behind You", "Voice Lines", 1.2, 0),
    ("Regroup On Me", "Voice Lines", 1.6, 0),
    ("Enemy Spotted", "Voice Lines", 1.3, 0),
    ("Elevator Jazz", "Music", 48.0, 7),
    ("Victory Fanfare", "Music", 6.5, 8),
    ("Lo-fi Loop", "Music", 92.0, 0),
    ("Dramatic Sting", "Stingers", 2.0, 9),
    ("Record Scratch", "Stingers", 1.7, 10),
    ("Crickets", "Stingers", 4.1, 0),
]


class State:
    def __init__(self):
        self.lock = threading.Lock()
        self.sounds = []
        for name, cat, dur, pad in SAMPLE:
            self.sounds.append({
                "id": str(uuid.uuid5(uuid.NAMESPACE_DNS, name)),
                "name": name,
                "path": f"/sdcard/Soundboard/{cat}/{name}.mp3",
                "category": cat,
                "sizeBytes": int(dur * 160_000),
                "volume": 1.0,
                "loop": "Loop" in name,
                "hotkey": None,
                "pad": pad,
                "durationMs": int(dur * 1000),
            })
        self.playing = {}        # soundId -> {start, duration, loop, name}
        self.route = "MIC_AND_MONITOR"
        self.master = 0.9
        self.monitor = 0.6
        self.tier = "PRIVILEGED"
        self.captured = None

    def by_id(self, sid):
        return next((s for s in self.sounds if s["id"] == sid), None)

    def snapshot(self):
        with self.lock:
            now = time.time()
            playing = []
            peak = 0.0
            for sid, p in list(self.playing.items()):
                elapsed = (now - p["start"]) * 1000
                if elapsed > p["duration"]:
                    if p["loop"]:
                        p["start"] = now
                        elapsed = 0
                    else:
                        del self.playing[sid]
                        continue
                playing.append({
                    "voiceId": p["voiceId"], "soundId": sid, "name": p["name"],
                    "positionMs": int(elapsed), "durationMs": p["duration"],
                    "loop": p["loop"],
                })
                # fake a believable level meter
                peak = max(peak, 0.35 + 0.45 * abs(((now * 7) % 2) - 1))

            root_tiers = {
                "PRIVILEGED": dict(rooted=True, canInjectMic=True, moduleInstalled=True),
                "ROOT": dict(rooted=True, canInjectMic=False, moduleInstalled=False),
                "NONE": dict(rooted=False, canInjectMic=False, moduleInstalled=False),
            }[self.tier]

            return {
                "ok": True,
                "sounds": self.sounds,
                "categories": sorted({s["category"] for s in self.sounds}),
                "playing": playing,
                "route": self.route,
                "masterVolume": self.master,
                "monitorVolume": self.monitor,
                "peak": round(peak * self.master, 3),
                "engineRunning": True,
                "libraryError": None,
                "root": {
                    "tier": self.tier, "provider": "Magisk", "magisk": "27.0",
                    "hiddenApiUnlocked": True, "selinuxEnforcing": False,
                    "device": "Meta Quest 3", "android": "12", **root_tiers,
                },
                "micInjector": {
                    "state": "ACTIVE" if self.tier == "PRIVILEGED" else "UNAVAILABLE",
                    "error": None if self.tier == "PRIVILEGED"
                             else "Denied MODIFY_AUDIO_ROUTING — install the Magisk module and reboot.",
                },
            }


STATE = State()
VOICE_ID = [0]


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    # ----------------------------------------------------------------- routes

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/api/state":
            return self.json(STATE.snapshot())
        if path == "/api/events":
            return self.events()
        return self.static(path)

    def do_DELETE(self):
        path = urlparse(self.path).path
        if path.startswith("/api/sound/"):
            sid = unquote(path.rsplit("/", 1)[-1])
            with STATE.lock:
                STATE.sounds = [s for s in STATE.sounds if s["id"] != sid]
            return self.json({"ok": True, "deleted": True})
        return self.json({"ok": False})

    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path
        q = {k: v[0] for k, v in parse_qs(parsed.query).items()}
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else b""

        if path.startswith("/api/play/"):
            sid = unquote(path.rsplit("/", 1)[-1])
            s = STATE.by_id(sid)
            if s:
                VOICE_ID[0] += 1
                with STATE.lock:
                    STATE.playing[sid] = {
                        "start": time.time(), "duration": s["durationMs"],
                        "loop": s["loop"], "name": s["name"], "voiceId": VOICE_ID[0],
                    }
            return self.json({"ok": True})

        if path.startswith("/api/stop/"):
            sid = unquote(path.rsplit("/", 1)[-1])
            with STATE.lock:
                STATE.playing.pop(sid, None)
            return self.json({"ok": True})

        if path == "/api/stopall":
            with STATE.lock:
                STATE.playing.clear()
            return self.json({"ok": True})

        if path == "/api/rescan":
            return self.json({"ok": True, "count": len(STATE.sounds)})

        if path == "/api/route":
            STATE.route = q.get("value", STATE.route)
            return self.json({"ok": True, "route": STATE.route})

        if path == "/api/volume":
            if "master" in q:
                STATE.master = float(q["master"])
            if "monitor" in q:
                STATE.monitor = float(q["monitor"])
            return self.json({"ok": True})

        if path.startswith("/api/sound/"):
            sid = unquote(path.rsplit("/", 1)[-1])
            s = STATE.by_id(sid)
            if s:
                if "volume" in q:
                    s["volume"] = float(q["volume"])
                if "loop" in q:
                    s["loop"] = q["loop"] == "true"
                if "pad" in q:
                    s["pad"] = int(q["pad"])
                if "hotkey" in q:
                    s["hotkey"] = None if q["hotkey"] in ("none", "") else q["hotkey"]
            return self.json({"ok": True})

        if path == "/api/upload":
            name = q.get("name", "clip.mp3")
            STATE.sounds.append({
                "id": str(uuid.uuid4()),
                "name": os.path.splitext(name)[0],
                "path": "/sdcard/Soundboard/" + name,
                "category": q.get("category", "General"),
                "sizeBytes": len(body), "volume": 1.0, "loop": False,
                "hotkey": None, "pad": 0, "durationMs": 3000,
            })
            return self.json({"ok": True})

        if path == "/api/hotkey/capture":
            STATE.captured = None
            threading.Timer(1.2, lambda: setattr(STATE, "captured", "BTN_A")).start()
            return self.json({"ok": True})

        if path == "/api/hotkey/captured":
            return self.json({"ok": True, "key": STATE.captured})

        if path == "/api/root/refresh":
            return self.json({"ok": True})

        return self.json({"ok": False, "error": "unknown endpoint"})

    # ------------------------------------------------------------- transport

    def json(self, obj):
        body = json.dumps(obj).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def events(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        try:
            while True:
                payload = json.dumps(STATE.snapshot())
                self.wfile.write(f"data: {payload}\n\n".encode())
                self.wfile.flush()
                time.sleep(0.4)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def static(self, path):
        rel = "index.html" if path in ("/", "") else path.lstrip("/")
        full = os.path.normpath(os.path.join(ROOT, rel))
        if not full.startswith(os.path.normpath(ROOT)) or not os.path.isfile(full):
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        with open(full, "rb") as f:
            body = f.read()
        self.send_response(200)
        self.send_header("Content-Type", mimetypes.guess_type(full)[0] or "application/octet-stream")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8099)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--tier", default="PRIVILEGED", choices=["PRIVILEGED", "ROOT", "NONE"])
    args = ap.parse_args()
    STATE.tier = args.tier
    print(f"Soundboard panel preview → http://{args.host}:{args.port}")
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()
