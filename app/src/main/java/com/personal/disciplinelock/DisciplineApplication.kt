package com.personal.disciplinelock

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import com.personal.disciplinelock.data.SecurePreferences
import com.personal.disciplinelock.data.task.HabitTaskDatabaseHelper
import com.personal.disciplinelock.domain.challenge.EmergencySessionManager
import com.personal.disciplinelock.domain.math.MathEngineManager
import com.personal.disciplinelock.domain.mode.DisciplineModeManager
import com.personal.disciplinelock.domain.reset.DailyResetManager
import com.personal.disciplinelock.domain.task.HabitTaskManager
import com.personal.disciplinelock.domain.webhook.LocalWebhookServer
import com.personal.disciplinelock.domain.webhook.MissionVerificationEngine
import com.personal.disciplinelock.system.LockGateService
import com.personal.disciplinelock.system.RootController
import com.personal.disciplinelock.system.SafetyManager
import com.personal.disciplinelock.system.WatchdogManager

class DisciplineApplication : Application() {

    lateinit var securePreferences: SecurePreferences
        private set
    lateinit var watchdogManager: WatchdogManager
        private set
    lateinit var emergencySessionManager: EmergencySessionManager
        private set
    lateinit var safetyManager: SafetyManager
        private set
    lateinit var mathEngineManager: MathEngineManager
        private set
    lateinit var habitTaskDbHelper: HabitTaskDatabaseHelper
        private set
    lateinit var habitTaskManager: HabitTaskManager
        private set
    lateinit var disciplineModeManager: DisciplineModeManager
        private set
    lateinit var localWebhookServer: LocalWebhookServer
        private set
    lateinit var missionVerificationEngine: MissionVerificationEngine
        private set
    lateinit var rootController: RootController
        private set
    lateinit var dailyResetManager: DailyResetManager
        private set
    lateinit var appsWhitelistManager: com.personal.disciplinelock.domain.whitelist.AppsWhitelistManager
        private set

    companion object {
        private const val TAG = "DisciplineApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Initializing DisciplineApplication (Math Engine + Webhook Missions)")

        securePreferences = SecurePreferences(this)
        appsWhitelistManager = com.personal.disciplinelock.domain.whitelist.AppsWhitelistManager(this, securePreferences)
        rootController = RootController(this)
        watchdogManager = WatchdogManager(this)
        emergencySessionManager = EmergencySessionManager(this)
        safetyManager = SafetyManager(this)
        mathEngineManager = MathEngineManager(this)

        // Initialize Habits & Daily Tasks subsystem (100% Offline SQLite)
        habitTaskDbHelper = HabitTaskDatabaseHelper(this)
        habitTaskManager = HabitTaskManager(habitTaskDbHelper)

        // Initialize Central Discipline Mode Manager (Math + Habits + Tasks + Root Freezing)
        disciplineModeManager = DisciplineModeManager(
            context = this,
            mathEngineManager = mathEngineManager,
            habitTaskManager = habitTaskManager,
            emergencySessionManager = emergencySessionManager,
            rootController = rootController,
            appsWhitelistManager = appsWhitelistManager
        )

        // Initialize Outbound API Verification Engine & Inbound Local Webhook Server
        missionVerificationEngine = MissionVerificationEngine()
        localWebhookServer = LocalWebhookServer(
            preferredPort = securePreferences.webhookPort,
            customTailscaleHost = securePreferences.customTailscaleHost,
            onRunMetricsReceived = { distanceMeters, _, paceMinPerKm, isFinished, targetDistanceMeters, targetPaceMinPerKm ->
                val targetDist = targetDistanceMeters ?: securePreferences.morningTargetDistanceMeters
                val targetPace = targetPaceMinPerKm ?: securePreferences.morningTargetPaceMinPerKm
                val pace = paceMinPerKm ?: 0f
                if (isFinished && distanceMeters >= targetDist && (pace <= 0.1f || pace <= targetPace)) {
                    val r1 = habitTaskManager.verifyMissionByWebhook("morning-run", "GPSLogger Finished")
                    val r2 = habitTaskManager.verifyMissionByWebhook("morning run", "GPSLogger Finished")
                    val r3 = habitTaskManager.verifyMissionByWebhook("run", "GPSLogger Finished")
                    val r4 = habitTaskManager.verifyMissionByWebhook("workout", "GPSLogger Finished")
                    val r5 = habitTaskManager.verifyMissionByWebhook("morning mobility", "GPSLogger Finished")
                    if (r1.first || r2.first || r3.first || r4.first || r5.first) {
                        disciplineModeManager.reEvaluateGateStatus()
                    }
                }
            },
            onMissionVerified = { target, source ->
                if (target.equals("math", ignoreCase = true) || target.equals("quiz", ignoreCase = true) || target.equals("math_quiz", ignoreCase = true)) {
                    mathEngineManager.markQuizPassed()
                    disciplineModeManager.reEvaluateGateStatus()
                    Pair(true, "Math Quiz Passed via Webhook ($source)")
                } else {
                    val result = habitTaskManager.verifyMissionByWebhook(target, source)
                    if (result.first) {
                        disciplineModeManager.reEvaluateGateStatus()
                    }
                    result
                }
            },
            getMissionsSummary = {
                val obligations = habitTaskManager.snapshot.value
                val list = mutableListOf<Pair<String, Boolean>>()
                obligations.dailyTasks.forEach { list.add(Pair(it.title, it.isCompleted)) }
                obligations.habits.forEach { list.add(Pair(it.title, it.isCompletedToday)) }
                list
            },
            onStepsReceived = { _, _ -> Pair(false, null) },
            getStatusDetailsJson = {
                buildStatusDetailsJson()
            },
            onMissionAdded = { title, _ ->
                val id = habitTaskManager.addDailyTask(title)
                disciplineModeManager.reEvaluateGateStatus()
                Pair(true, "Created mission #$id: $title")
            }
        )
        localWebhookServer.start()

        // Initialize Daily Reset Cycle Manager
        dailyResetManager = DailyResetManager(
            context = this,
            securePreferences = securePreferences,
            habitTaskManager = habitTaskManager,
            disciplineModeManager = disciplineModeManager,
            mathEngineManager = mathEngineManager
        )
        dailyResetManager.scheduleDailyReset()

        setupCrashWatchdog()

        // Start background gate service
        startLockGateService()
    }

    private fun setupCrashWatchdog() {
        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e(TAG, "Uncaught exception caught by DisciplineApplication watchdog", throwable)
                watchdogManager.recordCrashAndCheckFailsafe(throwable)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to record crash in watchdog", e)
            } finally {
                originalHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    fun startLockGateService() {
        try {
            val serviceIntent = Intent(this, LockGateService::class.java).apply {
                putExtra(LockGateService.EXTRA_TRIGGER_REASON, "Application onCreate")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start LockGateService from application", e)
        }
    }

    fun buildStatusDetailsJson(): org.json.JSONObject {
        val root = org.json.JSONObject()
        root.put("status", "active")
        root.put("server", "DisciplineOS Webhook Receiver")
        root.put("timestamp", System.currentTimeMillis())

        if (::disciplineModeManager.isInitialized) {
            val gate = disciplineModeManager.gateStatus.value
            val modeObj = org.json.JSONObject().apply {
                put("mode", gate.currentMode.name)
                put("totalGates", gate.totalGates)
                put("completedGates", gate.completedGates)
                put("progressFraction", gate.progressFraction)
                put("statusSummary", gate.statusSummary)
                put("isMathPassed", gate.isMathPassed)
                put("isMathBlocked", gate.isMathBlocked)
            }
            root.put("disciplineMode", modeObj)
        }

        if (::mathEngineManager.isInitialized) {
            val mathObj = org.json.JSONObject().apply {
                put("enabled", mathEngineManager.isMathEngineEnabled)
                put("passed", mathEngineManager.hasPassedQuiz())
                put("blocked", mathEngineManager.isCurrentlyBlocked())
                put("questionsCount", securePreferences.mathNumQuestions)
                put("timeLimitSeconds", securePreferences.mathTimeLimitSeconds)
            }
            root.put("mathEngine", mathObj)
        }

        if (::habitTaskManager.isInitialized) {
            val taskSnap = habitTaskManager.snapshot.value
            val tasksArr = org.json.JSONArray()
            for (t in taskSnap.dailyTasks) {
                tasksArr.put(org.json.JSONObject().apply {
                    put("id", t.id)
                    put("title", t.title)
                    put("type", "task")
                    put("completed", t.isCompleted)
                    put("verificationType", t.verificationType.name)
                    put("verificationStatus", t.verificationStatus ?: "")
                })
            }
            for (h in taskSnap.habits) {
                tasksArr.put(org.json.JSONObject().apply {
                    put("id", h.id)
                    put("title", h.title)
                    put("type", "habit")
                    put("completed", h.isCompletedToday)
                    put("streak", h.streak)
                    put("verificationType", h.verificationType.name)
                    put("verificationStatus", h.verificationStatus ?: "")
                })
            }
            root.put("missions", tasksArr)
            root.put("habitsCompleted", taskSnap.habitsCompletedCount)
            root.put("habitsTotal", taskSnap.habitsTotalCount)
            root.put("tasksCompleted", taskSnap.tasksCompletedCount)
            root.put("tasksTotal", taskSnap.tasksTotalCount)
            root.put("areAllCompleted", taskSnap.areAllObligationsCompleted)
        }

        val h = securePreferences.morningAlarmHour
        val m = securePreferences.morningAlarmMinute
        val amPm = if (h < 12) "AM" else "PM"
        val displayH = when {
            h == 0 -> 12
            h > 12 -> h - 12
            else -> h
        }
        val alarmObj = org.json.JSONObject().apply {
            put("enabled", securePreferences.isMorningAlarmEnabled)
            put("alarmHour", h)
            put("alarmMinute", m)
            put("alarmTime", String.format(java.util.Locale.getDefault(), "%02d:%02d %s", displayH, m, amPm))
            put("targetDistanceMeters", securePreferences.morningTargetDistanceMeters)
            put("targetPaceMinPerKm", securePreferences.morningTargetPaceMinPerKm.toDouble())
            put("snoozeDurationMinutes", securePreferences.morningSnoozeMinutes)
            put("stationaryGraceSeconds", securePreferences.morningStationaryGraceSeconds)
            put("enforceTargetPace", true)
        }
        root.put("morningAlarm", alarmObj)

        return root
    }
}
