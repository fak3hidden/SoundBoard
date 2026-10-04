package com.questsoundboard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.questsoundboard.AppContainer
import com.questsoundboard.R
import com.questsoundboard.SoundboardApp
import com.questsoundboard.audio.Route
import com.questsoundboard.net.ControlServer
import com.questsoundboard.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the mixer, the global hotkey listener and the phone/PC control server
 * alive while the user is inside a game. Without this everything would be torn
 * down the moment Horizon OS backgrounds the panel app.
 */
class SoundboardService : Service() {

    companion object {
        private const val TAG = "SoundboardService"
        private const val CHANNEL_ID = "soundboard"
        private const val NOTIFICATION_ID = 7341

        const val ACTION_START = "com.questsoundboard.START"
        const val ACTION_STOP = "com.questsoundboard.STOP"
        const val ACTION_PANIC = "com.questsoundboard.PANIC"

        @Volatile var isRunning = false
            private set

        @Volatile var controlServer: ControlServer? = null
            private set

        @Volatile var hotkeys: HotkeyMonitor? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, SoundboardService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SoundboardService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var container: AppContainer
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        container = (application as SoundboardApp).container
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PANIC -> {
                container.engine.stopAll()
                return START_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification("Soundboard ready"))
        isRunning = true
        acquireWakeLock()
        bootstrap()
        return START_STICKY
    }

    private fun bootstrap() = scope.launch {
        val settings = container.settings
        val engine = container.engine

        container.rootManager.refresh()
        container.rootManager.whitelistFromBatteryOptimisation()

        engine.masterVolume = settings.masterVolume
        engine.monitorVolume = settings.monitorVolume
        engine.switchRoute(runCatching { Route.valueOf(settings.route) }.getOrDefault(Route.MIC_AND_MONITOR))
        engine.start()

        val sounds = container.library.scan()
        launch(Dispatchers.IO) { engine.preload(sounds.take(48)) }

        if (settings.hotkeysEnabled) startHotkeys()
        if (settings.webPanelEnabled) startWebPanel()

        notify("Soundboard active · ${sounds.size} clips · ${engine.route.name.lowercase()}")
    }

    private fun startHotkeys() {
        val monitor = HotkeyMonitor { key, _ ->
            if (key.startsWith("__CAPTURE__:")) {
                controlServer?.reportCapturedKey(key.removePrefix("__CAPTURE__:"))
                return@HotkeyMonitor
            }
            when (key) {
                // Hard-wired panic combo: both grips is awkward to detect, so we
                // use the left thumbstick click.
                "BTN_THUMBL" -> container.engine.stopAll()
                else -> container.library.byHotkey(key).forEach { container.engine.play(it) }
            }
        }
        if (monitor.start()) {
            hotkeys = monitor
        } else {
            Log.w(TAG, "hotkeys unavailable without root")
        }
    }

    private fun startWebPanel() {
        runCatching {
            val server = ControlServer(
                context = this,
                container = container,
                port = container.settings.webPanelPort
            )
            server.start()
            controlServer = server
            Log.i(TAG, "control panel on ${server.panelUrl()}")
        }.onFailure { Log.e(TAG, "control server failed", it) }
    }

    private fun shutdown() {
        isRunning = false
        runCatching { controlServer?.stop() }
        controlServer = null
        runCatching { hotkeys?.stop() }
        hotkeys = null
        container.engine.stop()
        releaseWakeLock()
        scope.cancel()
    }

    override fun onDestroy() {
        if (isRunning) shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------ notification

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Soundboard", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Keeps the soundboard running inside games" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val panicIntent = PendingIntent.getService(
            this, 1, Intent(this, SoundboardService::class.java).setAction(ACTION_PANIC),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 2, Intent(this, SoundboardService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }

        return builder
            .setContentTitle("Quest Soundboard")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Panic stop", panicIntent).build())
            .addAction(Notification.Action.Builder(null, "Shut down", stopIntent).build())
            .build()
    }

    private fun notify(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun acquireWakeLock() {
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "soundboard:mixer").apply {
                setReferenceCounted(false)
                acquire(8 * 60 * 60 * 1000L)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }
}
