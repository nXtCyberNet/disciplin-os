package com.personal.disciplinelock.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personal.disciplinelock.DisciplineApplication
import com.personal.disciplinelock.domain.math.MathQuizState
import com.personal.disciplinelock.domain.mode.DisciplineGateStatus
import com.personal.disciplinelock.domain.task.DailyTaskItem
import com.personal.disciplinelock.domain.task.HabitItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LockTab {
    MISSIONS,
    MATH_ENGINE,
    SETTINGS,
    WHITELIST
}

data class LockGateUiState(
    val selectedTab: LockTab = LockTab.MISSIONS,
    val isGateEnabled: Boolean = true,
    // Math Engine State
    val mathQuizState: MathQuizState = MathQuizState(),
    val isMathEngineEnabled: Boolean = true,
    val isMathQuizPassed: Boolean = false,
    val isMathBlocked: Boolean = false,
    val mathBlockRemainingSeconds: Long = 0L,
    val mathNumQuestions: Int = 5,
    val mathTimeLimitSeconds: Int = 300,
    val mathAvailableQuestionCount: Int = 0,
    // Missions & Habits State
    val gateStatus: DisciplineGateStatus = DisciplineGateStatus(),
    val habits: List<HabitItem> = emptyList(),
    val dailyTasks: List<DailyTaskItem> = emptyList(),
    val overallStreak: Int = 0,
    val dailyResetTimeText: String = "12:00 AM",
    val dailyResetHour: Int = 0,
    val dailyResetMinute: Int = 0,
    val morningAlarmEnabled: Boolean = true,
    val morningAlarmHour: Int = 6,
    val morningAlarmMinute: Int = 0,
    val morningAlarmTimeText: String = "06:00 AM",
    val morningTargetDistanceKm: Double = 2.0,
    val morningTargetPaceMinPerKm: Float = 6.0f
)

class LockGateViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as DisciplineApplication
    private val securePrefs = app.securePreferences
    private val mathEngine = app.mathEngineManager
    val habitTaskManager = app.habitTaskManager
    val disciplineModeManager = app.disciplineModeManager
    val localWebhookServer = app.localWebhookServer
    val appsWhitelistManager = app.appsWhitelistManager
    val webhookServerIp = localWebhookServer.serverIp
    val webhookActivePort = localWebhookServer.activePort
    val installedApps = appsWhitelistManager.installedApps
    val isLoadingApps = appsWhitelistManager.isLoading

    private val _uiState = MutableStateFlow(
        LockGateUiState(
            selectedTab = LockTab.MISSIONS,
            isGateEnabled = securePrefs.isGateEnabled,
            mathQuizState = mathEngine.quizState.value,
            isMathEngineEnabled = securePrefs.isMathEngineEnabled,
            isMathQuizPassed = mathEngine.hasPassedQuiz(),
            isMathBlocked = mathEngine.isCurrentlyBlocked(),
            mathBlockRemainingSeconds = mathEngine.getBlockRemainingSeconds(),
            mathNumQuestions = securePrefs.mathNumQuestions,
            mathTimeLimitSeconds = securePrefs.mathTimeLimitSeconds,
            mathAvailableQuestionCount = mathEngine.questionRepository.getAvailableQuestionCount(),
            gateStatus = disciplineModeManager.gateStatus.value,
            habits = habitTaskManager.snapshot.value.habits,
            dailyTasks = habitTaskManager.snapshot.value.dailyTasks,
            overallStreak = habitTaskManager.snapshot.value.overallStreak,
            dailyResetTimeText = securePrefs.formattedDailyResetTime,
            dailyResetHour = securePrefs.dailyResetHour,
            dailyResetMinute = securePrefs.dailyResetMinute,
            morningAlarmEnabled = securePrefs.isMorningAlarmEnabled,
            morningAlarmHour = securePrefs.morningAlarmHour,
            morningAlarmMinute = securePrefs.morningAlarmMinute,
            morningAlarmTimeText = String.format(java.util.Locale.getDefault(), "%02d:%02d %s",
                if (securePrefs.morningAlarmHour == 0) 12 else if (securePrefs.morningAlarmHour > 12) securePrefs.morningAlarmHour - 12 else securePrefs.morningAlarmHour,
                securePrefs.morningAlarmMinute,
                if (securePrefs.morningAlarmHour < 12) "AM" else "PM"
            ),
            morningTargetDistanceKm = securePrefs.morningTargetDistanceMeters / 1000.0,
            morningTargetPaceMinPerKm = securePrefs.morningTargetPaceMinPerKm
        )
    )
    val uiState: StateFlow<LockGateUiState> = _uiState.asStateFlow()

    init {
        mathEngine.numQuestions = securePrefs.mathNumQuestions
        mathEngine.timeLimitSeconds = securePrefs.mathTimeLimitSeconds
        mathEngine.isMathEngineEnabled = securePrefs.isMathEngineEnabled

        // Collect Math Engine state
        viewModelScope.launch {
            mathEngine.quizState.collect { quiz ->
                _uiState.update {
                    it.copy(
                        mathQuizState = quiz,
                        isMathQuizPassed = mathEngine.hasPassedQuiz(),
                        isMathBlocked = mathEngine.isCurrentlyBlocked(),
                        mathBlockRemainingSeconds = mathEngine.getBlockRemainingSeconds()
                    )
                }
            }
        }

        // Collect Gate Status
        viewModelScope.launch {
            disciplineModeManager.gateStatus.collect { status ->
                _uiState.update { it.copy(gateStatus = status) }
            }
        }

        // Collect Habits and Daily Tasks
        viewModelScope.launch {
            habitTaskManager.snapshot.collect { snap ->
                _uiState.update {
                    it.copy(
                        habits = snap.habits,
                        dailyTasks = snap.dailyTasks,
                        overallStreak = snap.overallStreak
                    )
                }
            }
        }

        // Preload installed applications for whitelist settings
        viewModelScope.launch {
            appsWhitelistManager.refreshInstalledApps()
        }
    }

    fun selectTab(tab: LockTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    // Math Actions
    fun startMathQuiz() {
        mathEngine.startQuiz()
    }

    fun submitMathAnswer(answer: String) {
        mathEngine.submitAnswer(answer)
        disciplineModeManager.reEvaluateGateStatus()
    }

    fun submitMathMcq(index: Int) {
        mathEngine.submitMcqAnswer(index)
        disciplineModeManager.reEvaluateGateStatus()
    }

    fun selectMathMcqOption(index: Int) {
        mathEngine.selectMcqOption(index)
    }

    fun resetMathQuestionPool() {
        mathEngine.questionRepository.resetRemovedQuestions()
        _uiState.update { it.copy(mathAvailableQuestionCount = mathEngine.questionRepository.getAvailableQuestionCount()) }
    }

    fun saveMathSettings(numQuestions: Int, timeLimitSeconds: Int) {
        securePrefs.mathNumQuestions = numQuestions
        securePrefs.mathTimeLimitSeconds = timeLimitSeconds
        mathEngine.numQuestions = numQuestions
        mathEngine.timeLimitSeconds = timeLimitSeconds
        _uiState.update {
            it.copy(mathNumQuestions = numQuestions, mathTimeLimitSeconds = timeLimitSeconds)
        }
    }

    // Missions Actions
    fun toggleDailyTask(taskId: Long) {
        habitTaskManager.toggleDailyTask(taskId)
        disciplineModeManager.reEvaluateGateStatus()
    }

    fun addDailyTask(title: String) {
        habitTaskManager.addDailyTask(title)
        disciplineModeManager.reEvaluateGateStatus()
    }

    fun deleteDailyTask(taskId: Long) {
        habitTaskManager.deleteDailyTask(taskId)
        disciplineModeManager.reEvaluateGateStatus()
    }

    fun toggleHabit(habitId: Long) {
        habitTaskManager.toggleHabit(habitId)
        disciplineModeManager.reEvaluateGateStatus()
    }

    // Webhook and Settings Actions
    fun updateWebhookPort(port: Int) {
        securePrefs.webhookPort = port
        localWebhookServer.restart(port)
    }

    fun updateDailyResetTime(hour: Int, minute: Int) {
        securePrefs.dailyResetHour = hour
        securePrefs.dailyResetMinute = minute
        _uiState.update {
            it.copy(
                dailyResetHour = hour,
                dailyResetMinute = minute,
                dailyResetTimeText = securePrefs.formattedDailyResetTime
            )
        }
        app.dailyResetManager.scheduleDailyReset()
    }

    fun updateMorningAlarmTime(hour: Int, minute: Int) {
        securePrefs.morningAlarmHour = hour
        securePrefs.morningAlarmMinute = minute
        val amPm = if (hour < 12) "AM" else "PM"
        val displayH = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        val text = String.format(java.util.Locale.getDefault(), "%02d:%02d %s", displayH, minute, amPm)
        _uiState.update {
            it.copy(
                morningAlarmHour = hour,
                morningAlarmMinute = minute,
                morningAlarmTimeText = text
            )
        }
    }

    fun updateMorningTargets(distanceKm: Double, paceMinPerKm: Float) {
        securePrefs.morningTargetDistanceMeters = distanceKm * 1000.0
        securePrefs.morningTargetPaceMinPerKm = paceMinPerKm
        _uiState.update {
            it.copy(
                morningTargetDistanceKm = distanceKm,
                morningTargetPaceMinPerKm = paceMinPerKm
            )
        }
    }

    // Apps Whitelist Actions
    fun toggleWhitelist(packageName: String) {
        appsWhitelistManager.toggleWhitelist(packageName)
    }

    fun launchApp(packageName: String): Boolean {
        return appsWhitelistManager.launchApp(packageName)
    }

    fun refreshInstalledApps() {
        viewModelScope.launch {
            appsWhitelistManager.refreshInstalledApps()
        }
    }
}
