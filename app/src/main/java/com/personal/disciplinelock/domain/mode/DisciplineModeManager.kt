package com.personal.disciplinelock.domain.mode

import android.content.Context
import android.util.Log
import com.personal.disciplinelock.domain.challenge.EmergencySessionManager
import com.personal.disciplinelock.domain.math.MathEngineManager
import com.personal.disciplinelock.domain.task.HabitTaskManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Operating personas for DisciplineOS.
 */
enum class DisciplineMode {
    /**
     * Monk / Focus Mode: Blocked until Math & Missions are cleared.
     */
    DISCIPLINED_PHONE,

    /**
     * Full Standard Android System unlocked.
     * Accessible once all daily gates (Math + Missions) are completed.
     */
    NORMAL_PHONE,

    /**
     * Temporary emergency bypass window.
     */
    EMERGENCY_OVERRIDE
}

/**
 * Unified status snapshot of daily gates.
 */
data class DisciplineGateStatus(
    val currentMode: DisciplineMode = DisciplineMode.DISCIPLINED_PHONE,
    val isMathPassed: Boolean = false,
    val isMathBlocked: Boolean = false,
    val isHabitsDone: Boolean = false,
    val isTasksDone: Boolean = false,
    val isEmergencyActive: Boolean = false,
    val totalGates: Int = 3,
    val completedGates: Int = 0,
    val progressFraction: Float = 0f,
    val statusSummary: String = "Disciplined Phone Active",
    val overallStreak: Int = 0
)

/**
 * Central State Machine that arbitrates between "Disciplined Phone" and "Normal Phone".
 *
 * Monitors:
 * 1. Math Engine (Quiz passed, no 5-min penalty active)
 * 2. Daily Habits (All daily recurring habits checked)
 * 3. Today's Tasks (All today's tasks checked / verified via webhook)
 * 4. Emergency Session Manager (friction challenge unlock)
 */
class DisciplineModeManager(
    private val context: Context,
    private val mathEngineManager: MathEngineManager,
    private val habitTaskManager: HabitTaskManager,
    private val emergencySessionManager: EmergencySessionManager,
    private val rootController: com.personal.disciplinelock.system.RootController? = null,
    private val appsWhitelistManager: com.personal.disciplinelock.domain.whitelist.AppsWhitelistManager? = null
) {

    companion object {
        private const val TAG = "DisciplineModeManager"
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val _gateStatus = MutableStateFlow(DisciplineGateStatus())
    val gateStatus: StateFlow<DisciplineGateStatus> = _gateStatus.asStateFlow()

    private val securePreferences = com.personal.disciplinelock.data.SecurePreferences(context)
    private var lastAppliedMode: DisciplineMode? = null

    init {
        observeSubsystems()
        reEvaluateGateStatus()
    }

    private fun observeSubsystems() {
        // Observe math engine state changes
        scope.launch {
            mathEngineManager.quizState.collect {
                reEvaluateGateStatus()
            }
        }

        // Observe habits and daily tasks changes
        scope.launch {
            habitTaskManager.snapshot.collect {
                reEvaluateGateStatus()
            }
        }

        // Observe emergency challenge window
        scope.launch {
            emergencySessionManager.sessionState.collect {
                reEvaluateGateStatus()
            }
        }
    }

    /**
     * Re-evaluates whether the phone should be in Disciplined Mode or Normal Phone Mode.
     */
    fun reEvaluateGateStatus() {
        if (!securePreferences.isGateEnabled) {
            _gateStatus.value = DisciplineGateStatus(
                currentMode = DisciplineMode.NORMAL_PHONE,
                isMathPassed = true,
                isMathBlocked = false,
                isHabitsDone = true,
                isTasksDone = true,
                isEmergencyActive = false,
                totalGates = 0,
                completedGates = 0,
                progressFraction = 1f,
                statusSummary = "Discipline Gate Protection is Turned Off"
            )
            return
        }

        val isEmergency = emergencySessionManager.isCurrentlyEmergencyUnlocked()
        val isMathBlocked = mathEngineManager.isCurrentlyBlocked()
        val isMathPassed = mathEngineManager.hasPassedQuiz()

        val obligations = habitTaskManager.snapshot.value
        val isHabitsDone = obligations.areAllHabitsCompleted
        val isTasksDone = obligations.areAllTasksCompleted

        // Calculate progress dynamically based on configured gates
        var totalGates = 0
        var completedCount = 0

        if (mathEngineManager.isMathEngineEnabled) {
            totalGates++
            if (isMathPassed && !isMathBlocked) completedCount++
        }
        if (obligations.habitsTotalCount > 0) {
            totalGates++
            if (isHabitsDone) completedCount++
        }
        if (obligations.tasksTotalCount > 0) {
            totalGates++
            if (isTasksDone) completedCount++
        }

        if (totalGates == 0) {
            totalGates = 1
            completedCount = 1
        }

        val fraction = completedCount.toFloat() / totalGates.toFloat()

        val mode: DisciplineMode
        val summary: String

        when {
            isEmergency -> {
                mode = DisciplineMode.EMERGENCY_OVERRIDE
                summary = "Emergency Window Active (Normal System Available)"
            }
            isMathBlocked -> {
                mode = DisciplineMode.DISCIPLINED_PHONE
                summary = "PHONE BLOCKED: 5-minute math penalty active"
            }
            completedCount == totalGates -> {
                mode = DisciplineMode.NORMAL_PHONE
                summary = "All Discipline Gates Cleared! Normal Phone Access Granted"
            }
            else -> {
                mode = DisciplineMode.DISCIPLINED_PHONE
                summary = "Disciplined Phone ($completedCount/$totalGates Gates Cleared)"
            }
        }

        Log.d(TAG, "Mode updated: $mode ($completedCount/$totalGates gates cleared)")

        _gateStatus.value = DisciplineGateStatus(
            currentMode = mode,
            isMathPassed = isMathPassed,
            isMathBlocked = isMathBlocked,
            isHabitsDone = isHabitsDone,
            isTasksDone = isTasksDone,
            isEmergencyActive = isEmergency,
            totalGates = totalGates,
            completedGates = completedCount,
            progressFraction = fraction,
            statusSummary = summary,
            overallStreak = obligations.overallStreak
        )

        if (mode != lastAppliedMode) {
            lastAppliedMode = mode
            applyAppFreezingForMode(mode)
        }
    }

    private fun applyAppFreezingForMode(targetMode: DisciplineMode) {
        scope.launch(Dispatchers.IO) {
            val root = rootController ?: return@launch
            val whitelistManager = appsWhitelistManager ?: return@launch
            if (!securePreferences.isRootFreezingEnabled) {
                Log.d(TAG, "Root app freezing is disabled in preferences.")
                return@launch
            }
            if (!root.isRootAccessGranted()) {
                Log.w(TAG, "Root access not granted. Cannot freeze apps.")
                return@launch
            }

            try {
                // Refresh list of installed launcher apps if empty
                if (whitelistManager.installedApps.value.isEmpty()) {
                    whitelistManager.refreshInstalledApps()
                }

                when (targetMode) {
                    DisciplineMode.DISCIPLINED_PHONE -> {
                        val whitelisted = whitelistManager.whitelistedPackages.value
                        val appsToSuspend = whitelistManager.installedApps.value
                            .map { it.packageName }
                            .filter { pkg ->
                                !whitelisted.contains(pkg) &&
                                pkg != context.packageName &&
                                !isSystemCriticalPackage(pkg)
                            }
                        if (appsToSuspend.isNotEmpty()) {
                            Log.i(TAG, "Disciplined Mode: Suspending ${appsToSuspend.size} non-whitelisted apps via root.")
                            root.suspendPackages(appsToSuspend)
                        }
                    }
                    DisciplineMode.NORMAL_PHONE, DisciplineMode.EMERGENCY_OVERRIDE -> {
                        val allApps = whitelistManager.installedApps.value
                            .map { it.packageName }
                            .filter { !isSystemCriticalPackage(it) }
                        if (allApps.isNotEmpty()) {
                            Log.i(TAG, "Normal Mode: Unsuspending ${allApps.size} apps via root.")
                            root.unsuspendPackages(allApps)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error applying app freezing for mode $targetMode", e)
            }
        }
    }

    private fun isSystemCriticalPackage(pkg: String): Boolean {
        return pkg == "android" ||
               pkg == "com.android.systemui" ||
               pkg == "com.android.settings" ||
               pkg == "com.android.phone" ||
               pkg == "com.android.server.telecom" ||
               pkg.contains("launcher") ||
               pkg.contains("inputmethod") ||
               pkg.contains("emergency") ||
               pkg.contains("google.android.gms") ||
               pkg.contains("google.android.gsf") ||
               pkg.contains("google.android.dialer") ||
               pkg.contains("com.nxtcybernet.disciplinerun") ||
               pkg.contains("mendhak.gpslogger")
    }

}
