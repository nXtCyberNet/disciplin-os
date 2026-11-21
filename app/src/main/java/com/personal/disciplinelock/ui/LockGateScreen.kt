package com.personal.disciplinelock.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personal.disciplinelock.domain.mode.DisciplineMode
import com.personal.disciplinelock.ui.missions.MissionsScreen
import com.personal.disciplinelock.ui.settings.SettingsScreen
import com.personal.disciplinelock.ui.whitelist.AppsWhitelistScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockGateScreen(
    viewModel: LockGateViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val isNormalPhone = uiState.gateStatus.currentMode == DisciplineMode.NORMAL_PHONE ||
            uiState.gateStatus.currentMode == DisciplineMode.EMERGENCY_OVERRIDE
    val webhookIp by viewModel.webhookServerIp.collectAsState()
    val webhookPort by viewModel.webhookActivePort.collectAsState()
    val installedApps by viewModel.installedApps.collectAsState()
    val isLoadingApps by viewModel.isLoadingApps.collectAsState()

    when (uiState.selectedTab) {
        LockTab.MISSIONS -> {
            MissionsScreen(
                habits = uiState.habits,
                dailyTasks = uiState.dailyTasks,
                gateStatus = uiState.gateStatus,
                dailyResetFormattedTime = uiState.dailyResetTimeText,
                isNormalMode = isNormalPhone,
                serverIp = webhookIp,
                serverPort = webhookPort,
                isMathPassed = uiState.isMathQuizPassed,
                isMathBlocked = uiState.isMathBlocked,
                mathBlockRemainingSeconds = uiState.mathBlockRemainingSeconds,
                overallStreak = uiState.overallStreak,
                onNavigateToMathQuiz = {
                    viewModel.selectTab(LockTab.MATH_ENGINE)
                    if (!uiState.isMathQuizPassed && !uiState.isMathBlocked && !uiState.mathQuizState.isQuizActive) {
                        viewModel.startMathQuiz()
                    }
                },
                onNavigateToSettings = { viewModel.selectTab(LockTab.SETTINGS) },
                onToggleDailyTask = { viewModel.toggleDailyTask(it) },
                onAddDailyTask = { viewModel.addDailyTask(it) },
                onDeleteDailyTask = { viewModel.deleteDailyTask(it) },
                onToggleHabit = { viewModel.toggleHabit(it) },
                whitelistedApps = installedApps,
                onLaunchApp = { viewModel.launchApp(it) },
                onNavigateToWhitelist = { viewModel.selectTab(LockTab.WHITELIST) }
            )
        }
        LockTab.MATH_ENGINE -> {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("🧠 Math Quiz Engine", color = Color.White, fontSize = 18.sp) },
                        navigationIcon = {
                            IconButton(onClick = { viewModel.selectTab(LockTab.MISSIONS) }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF161B22))
                    )
                },
                containerColor = Color(0xFF0D1117)
            ) { padding ->
                Box(modifier = Modifier.padding(padding).padding(16.dp)) {
                    MathQuizTabContent(
                        quizState = uiState.mathQuizState,
                        onStartQuiz = { viewModel.startMathQuiz() },
                        onSubmitAnswer = { viewModel.submitMathAnswer(it) },
                        onSubmitMcq = { viewModel.submitMathMcq(it) },
                        onSelectMcqOption = { viewModel.selectMathMcqOption(it) },
                        mathEngineEnabled = uiState.isMathEngineEnabled,
                        quizPassed = uiState.isMathQuizPassed
                    )
                }
            }
        }
        LockTab.SETTINGS -> {
            SettingsScreen(
                uiState = uiState,
                isNormalMode = isNormalPhone,
                serverIp = webhookIp,
                serverPort = webhookPort,
                onNavigateBack = { viewModel.selectTab(LockTab.MISSIONS) },
                onNavigateToWhitelist = { viewModel.selectTab(LockTab.WHITELIST) },
                whitelistedCount = installedApps.count { it.isWhitelisted },
                onSaveMathSettings = { numQuestions, timeLimit ->
                    viewModel.saveMathSettings(numQuestions, timeLimit)
                },
                onResetMathPool = { viewModel.resetMathQuestionPool() },
                onUpdateWebhookPort = { viewModel.updateWebhookPort(it) },
                onUpdateDailyResetTime = { h, m -> viewModel.updateDailyResetTime(h, m) },
                onUpdateMorningAlarmTime = { h, m -> viewModel.updateMorningAlarmTime(h, m) },
                onUpdateMorningTargets = { d, p -> viewModel.updateMorningTargets(d, p) }
            )
        }
        LockTab.WHITELIST -> {
            AppsWhitelistScreen(
                apps = installedApps,
                isLoading = isLoadingApps,
                onBack = { viewModel.selectTab(LockTab.SETTINGS) },
                onToggleWhitelist = { viewModel.toggleWhitelist(it) },
                onLaunchApp = { viewModel.launchApp(it) },
                onRefreshApps = { viewModel.refreshInstalledApps() }
            )
        }
    }
}
