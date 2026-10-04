package com.questsoundboard.root

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Minimal, dependency-free root shell.
 *
 * We deliberately do not rely on libsu alone: on Horizon OS some Magisk builds
 * ship `su` at non-standard paths, and KernelSU / APatch expose a different
 * binary. [SU_CANDIDATES] is probed in order and the first one that can execute
 * `id` and return uid 0 wins.
 */
object RootShell {

    private const val TAG = "RootShell"

    private val SU_CANDIDATES = listOf(
        "su",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/debug_ramdisk/su",      // Magisk 24+ on Android 10+
        "/sbin/.magisk/busybox/su",
        "/data/adb/ksud",          // KernelSU
        "/data/adb/magisk/busybox" // last resort
    )

    @Volatile
    private var resolvedSu: String? = null

    data class Result(
        val exitCode: Int,
        val stdout: List<String>,
        val stderr: List<String>
    ) {
        val ok: Boolean get() = exitCode == 0
        val out: String get() = stdout.joinToString("\n")
        val err: String get() = stderr.joinToString("\n")
    }

    /** Locates a working su binary, or null if the device is not rooted. */
    fun findSu(): String? {
        resolvedSu?.let { return it }
        for (candidate in SU_CANDIDATES) {
            try {
                val p = ProcessBuilder(candidate, "-c", "id -u").redirectErrorStream(true).start()
                if (!p.waitFor(4, TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                    continue
                }
                val uid = p.inputStream.bufferedReader().readText().trim()
                if (uid == "0") {
                    resolvedSu = candidate
                    Log.i(TAG, "root available via $candidate")
                    return candidate
                }
            } catch (_: Exception) {
                // candidate not present, keep probing
            }
        }
        return null
    }

    fun isRooted(): Boolean = findSu() != null

    /** Forget the cached su path (used when the user re-checks root). */
    fun invalidate() {
        resolvedSu = null
    }

    /**
     * Runs [commands] in a single root shell. Blocking — call from a worker
     * thread or use [execAsync].
     */
    fun exec(vararg commands: String): Result {
        val su = findSu() ?: return Result(-1, emptyList(), listOf("no su binary: device is not rooted"))
        return try {
            val process = ProcessBuilder(su).start()
            val stdout = ArrayList<String>()
            val stderr = ArrayList<String>()

            val outReader = Thread { drain(process.inputStream.bufferedReader(), stdout) }
            val errReader = Thread { drain(process.errorStream.bufferedReader(), stderr) }
            outReader.start(); errReader.start()

            DataOutputStream(process.outputStream).use { os ->
                for (cmd in commands) {
                    os.write((cmd + "\n").toByteArray())
                }
                os.write("exit\n".toByteArray())
                os.flush()
            }

            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return Result(-2, stdout, stderr + "timed out after 30s")
            }
            outReader.join(2000); errReader.join(2000)
            Result(process.exitValue(), stdout, stderr)
        } catch (e: Exception) {
            Log.e(TAG, "root exec failed", e)
            Result(-1, emptyList(), listOf(e.message ?: e.toString()))
        }
    }

    suspend fun execAsync(vararg commands: String): Result =
        withContext(Dispatchers.IO) { exec(*commands) }

    /** Starts a long-lived root process and streams its stdout to [onLine]. */
    fun stream(command: String, onLine: (String) -> Unit): Process? {
        val su = findSu() ?: return null
        return try {
            val process = ProcessBuilder(su, "-c", command)
                .redirectErrorStream(true)
                .start()
            Thread {
                try {
                    process.inputStream.bufferedReader().forEachLine(onLine)
                } catch (_: Exception) {
                    // process killed, expected on stop
                }
            }.apply { isDaemon = true }.start()
            process
        } catch (e: Exception) {
            Log.e(TAG, "stream failed for: $command", e)
            null
        }
    }

    private fun drain(reader: BufferedReader, into: MutableList<String>) {
        try {
            reader.forEachLine { into.add(it) }
        } catch (_: Exception) {
        }
    }

    /** Convenience: read a file as root (works on /data, /system, /dev). */
    fun readFile(path: String): String? {
        val r = exec("cat '$path' 2>/dev/null")
        return if (r.ok && r.stdout.isNotEmpty()) r.out else null
    }

    fun fileExists(path: String): Boolean = exec("test -e '$path' && echo yes").out.trim() == "yes"
}
