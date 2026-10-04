package com.questsoundboard.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.questsoundboard.AppContainer
import com.questsoundboard.SoundboardApp
import com.questsoundboard.audio.Route
import com.questsoundboard.data.Sound
import com.questsoundboard.root.ModuleInstaller
import com.questsoundboard.root.RootManager
import com.questsoundboard.service.SoundboardService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var container: AppContainer

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = (application as SoundboardApp).container
        container.library.ensureFolders()
        requestPermissions()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF6C5CE7),
                secondary = Color(0xFF00D2FF),
                background = Color(0xFF0B0D14),
                surface = Color(0xFF161A28)
            )) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SoundboardScreen(container, this)
                }
            }
        }
    }

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(perms.toTypedArray())
    }

    fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }
    }
}

@Composable
fun SoundboardScreen(container: AppContainer, activity: MainActivity) {
    val scope = rememberCoroutineScope()
    val rootStatus by container.rootManager.status.collectAsState()
    val sounds by container.library.sounds.collectAsState()
    val playing by container.engine.nowPlaying.collectAsState()

    var serviceRunning by remember { mutableStateOf(SoundboardService.isRunning) }
    var installLog by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var route by remember { mutableStateOf(container.engine.route) }

    LaunchedEffect(Unit) {
        container.rootManager.refresh()
        container.library.scan()
    }

    LaunchedEffect(serviceRunning) {
        kotlinx.coroutines.delay(800)
        serviceRunning = SoundboardService.isRunning
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Quest Soundboard", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(
            "${rootStatus.device} · Android ${rootStatus.androidRelease} · ${sounds.size} clips loaded",
            fontSize = 13.sp, color = Color(0xFF8D97B5)
        )

        PrivilegeCard(rootStatus, busy, onRefresh = {
            scope.launch { busy = true; container.rootManager.refresh(); busy = false }
        }, onInstallModule = {
            scope.launch {
                busy = true
                ModuleInstaller(activity).install { p ->
                    installLog = p.error?.let { "⚠ $it" } ?: p.step
                }
                container.rootManager.refresh()
                busy = false
            }
        }, onReboot = {
            scope.launch { ModuleInstaller(activity).reboot() }
        })

        installLog?.let {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1D2234))) {
                Text(it, Modifier.padding(14.dp), fontSize = 13.sp)
            }
        }

        // ------------------------------------------------------------- engine
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF161A28))) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Soundboard engine", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                    Switch(checked = serviceRunning, onCheckedChange = { on ->
                        if (on) SoundboardService.start(activity) else SoundboardService.stop(activity)
                        serviceRunning = on
                    })
                }

                Text("Output routing", fontSize = 13.sp, color = Color(0xFF8D97B5))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    RouteOption("Mic + Me", "Teammates hear it, you hear it too",
                        Route.MIC_AND_MONITOR, route, rootStatus.canInjectMic) {
                        route = it; container.engine.switchRoute(it); container.settings.route = it.name
                    }
                    RouteOption("Mic only", "Only the game/voice chat hears it",
                        Route.MIC, route, rootStatus.canInjectMic) {
                        route = it; container.engine.switchRoute(it); container.settings.route = it.name
                    }
                    RouteOption("Me only", "Private — nothing leaves the headset",
                        Route.MONITOR, route, true) {
                        route = it; container.engine.switchRoute(it); container.settings.route = it.name
                    }
                    RouteOption("Acoustic", "Play out loud so the real mic catches it (no root)",
                        Route.ACOUSTIC, route, true) {
                        route = it; container.engine.switchRoute(it); container.settings.route = it.name
                    }
                }

                val url = SoundboardService.controlServer?.panelUrl()
                if (serviceRunning && url != null) {
                    HorizontalDivider(color = Color(0xFF262C42))
                    Text("Phone / PC control panel", fontSize = 13.sp, color = Color(0xFF8D97B5))
                    Text(url, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                        color = Color(0xFF00D2FF))
                    Text(
                        "Open that on your phone (same Wi-Fi) and tap pads without leaving the game.",
                        fontSize = 12.sp, color = Color(0xFF8D97B5)
                    )
                }
            }
        }

        // ------------------------------------------------------------ library
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Your clips", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            TextButton(onClick = { scope.launch { container.library.scan() } }) { Text("Rescan") }
            TextButton(onClick = { activity.requestAllFilesAccess() }) { Text("Storage access") }
        }

        container.library.lastScanError?.let {
            Text(it, color = Color(0xFFFF8D96), fontSize = 13.sp)
        }

        if (sounds.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF161A28))) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No audio found", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Put your own files in /sdcard/Soundboard/ — sub-folders become categories.\n\n" +
                            "adb push airhorn.mp3 /sdcard/Soundboard/Memes/\n\n" +
                            "…or drag-and-drop them onto the web panel from your phone.",
                        fontSize = 13.sp, color = Color(0xFF8D97B5)
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(170.dp),
                modifier = Modifier.heightIn(max = 520.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(sounds, key = { it.id }) { sound ->
                    PadButton(
                        sound = sound,
                        isPlaying = playing.any { it.soundId == sound.id },
                        onClick = {
                            if (!container.engine.isRunning) SoundboardService.start(activity)
                            container.engine.play(sound)
                        }
                    )
                }
            }
            Button(
                onClick = { container.engine.stopAll() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4757)),
                modifier = Modifier.fillMaxWidth()
            ) { Text("PANIC STOP", fontWeight = FontWeight.Bold) }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PrivilegeCard(
    status: RootManager.Status,
    busy: Boolean,
    onRefresh: () -> Unit,
    onInstallModule: () -> Unit,
    onReboot: () -> Unit
) {
    val (title, body, tint) = when (status.tier) {
        RootManager.Tier.PRIVILEGED -> Triple(
            "Full access — mic injection ready",
            "${status.rootProvider} + virtual-mic module active. Games hear your clips as microphone input.",
            Color(0xFF2ED573)
        )
        RootManager.Tier.ROOT -> Triple(
            if (status.moduleInstalled) "Root OK — reboot to finish" else "Root OK — module not installed",
            if (status.moduleInstalled)
                "The module is in place. Reboot the headset so Android grants MODIFY_AUDIO_ROUTING."
            else
                "${status.rootProvider} detected. Install the virtual-mic module to unlock real mic injection.",
            Color(0xFFFFA502)
        )
        RootManager.Tier.NONE -> Triple(
            "No root detected",
            "The soundboard still works in Acoustic mode. For clean injection into games you need a " +
                "rooted headset (Quest 1/2 via Magisk; Quest 3/3S needs an unlocked bootloader).",
            Color(0xFFFF4757)
        )
    }

    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF161A28))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(tint, androidx.compose.foundation.shape.CircleShape)
                )
                Spacer(Modifier.width(10.dp))
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(body, fontSize = 13.sp, color = Color(0xFF8D97B5))

            if (status.rooted) {
                Text(
                    buildString {
                        append("su: ${status.suPath}")
                        status.magiskVersion?.let { append("  ·  Magisk $it") }
                        append("  ·  hidden-API ${if (status.hiddenApiUnlocked) "unlocked" else "locked"}")
                        append("  ·  SELinux ${if (status.selinuxEnforcing) "enforcing" else "permissive"}")
                    },
                    fontSize = 11.sp, color = Color(0xFF5E6788)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRefresh, enabled = !busy) { Text("Re-check") }
                if (status.rooted && !status.moduleInstalled) {
                    Button(onClick = onInstallModule, enabled = !busy) { Text("Install module") }
                }
                if (status.rooted && status.moduleInstalled && !status.canInjectMic) {
                    Button(onClick = onReboot, enabled = !busy) { Text("Reboot now") }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun RouteOption(
    label: String,
    description: String,
    value: Route,
    selected: Route,
    enabled: Boolean,
    onSelect: (Route) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected == value,
            onClick = { if (enabled) onSelect(value) },
            enabled = enabled
        )
        Column(Modifier.weight(1f)) {
            Text(
                label + if (!enabled) "  (needs root module)" else "",
                fontSize = 14.sp,
                color = if (enabled) Color.White else Color(0xFF5E6788)
            )
            Text(description, fontSize = 11.sp, color = Color(0xFF8D97B5))
        }
    }
}

@Composable
private fun PadButton(sound: Sound, isPlaying: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (isPlaying) Color(0xFF243150) else Color(0xFF1D2234)
        ),
        border = if (isPlaying) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00D2FF)) else null,
        modifier = Modifier.height(96.dp)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                sound.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (sound.pad > 0) Text("PAD ${sound.pad}", fontSize = 10.sp, color = Color(0xFFB9B2FF))
                sound.hotkey?.let {
                    Text(it.removePrefix("BTN_").removePrefix("KEY_"),
                        fontSize = 10.sp, color = Color(0xFF9FE6FF))
                }
                if (sound.loop) Text("LOOP", fontSize = 10.sp, color = Color(0xFF9BF0C0))
            }
        }
    }
}
