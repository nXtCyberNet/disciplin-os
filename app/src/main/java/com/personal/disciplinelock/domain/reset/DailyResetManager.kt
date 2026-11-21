package com.personal.disciplinelock.domain.reset

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.personal.disciplinelock.data.SecurePreferences
import com.personal.disciplinelock.domain.math.MathEngineManager
import com.personal.disciplinelock.domain.mode.DisciplineModeManager
import com.personal.disciplinelock.domain.task.HabitTaskManager
import com.personal.disciplinelock.system.DailyResetReceiver
import java.util.Calendar

/**
 * Manages the daily cycle transition and reset schedule.
 *
 * At the configured daily reset time (e.g. 12:00 AM midnight or 04:00 AM):
 * 1. Closes the previous day's discipline cycle.
 * 2. Refreshes obligations (recurring habits and daily tasks) for the new day.
 * 3. Resets the daily Math Quiz.
 * 4. Transitions phone mode back to DISCIPLINED_PHONE with pending missions.
 * 5. Schedules the next exact reset cycle.
 */
class DailyResetManager(
    private val context: Context,
    private val securePreferences: SecurePreferences,
    private val habitTaskManager: HabitTaskManager,
    private val disciplineModeManager: DisciplineModeManager,
    private val mathEngineManager: MathEngineManager
) {

    companion object {
        private const val TAG = "DailyResetManager"
        const val ACTION_DAILY_RESET = "com.personal.disciplinelock.ACTION_DAILY_RESET"
        private const val REQUEST_CODE_DAILY_RESET = 5001
    }

    /**
     * Schedules the next daily reset alarm at the hour and minute configured in SecurePreferences.
     */
    fun scheduleDailyReset() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val hour = securePreferences.dailyResetHour
        val minute = securePreferences.dailyResetMinute

        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (target.timeInMillis <= now.timeInMillis) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }

        val intent = Intent(context, DailyResetReceiver::class.java).apply {
            action = ACTION_DAILY_RESET
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_DAILY_RESET,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    target.timeInMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    target.timeInMillis,
                    pendingIntent
                )
            }
            Log.i(TAG, "Scheduled daily reset alarm for ${target.time} (Hour: $hour, Minute: $minute)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule daily reset alarm: ${e.message}", e)
        }
    }

    /**
     * Checks if a daily reset was missed (e.g. device was off or in deep Doze) and executes it immediately.
     */
    fun checkAndTriggerMissedReset() {
        val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val lastReset = securePreferences.lastDailyResetDate
        if (lastReset == null) {
            securePreferences.lastDailyResetDate = todayStr
            scheduleDailyReset()
            return
        }
        if (lastReset != todayStr) {
            Log.i(TAG, "Missed daily reset detected (last: $lastReset, today: $todayStr). Triggering reset now.")
            onDailyResetTriggered()
        } else {
            scheduleDailyReset()
        }
    }

    /**
     * Executes the daily reset sequence. Called when ACTION_DAILY_RESET fires.
     */
    fun onDailyResetTriggered() {
        Log.i(TAG, "⏰ Daily reset triggered! Starting new daily cycle...")
        val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        securePreferences.lastDailyResetDate = todayStr

        // 1. Refresh daily tasks and habits for the new day
        habitTaskManager.refresh()

        // 2. Reset Math Quiz state
        mathEngineManager.resetQuizPass()

        // 3. Re-evaluate mode: will switch to DISCIPLINED_PHONE since today's missions are pending
        disciplineModeManager.reEvaluateGateStatus()

        // 4. Schedule the next day's reset
        scheduleDailyReset()
    }
}
