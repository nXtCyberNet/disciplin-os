package com.personal.disciplinelock.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.personal.disciplinelock.DisciplineApplication
import com.personal.disciplinelock.data.SecurePreferences
import com.personal.disciplinelock.domain.mode.DisciplineMode
import com.personal.disciplinelock.domain.task.HabitTaskManager
import com.personal.disciplinelock.ui.LockGateActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Persistent Foreground Service maintaining the live Daily Missions Notification.
 */
class LockGateService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private lateinit var securePreferences: SecurePreferences
    private lateinit var habitTaskManager: HabitTaskManager

    companion object {
        private const val TAG = "LockGateService"
        const val EXTRA_TRIGGER_REASON = "extra_trigger_reason"
        const val NOTIFICATION_CHANNEL_ID = "channel_lock_service"
        const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Creating LockGateService for Missions Notification")

        val app = application as DisciplineApplication
        securePreferences = app.securePreferences
        habitTaskManager = app.habitTaskManager

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildServiceNotification())

        serviceScope.launch {
            habitTaskManager.snapshot.collect {
                updateMissionsNotification()
            }
        }

        serviceScope.launch {
            app.disciplineModeManager.gateStatus.collect {
                updateMissionsNotification()
            }
        }

        serviceScope.launch {
            app.mathEngineManager.quizState.collect {
                updateMissionsNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateMissionsNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Destroying LockGateService")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Discipline Lock Service"
            val descriptionText = "Ongoing status for daily discipline missions and math challenges"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun buildServiceNotification(): Notification {
        val app = application as DisciplineApplication
        val gateStatus = app.disciplineModeManager.gateStatus.value
        val taskSnapshot = app.habitTaskManager.snapshot.value
        val pendingHabits = taskSnapshot.habits.filter { !it.isCompletedToday }
        val pendingTasks = taskSnapshot.dailyTasks.filter { !it.isCompleted }
        val totalPending = pendingHabits.size + pendingTasks.size

        val openIntent = Intent(this, LockGateActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(LockGateActivity.EXTRA_NAVIGATE_TAB, "MISSIONS")
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val streak = taskSnapshot.overallStreak
        val title = if (gateStatus.currentMode == DisciplineMode.NORMAL_PHONE) {
            "🎉 All Missions Done (Normal Phone Unlocked)${if (streak > 0) " • 🔥 $streak Days" else ""}"
        } else {
            "🎯 Daily Missions (${gateStatus.completedGates}/${gateStatus.totalGates} Gates Done)${if (streak > 0) " • 🔥 $streak" else ""}"
        }

        val shortSummary = if (totalPending == 0) {
            "All daily habits and tasks completed."
        } else {
            "$totalPending pending: ${pendingHabits.take(2).joinToString { it.title }}"
        }

        val bigText = StringBuilder()
        bigText.append("Status: ${gateStatus.statusSummary}\n\n")
        if (streak > 0) {
            bigText.append("🔥 Discipline Streak: $streak ${if (streak == 1) "Day" else "Days"} (Reset at ${app.securePreferences.formattedDailyResetTime})\n\n")
        }
        val mathPassed = app.mathEngineManager.hasPassedQuiz()
        val mathBlocked = app.mathEngineManager.isCurrentlyBlocked()
        bigText.append("🧠 Math Gate: ${if (mathBlocked) "Blocked (5-min penalty)" else if (mathPassed) "Passed ✅" else "Pending ⏳"}\n")

        if (pendingHabits.isNotEmpty()) {
            bigText.append("\n📌 Pending Habits (${pendingHabits.size}):\n")
            pendingHabits.take(4).forEach { bigText.append(" • ${it.title}\n") }
        }
        if (pendingTasks.isNotEmpty()) {
            bigText.append("\n📝 Today's Tasks (${pendingTasks.size}):\n")
            pendingTasks.take(4).forEach { bigText.append(" • ${it.title}\n") }
        }

        val mathIntent = Intent(this, LockGateActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(LockGateActivity.EXTRA_NAVIGATE_TAB, "MATH_ENGINE")
        }
        val mathPendingIntent = PendingIntent.getActivity(
            this,
            1,
            mathIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(shortSummary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText.toString().trim()))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_agenda,
                "Missions",
                pendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_help,
                "Math Quiz",
                mathPendingIntent
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun updateMissionsNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.notify(NOTIFICATION_ID, buildServiceNotification())
    }
}
