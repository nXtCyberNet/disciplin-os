package com.personal.disciplinelock.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "Received boot/replacement action: $action")

        val serviceIntent = Intent(context, LockGateService::class.java).apply {
            putExtra(LockGateService.EXTRA_TRIGGER_REASON, "BOOT_COMPLETED: $action")
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start LockGateService on boot: ${e.message}", e)
        }

        // Schedule daily reset
        try {
            val app = context.applicationContext as? com.personal.disciplinelock.DisciplineApplication
            app?.dailyResetManager?.scheduleDailyReset()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule dailyResetManager on boot: ${e.message}", e)
        }
    }
}
