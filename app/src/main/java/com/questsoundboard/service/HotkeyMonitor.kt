package com.questsoundboard.service

import android.util.Log
import com.questsoundboard.root.RootShell

/**
 * A normal Android app never sees input while a VR game owns focus — the
 * runtime hands controller input straight to the foreground app.
 *
 * With root we can read the evdev stream directly (`getevent -l`), which gives
 * us global hotkeys that work *inside* any game without stealing input from it.
 * We only observe; events are never consumed, so the game still gets them.
 *
 * Typical Quest device names:
 *   "Oculus Touch (Left)" / "Oculus Touch (Right)" / "oculus_hmd_keys"
 * Bluetooth keyboards and gamepads show up here too, which is how people wire
 * up a stream-deck-ish setup.
 */
class HotkeyMonitor(
    private val onKeyDown: (key: String, device: String) -> Unit
) {

    companion object {
        private const val TAG = "HotkeyMonitor"

        /** Ignore the system/home button so we never fight the Horizon UI. */
        private val BLOCKED = setOf("KEY_HOME", "KEY_POWER", "BTN_MODE")
    }

    private var process: Process? = null

    @Volatile var isRunning = false
        private set

    @Volatile var lastKey: String? = null
        private set

    /** When true, the next key press is reported for binding instead of firing. */
    @Volatile var captureMode = false

    val available: Boolean get() = RootShell.isRooted()

    fun start(): Boolean {
        if (isRunning) return true
        if (!RootShell.isRooted()) {
            Log.w(TAG, "hotkeys need root")
            return false
        }
        // -l = label mode (symbolic names), -q = no device dump header
        process = RootShell.stream("getevent -lq") { line -> handleLine(line) }
        isRunning = process != null
        Log.i(TAG, if (isRunning) "global hotkeys active" else "failed to start getevent")
        return isRunning
    }

    fun stop() {
        runCatching { process?.destroy() }
        // getevent is spawned under su; make sure no orphan survives.
        if (RootShell.isRooted()) RootShell.exec("pkill -f 'getevent -lq' 2>/dev/null")
        process = null
        isRunning = false
    }

    /**
     * Lines look like:
     *   /dev/input/event4: EV_KEY       BTN_A                DOWN
     *   /dev/input/event4: EV_KEY       KEY_1                00000001
     */
    private fun handleLine(line: String) {
        try {
            if (!line.contains("EV_KEY")) return
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 4) return

            val device = parts[0].removeSuffix(":")
            val key = parts[2]
            val value = parts[3]

            val isDown = value.equals("DOWN", true) || value == "00000001"
            if (!isDown) return
            if (key in BLOCKED) return

            lastKey = key
            if (captureMode) {
                captureMode = false
                onKeyDown("__CAPTURE__:$key", device)
            } else {
                onKeyDown(key, device)
            }
        } catch (e: Exception) {
            Log.w(TAG, "bad evdev line: $line", e)
        }
    }

    /** Lists input devices so the UI can tell the user what it can bind to. */
    fun listDevices(): List<String> {
        if (!RootShell.isRooted()) return emptyList()
        val r = RootShell.exec("getevent -pl 2>/dev/null | grep -E 'add device|name:'")
        val devices = ArrayList<String>()
        var current = ""
        for (line in r.stdout) {
            when {
                line.contains("add device") -> current = line.substringAfter(": ").trim()
                line.contains("name:") -> {
                    val name = line.substringAfter("name:").trim().trim('"')
                    devices.add("$name  ($current)")
                }
            }
        }
        return devices
    }
}
