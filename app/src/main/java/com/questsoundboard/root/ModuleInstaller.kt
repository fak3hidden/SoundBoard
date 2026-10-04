package com.questsoundboard.root

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Installs the "virtual mic" Magisk module straight from the app, so the user
 * never has to copy a zip onto the headset.
 *
 * What the module does:
 *   - symlinks this APK into /system/priv-app so the platform grants
 *     MODIFY_AUDIO_ROUTING / CAPTURE_AUDIO_OUTPUT
 *   - installs a privapp-permissions allowlist XML
 *   - patches audio_policy_configuration.xml to expose a remote_submix input
 *     that games can open as a regular microphone
 */
class ModuleInstaller(private val context: Context) {

    data class Progress(val step: String, val done: Boolean = false, val error: String? = null)

    suspend fun install(onProgress: (Progress) -> Unit): Boolean = withContext(Dispatchers.IO) {
        if (!RootShell.isRooted()) {
            onProgress(Progress("Root required", error = "No su binary. Install Magisk first."))
            return@withContext false
        }

        onProgress(Progress("Staging module files…"))
        val staging = File(context.cacheDir, "vmic_module").apply {
            deleteRecursively(); mkdirs()
        }
        copyAssetDir("magisk-module", staging)

        val apkPath = context.applicationInfo.sourceDir
        val dir = RootManager.MODULE_DIR

        onProgress(Progress("Writing /data/adb/modules…"))
        val r = RootShell.exec(
            "set -e",
            "rm -rf $dir",
            "mkdir -p $dir",
            "cp -r ${staging.absolutePath}/* $dir/",
            "chmod -R 0755 $dir",
            "chown -R 0:0 $dir",
            // record where the real APK lives so the module can link it at boot
            "echo '$apkPath' > $dir/apk_path",
            "mkdir -p $dir/system/priv-app/QuestSoundboard",
            "cp '$apkPath' $dir/system/priv-app/QuestSoundboard/QuestSoundboard.apk",
            "chmod 0644 $dir/system/priv-app/QuestSoundboard/QuestSoundboard.apk",
            "mkdir -p $dir/system/etc/permissions",
            "cp $dir/common/privapp-permissions-questsoundboard.xml " +
                "$dir/system/etc/permissions/privapp-permissions-questsoundboard.xml",
            "chmod 0644 $dir/system/etc/permissions/privapp-permissions-questsoundboard.xml",
            "touch $dir/auto_mount",
            "rm -f $dir/disable $dir/remove",
            "echo INSTALL_OK"
        )

        if (!r.out.contains("INSTALL_OK")) {
            onProgress(Progress("Install failed", error = r.err.ifBlank { r.out }))
            return@withContext false
        }

        onProgress(Progress("Unlocking hidden APIs…"))
        RootManager(context).unlockHiddenApi()

        onProgress(Progress("Module installed — reboot required", done = true))
        true
    }

    suspend fun uninstall(): Boolean = withContext(Dispatchers.IO) {
        RootShell.exec("touch ${RootManager.MODULE_DIR}/remove").ok
    }

    suspend fun reboot() = withContext(Dispatchers.IO) {
        RootShell.exec("sync", "svc power reboot || reboot")
    }

    private fun copyAssetDir(assetPath: String, dest: File) {
        val assets = context.assets
        val children = assets.list(assetPath) ?: return
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            return
        }
        dest.mkdirs()
        for (child in children) {
            copyAssetDir("$assetPath/$child", File(dest, child))
        }
    }
}
