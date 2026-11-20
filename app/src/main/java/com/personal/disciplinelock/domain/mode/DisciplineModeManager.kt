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
    private val emergencySessionManager: EmergencySessionManager
) {

    companion object {
        private const val TAG = "DisciplineModeManager"
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val _gateStatus = MutableStateFlow(DisciplineGateStatus())
    val gateStatus: StateFlow<DisciplineGateStatus> = _gateStatus.asStateFlow()

    private val securePreferences = com.personal.disciplinelock.data.SecurePreferences(context)
    private val prefs = context.getSharedPreferences("discipline_test_mode_prefs", Context.MODE_PRIVATE)
    private var testModeOverride: DisciplineMode? = null

    init {
        val savedMode = prefs.getString("test_mode_override", DisciplineMode.NORMAL_PHONE.name)
        testModeOverride = try {
            if (savedMode != null) DisciplineMode.valueOf(savedMode) else DisciplineMode.NORMAL_PHONE
        } catch (_: Exception) {
            DisciplineMode.NORMAL_PHONE
        }
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

        val finalMode = testModeOverride ?: mode
        val finalSummary = when {
            testModeOverride == DisciplineMode.NORMAL_PHONE -> "Testing Mode Active (Normal Phone Unlocked)"
            testModeOverride == DisciplineMode.DISCIPLINED_PHONE -> "Testing Mode Active (Disciplined Mode Simulated)"
            else -> summary
        }
        Log.d(TAG, "Mode updated: $finalMode ($completedCount/$totalGates gates cleared)")

        _gateStatus.value = DisciplineGateStatus(
            currentMode = finalMode,
            isMathPassed = isMathPassed,
            isMathBlocked = isMathBlocked,
            isHabitsDone = isHabitsDone,
            isTasksDone = isTasksDone,
            isEmergencyActive = isEmergency,
            totalGates = totalGates,
            completedGates = completedCount,
            progressFraction = fraction,
            statusSummary = finalSummary,
            overallStreak = obligations.overallStreak
        )
    }

    fun toggleTestMode(): DisciplineMode {
        val current = _gateStatus.value.currentMode
        testModeOverride = if (current == DisciplineMode.NORMAL_PHONE) {
            DisciplineMode.DISCIPLINED_PHONE
        } else {
            DisciplineMode.NORMAL_PHONE
        }
        prefs.edit().putString("test_mode_override", testModeOverride?.name).apply()
        reEvaluateGateStatus()
        return _gateStatus.value.currentMode
    }

    fun forceUnlockNormalPhone() {
        testModeOverride = DisciplineMode.NORMAL_PHONE
        reEvaluateGateStatus()
    }
}
