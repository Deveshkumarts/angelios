package org.sih.itantra.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the mesh listener right after the device reboots, so a rebooted phone doesn't sit
 * silent (missing SOS broadcasts) until the user remembers to manually reopen the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            try {
                MeshForegroundService.start(context)
            } catch (_: Throwable) {}
        }
    }
}
