package com.personal.disciplinelock.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.personal.disciplinelock.DisciplineApplication

/**
 * BroadcastReceiver triggered by AlarmManager when the daily reset time arrives.
 */
class DailyResetReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DailyResetReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Received daily reset broadcast: ${intent.action}")
        try {
            val app = context.applicationContext as? DisciplineApplication
            app?.dailyResetManager?.onDailyResetTriggered()
        } catch (e: Exception) {
            Log.e(TAG, "Error executing daily reset: ${e.message}", e)
        }
    }
}
