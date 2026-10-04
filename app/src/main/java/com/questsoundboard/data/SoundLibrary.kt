package com.questsoundboard.data

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Sound(
    val id: String,
    val name: String,
    val path: String,
    val category: String,
    val sizeBytes: Long,
    var volume: Float = 1.0f,
    var loop: Boolean = false,
    /** Controller/keyboard hotkey, e.g. "BTN_A" or "KEY_1". Null = none. */
    var hotkey: String? = null,
    /** 1-based pad slot on the quick-fire grid, 0 = unassigned. */
    var pad: Int = 0,
    var durationMs: Long = 0
) {
    val file: File get() = File(path)
    val extension: String get() = name.substringAfterLast('.', "").lowercase()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("path", path); put("category", category)
        put("sizeBytes", sizeBytes); put("volume", volume.toDouble()); put("loop", loop)
        put("hotkey", hotkey ?: JSONObject.NULL); put("pad", pad); put("durationMs", durationMs)
    }
}

/**
 * The user supplies their own audio. We watch one plain folder on the headset
 * so people can just `adb push` files or drop them in over MTP / SideQuest:
 *
 *     /sdcard/Soundboard/<category>/<clip>.mp3
 *
 * Files directly in the root folder land in the "General" category.
 */
class SoundLibrary(private val context: Context) {

    companion object {
        private const val TAG = "SoundLibrary"
        val SUPPORTED = setOf("mp3", "wav", "ogg", "oga", "opus", "m4a", "aac", "flac", "mp4", "mkv")

        fun rootDir(): File =
            File(Environment.getExternalStorageDirectory(), "Soundboard")
    }

    private val metaFile = File(context.filesDir, "sound_meta.json")

    private val _sounds = MutableStateFlow<List<Sound>>(emptyList())
    val sounds: StateFlow<List<Sound>> = _sounds

    private val _categories = MutableStateFlow<List<String>>(emptyList())
    val categories: StateFlow<List<String>> = _categories

    @Volatile var lastScanError: String? = null
        private set

    /** Creates the folder tree + a README the first time the app runs. */
    fun ensureFolders() {
        try {
            val root = rootDir()
            if (!root.exists()) {
                root.mkdirs()
                listOf("Memes", "Voice Lines", "Music", "Stingers").forEach {
                    File(root, it).mkdirs()
                }
            }
            val readme = File(root, "README.txt")
            if (!readme.exists()) {
                readme.writeText(
                    """
                    Quest Soundboard — drop your own audio files in here.

                    Any folder in this directory becomes a category/tab in the app.
                    Files sitting loose in this folder show up under "General".

                    Supported: mp3, wav, ogg, opus, m4a, aac, flac

                    Getting files onto the headset:
                      adb push mysound.mp3 /sdcard/Soundboard/Memes/
                    or just drag them in over USB (MTP) / SideQuest file manager.

                    Hit "Rescan" in the app (or the web panel) after adding files.
                    """.trimIndent()
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not create folders", e)
        }
    }

    suspend fun scan(): List<Sound> = withContext(Dispatchers.IO) {
        ensureFolders()
        val root = rootDir()
        val meta = loadMeta()
        val found = ArrayList<Sound>()

        if (!root.exists() || !root.canRead()) {
            lastScanError = "Cannot read ${root.absolutePath}. Grant storage access (or 'All files access') to the app."
            _sounds.value = emptyList()
            return@withContext emptyList()
        }
        lastScanError = null

        fun visit(dir: File, category: String, depth: Int) {
            if (depth > 3) return
            val children = dir.listFiles() ?: return
            for (f in children.sortedBy { it.name.lowercase() }) {
                if (f.isDirectory) {
                    visit(f, if (depth == 0) f.name else "$category/${f.name}", depth + 1)
                } else if (f.extension.lowercase() in SUPPORTED) {
                    val key = f.absolutePath
                    val saved = meta.optJSONObject(key)
                    found.add(
                        Sound(
                            id = saved?.optString("id")?.takeIf { it.isNotBlank() }
                                ?: UUID.nameUUIDFromBytes(key.toByteArray()).toString(),
                            name = f.nameWithoutExtension,
                            path = key,
                            category = category,
                            sizeBytes = f.length(),
                            volume = saved?.optDouble("volume", 1.0)?.toFloat() ?: 1.0f,
                            loop = saved?.optBoolean("loop", false) ?: false,
                            hotkey = saved?.optString("hotkey")?.takeIf {
                                it.isNotBlank() && it != "null"
                            },
                            pad = saved?.optInt("pad", 0) ?: 0,
                            durationMs = saved?.optLong("durationMs", 0L) ?: 0L
                        )
                    )
                }
            }
        }

        visit(root, "General", 0)

        _sounds.value = found
        _categories.value = found.map { it.category }.distinct().sorted()
        Log.i(TAG, "scanned ${found.size} sounds in ${_categories.value.size} categories")
        found
    }

    fun byId(id: String): Sound? = _sounds.value.firstOrNull { it.id == id }

    fun byPad(pad: Int): Sound? = _sounds.value.firstOrNull { it.pad == pad && pad > 0 }

    fun byHotkey(key: String): List<Sound> = _sounds.value.filter { it.hotkey == key }

    suspend fun update(id: String, mutate: (Sound) -> Unit) = withContext(Dispatchers.IO) {
        val sound = byId(id) ?: return@withContext
        mutate(sound)
        _sounds.value = _sounds.value.toList() // trigger recomposition
        saveMeta()
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        val sound = byId(id) ?: return@withContext false
        val ok = runCatching { sound.file.delete() }.getOrDefault(false)
        if (ok) scan()
        ok
    }

    /** Used by the web panel's drag-and-drop upload. */
    suspend fun import(fileName: String, category: String, bytes: ByteArray): Sound? =
        withContext(Dispatchers.IO) {
            val safeName = fileName.replace(Regex("[^A-Za-z0-9._ \\-()]"), "_").take(120)
            if (safeName.substringAfterLast('.', "").lowercase() !in SUPPORTED) return@withContext null
            val dir = if (category.isBlank() || category == "General") rootDir()
            else File(rootDir(), category).apply { mkdirs() }
            val target = File(dir, safeName)
            target.writeBytes(bytes)
            scan()
            _sounds.value.firstOrNull { it.path == target.absolutePath }
        }

    private fun loadMeta(): JSONObject = try {
        if (metaFile.exists()) JSONObject(metaFile.readText()) else JSONObject()
    } catch (_: Exception) {
        JSONObject()
    }

    fun saveMeta() {
        try {
            val obj = JSONObject()
            for (s in _sounds.value) obj.put(s.path, s.toJson())
            metaFile.writeText(obj.toString())
        } catch (e: Exception) {
            Log.w(TAG, "could not persist metadata", e)
        }
    }
}
