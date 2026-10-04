package com.questsoundboard.root

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Everything the app needs to know about the privilege level it is running at.
 *
 * There are three tiers:
 *
 *  1. [Tier.NONE]       - stock headset. Only acoustic routing works (play out
 *                         of the headset speakers so the headset mic picks it
 *                         up). Works everywhere, sounds worse.
 *  2. [Tier.ROOT]       - `su` is available. We can flip hidden-API policy,
 *                         read /dev/input for controller hotkeys and drop the
 *                         Magisk module in place.
 *  3. [Tier.PRIVILEGED] - the Magisk module installed us into /system/priv-app
 *                         and granted MODIFY_AUDIO_ROUTING, so true microphone
 *                         injection via AudioPolicy is available.
 */
class RootManager(private val context: Context) {

    enum class Tier { NONE, ROOT, PRIVILEGED }

    data class Status(
        val tier: Tier = Tier.NONE,
        val suPath: String? = null,
        val rootProvider: String = "unknown",
        val magiskVersion: String? = null,
        val moduleInstalled: Boolean = false,
        val moduleVersion: String? = null,
        val hiddenApiUnlocked: Boolean = false,
        val selinuxEnforcing: Boolean = true,
        val device: String = deviceName(),
        val androidRelease: String = Build.VERSION.RELEASE,
        val lastError: String? = null
    ) {
        val rooted: Boolean get() = tier != Tier.NONE
        val canInjectMic: Boolean get() = tier == Tier.PRIVILEGED
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    companion object {
        const val MODULE_ID = "questsoundboard_vmic"
        const val MODULE_DIR = "/data/adb/modules/$MODULE_ID"

        /** Maps Quest board names to something a human recognises. */
        fun deviceName(): String = when (Build.DEVICE.lowercase()) {
            "monterey" -> "Meta Quest 1"
            "hollywood" -> "Meta Quest 2"
            "seacliff" -> "Meta Quest Pro"
            "eureka" -> "Meta Quest 3"
            "panther" -> "Meta Quest 3S"
            else -> "${Build.MANUFACTURER} ${Build.MODEL}"
        }

        fun isQuest(): Boolean =
            Build.MANUFACTURER.equals("Oculus", true) || Build.MANUFACTURER.equals("Meta", true)
    }

    /** Full privilege probe. Safe to call repeatedly. */
    suspend fun refresh(): Status = withContext(Dispatchers.IO) {
        RootShell.invalidate()
        val su = RootShell.findSu()
        if (su == null) {
            _status.value = Status(tier = Tier.NONE, lastError = "No su binary found")
            return@withContext _status.value
        }

        // Tag every answer so we never depend on command/line ordering — some
        // commands print nothing, others print several lines.
        // Backticks rather than $(...) purely so the Kotlin compiler never has
        // to decide whether '$(' starts a string template.
        val probe = RootShell.exec(
            "echo MAGISK=`magisk -V 2>/dev/null`",
            "echo MODULE=`test -d $MODULE_DIR && echo yes || echo no`",
            "echo MODVER=`grep '^version=' $MODULE_DIR/module.prop 2>/dev/null | cut -d= -f2`",
            "echo HIDDENAPI=`settings get global hidden_api_policy 2>/dev/null`",
            "echo SELINUX=`getenforce 2>/dev/null`",
            "echo PROVIDER=`test -d /data/adb/ksu && echo KernelSU || " +
                "(test -d /data/adb/ap && echo APatch || echo Magisk)`"
        )

        val fields = probe.stdout
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }.toMap()

        fun field(key: String) = fields[key]?.takeIf { it.isNotBlank() && it != "null" }

        val magisk = field("MAGISK")
        val moduleInstalled = field("MODULE") == "yes"
        val moduleVersion = field("MODVER")
        val hiddenApi = field("HIDDENAPI") == "1"
        val enforcing = field("SELINUX")?.equals("Enforcing", true) ?: true
        val provider = field("PROVIDER") ?: "unknown"

        val privileged = moduleInstalled && hasPrivilegedPermission()

        _status.value = Status(
            tier = if (privileged) Tier.PRIVILEGED else Tier.ROOT,
            suPath = su,
            rootProvider = provider,
            magiskVersion = magisk,
            moduleInstalled = moduleInstalled,
            moduleVersion = moduleVersion,
            hiddenApiUnlocked = hiddenApi,
            selinuxEnforcing = enforcing
        )
        _status.value
    }

    private fun hasPrivilegedPermission(): Boolean = try {
        context.packageManager.checkPermission(
            "android.permission.MODIFY_AUDIO_ROUTING",
            context.packageName
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }

    /**
     * Unlocks hidden/greylisted APIs for this process. Required before the
     * AudioPolicy reflection in MicInjector will work on Android 10+.
     * Takes effect for processes started *after* the setting flips, so the app
     * restarts itself afterwards.
     */
    suspend fun unlockHiddenApi(): Boolean = withContext(Dispatchers.IO) {
        val r = RootShell.exec(
            "settings put global hidden_api_policy 1",
            "settings put global hidden_api_policy_pre_p_apps 1",
            "settings put global hidden_api_policy_p_apps 1"
        )
        r.ok
    }

    /** Grants the signature-level permissions directly (works once we are priv-app). */
    suspend fun grantAudioRouting(): Boolean = withContext(Dispatchers.IO) {
        val pkg = context.packageName
        RootShell.exec(
            "pm grant $pkg android.permission.MODIFY_AUDIO_ROUTING",
            "pm grant $pkg android.permission.CAPTURE_AUDIO_OUTPUT",
            "appops set $pkg PROJECT_MEDIA allow"
        ).ok
    }

    /** Keeps the service alive while a game is in the foreground. */
    suspend fun whitelistFromBatteryOptimisation(): Boolean = withContext(Dispatchers.IO) {
        RootShell.exec(
            "dumpsys deviceidle whitelist +${context.packageName}",
            "cmd appops set ${context.packageName} RUN_IN_BACKGROUND allow",
            "cmd appops set ${context.packageName} RUN_ANY_IN_BACKGROUND allow"
        ).ok
    }
}
