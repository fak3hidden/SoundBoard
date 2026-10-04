package com.questsoundboard.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.questsoundboard.SoundboardApp

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? SoundboardApp ?: return
        if (app.container.settings.startOnBoot) {
            SoundboardService.start(context)
        }
    }
}
