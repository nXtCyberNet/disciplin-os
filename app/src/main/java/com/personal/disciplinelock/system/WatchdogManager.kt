package com.personal.disciplinelock.system

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.util.concurrent.TimeUnit

class WatchdogManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("safety_watchdog_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "WatchdogManager"
        private const val KEY_CRASH_TIMESTAMPS = "crash_timestamps"
        private const val KEY_IS_DISARMED = "is_watchdog_disarmed"
        private const val KEY_DISARM_REASON = "disarm_reason"
        private const val KEY_LAST_DISARM_TIME = "last_disarm_time"

        const val CRASH_THRESHOLD = 3
        val WINDOW_DURATION_MS: Long = TimeUnit.MINUTES.toMillis(10)
        const val NOTIFICATION_CHANNEL_ID = "channel_watchdog"
        const val NOTIFICATION_ID = 9001
    }

    private val _disarmedState = MutableStateFlow(isDisarmed())
    val disarmedState: StateFlow<Boolean> = _disarmedState.asStateFlow()

    init {
        createNotificationChannel()
    }

    fun isDisarmed(): Boolean {
        return prefs.getBoolean(KEY_IS_DISARMED, false)
    }

    fun getDisarmReason(): String? {
        return prefs.getString(KEY_DISARM_REASON, null)
    }

    fun getCrashTimestamps(): List<Long> {
        val raw = prefs.getString(KEY_CRASH_TIMESTAMPS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<Long>()
            for (i in 0 until arr.length()) {
                list.add(arr.getLong(i))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Records a crash event. If 3 crashes occur within 10 minutes,
     * immediately trips the failsafe: disarms the lock system and notifies the user.
     * Returns true if failsafe was triggered.
     */
    fun recordCrashAndCheckFailsafe(throwable: Throwable?): Boolean {
        val now = System.currentTimeMillis()
        val cutoff = now - WINDOW_DURATION_MS
        val currentCrashes = getCrashTimestamps().filter { it >= cutoff }.toMutableList()
        currentCrashes.add(now)

        // Save updated crash history
        val jsonArray = JSONArray()
        currentCrashes.forEach { jsonArray.put(it) }
        prefs.edit().putString(KEY_CRASH_TIMESTAMPS, jsonArray.toString()).commit()

        Log.w(TAG, "Watchdog recorded crash (${currentCrashes.size}/$CRASH_THRESHOLD within 10m). Crash details: ${throwable?.message}")

        if (currentCrashes.size >= CRASH_THRESHOLD) {
            tripFailsafe("Crash loop detected: ${currentCrashes.size} crashes in the last 10 minutes. Last exception: ${throwable?.javaClass?.simpleName}: ${throwable?.message}")
            return true
        }
        return false
    }

    fun tripFailsafe(reason: String) {
        Log.e(TAG, "SAFETY WATCHDOG TRIPPED: $reason")
        prefs.edit()
            .putBoolean(KEY_IS_DISARMED, true)
            .putString(KEY_DISARM_REASON, reason)
            .putLong(KEY_LAST_DISARM_TIME, System.currentTimeMillis())
            .commit()

        _disarmedState.value = true
        postFailsafeNotification(reason)
    }

    fun rearm() {
        Log.i(TAG, "Watchdog manually re-armed by user.")
        prefs.edit()
            .putBoolean(KEY_IS_DISARMED, false)
            .remove(KEY_DISARM_REASON)
            .remove(KEY_CRASH_TIMESTAMPS)
            .apply()

        _disarmedState.value = false
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.cancel(NOTIFICATION_ID)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Safety Watchdog Failsafe",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Emergency failsafe notices when lock gate is disarmed"
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    private fun postFailsafeNotification(reason: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠️ Discipline Lock Failsafe Disarmed")
            .setContentText("The lock gate has been automatically disarmed to protect phone accessibility.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Failsafe Disarmed: $reason\n\nYou have full access to your phone. Open the app to inspect crash logs and re-arm when safe."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)

        try {
            notificationManager.notify(NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to post notification due to permission", e)
        }
    }
}
