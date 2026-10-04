package com.questsoundboard

import android.app.Application
import com.questsoundboard.audio.MicInjector
import com.questsoundboard.audio.SoundboardEngine
import com.questsoundboard.data.SoundLibrary
import com.questsoundboard.root.RootManager

class SoundboardApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Dirt-simple manual DI — one instance of each piece, shared app-wide. */
class AppContainer(app: Application) {
    val rootManager = RootManager(app)
    val micInjector = MicInjector(app)
    val engine = SoundboardEngine(app, micInjector)
    val library = SoundLibrary(app)
    val settings = Settings(app)
}

/** Small typed wrapper over SharedPreferences. */
class Settings(app: Application) {
    private val prefs = app.getSharedPreferences("soundboard", android.content.Context.MODE_PRIVATE)

    var route: String
        get() = prefs.getString("route", "MIC_AND_MONITOR")!!
        set(v) = prefs.edit().putString("route", v).apply()

    var masterVolume: Float
        get() = prefs.getFloat("masterVolume", 0.9f)
        set(v) = prefs.edit().putFloat("masterVolume", v).apply()

    var monitorVolume: Float
        get() = prefs.getFloat("monitorVolume", 0.6f)
        set(v) = prefs.edit().putFloat("monitorVolume", v).apply()

    var webPanelEnabled: Boolean
        get() = prefs.getBoolean("webPanel", true)
        set(v) = prefs.edit().putBoolean("webPanel", v).apply()

    var webPanelPort: Int
        get() = prefs.getInt("webPort", 8099)
        set(v) = prefs.edit().putInt("webPort", v).apply()

    var hotkeysEnabled: Boolean
        get() = prefs.getBoolean("hotkeys", true)
        set(v) = prefs.edit().putBoolean("hotkeys", v).apply()

    var startOnBoot: Boolean
        get() = prefs.getBoolean("startOnBoot", false)
        set(v) = prefs.edit().putBoolean("startOnBoot", v).apply()
}
