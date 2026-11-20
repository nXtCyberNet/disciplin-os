package com.personal.disciplinelock.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personal.disciplinelock.ui.LockGateUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: LockGateUiState,
    isNormalMode: Boolean = true,
    serverIp: String = "127.0.0.1",
    serverPort: Int = 8080,
    onNavigateBack: () -> Unit,
    onNavigateToWhitelist: () -> Unit,
    whitelistedCount: Int = 0,
    onSaveMathSettings: (numQuestions: Int, timeLimitSeconds: Int) -> Unit,
    onResetMathPool: () -> Unit,
    onUpdateWebhookPort: (Int) -> Unit,
    onUpdateDailyResetTime: (hour: Int, minute: Int) -> Unit,
    onUpdateMorningAlarmTime: (hour: Int, minute: Int) -> Unit = { _, _ -> },
    onUpdateMorningTargets: (distanceKm: Double, paceMinPerKm: Float) -> Unit = { _, _ -> }
) {
    BackHandler { onNavigateBack() }

    var questionsCount by remember(uiState.mathNumQuestions) { mutableStateOf(uiState.mathNumQuestions.toFloat()) }
    var timeLimitSec by remember(uiState.mathTimeLimitSeconds) { mutableStateOf(uiState.mathTimeLimitSeconds.toFloat()) }
    var webhookPortText by remember(serverPort) { mutableStateOf(serverPort.toString()) }

    var selectedHour by remember(uiState.dailyResetHour) { mutableIntStateOf(uiState.dailyResetHour) }
    var selectedMinute by remember(uiState.dailyResetMinute) { mutableIntStateOf(uiState.dailyResetMinute) }
    var showTimePicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings & Configuration", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF161B22),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        containerColor = Color(0xFF0D1117)
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Math Engine Settings Card
            item {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Calculate, contentDescription = null, tint = Color(0xFF58A6FF))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("🧠 Math Engine Settings", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                        }

                        Text("Configure math challenge difficulty and time limits.", color = Color.Gray, fontSize = 12.sp)

                        Text("Questions per Quiz: ${questionsCount.toInt()}", color = Color(0xFFE6EDF3), fontSize = 13.sp)
                        Slider(
                            value = questionsCount,
                            onValueChange = {
                                questionsCount = it
                                onSaveMathSettings(it.toInt(), timeLimitSec.toInt())
                            },
                            valueRange = 1f..20f,
                            steps = 19,
                            colors = SliderDefaults.colors(thumbColor = Color(0xFF58A6FF), activeTrackColor = Color(0xFF58A6FF))
                        )

                        Text("Time Limit: ${timeLimitSec.toInt()} seconds (${timeLimitSec.toInt() / 60}m)", color = Color(0xFFE6EDF3), fontSize = 13.sp)
                        Slider(
                            value = timeLimitSec,
                            onValueChange = {
                                timeLimitSec = it
                                onSaveMathSettings(questionsCount.toInt(), it.toInt())
                            },
                            valueRange = 60f..1800f,
                            steps = 28,
                            colors = SliderDefaults.colors(thumbColor = Color(0xFF58A6FF), activeTrackColor = Color(0xFF58A6FF))
                        )

                        OutlinedButton(
                            onClick = onResetMathPool,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF58A6FF))
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Reset Question Pool (${uiState.mathAvailableQuestionCount} Available)")
                        }
                    }
                }
            }

            // Webhook Receiver Settings Card
            item {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lan, contentDescription = null, tint = Color(0xFF3FB950))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("⚡ Inbound Webhook Server", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                        }

                        Text("Receives automated completion signals from PC dispatcher (GitHub PRs, YouTube watch, etc.).", color = Color.Gray, fontSize = 12.sp)

                        Text("Active Endpoint: http://$serverIp:$serverPort", fontFamily = FontFamily.Monospace, color = Color(0xFF3FB950), fontSize = 12.sp)

                        OutlinedTextField(
                            value = webhookPortText,
                            onValueChange = {
                                webhookPortText = it
                                it.toIntOrNull()?.let { p -> if (p in 1024..65535) onUpdateWebhookPort(p) }
                            },
                            label = { Text("Webhook Port", color = Color.Gray) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF3FB950),
                                unfocusedBorderColor = Color(0xFF30363D)
                            )
                        )
                    }
                }
            }

            // Permitted Apps Whitelist Settings Card
            item {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Apps, contentDescription = null, tint = Color(0xFF58A6FF))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("📱 Apps Whitelist", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                            }
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0x333FB950)
                            ) {
                                Text(
                                    "$whitelistedCount Allowed",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF3FB950),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Text(
                            "Choose which apps remain open and permitted while lock gates are active. Add or close/remove apps anytime.",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )

                        Button(
                            onClick = onNavigateToWhitelist,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D))
                        ) {
                            Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF58A6FF))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Manage Allowed Apps & Close ($whitelistedCount Allowed)", color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            }

            // Daily Reset Cycle Card
            item {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Schedule, contentDescription = null, tint = Color(0xFFD29922))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("⏰ Daily Reset Cycle", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                        }

                        Text("Time when daily obligations refresh and lock state re-engages for the day.", color = Color.Gray, fontSize = 12.sp)

                        Text("Scheduled Reset Time: ${uiState.dailyResetTimeText}", fontWeight = FontWeight.Bold, color = Color(0xFFD29922), fontSize = 14.sp)

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onUpdateDailyResetTime(0, 0) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D))
                            ) {
                                Text("12:00 AM", fontSize = 12.sp)
                            }
                            Button(
                                onClick = { onUpdateDailyResetTime(4, 0) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D))
                            ) {
                                Text("04:00 AM", fontSize = 12.sp)
                            }
                            Button(
                                onClick = { onUpdateDailyResetTime(6, 0) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D))
                            ) {
                                Text("06:00 AM", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // Morning Alarm & Target Run Card
            item {
                var localDistKm by remember(uiState.morningTargetDistanceKm) { mutableStateOf(uiState.morningTargetDistanceKm.toFloat()) }
                var localPaceMin by remember(uiState.morningTargetPaceMinPerKm) { mutableStateOf(uiState.morningTargetPaceMinPerKm) }

                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.DirectionsRun, contentDescription = null, tint = Color(0xFFF78166))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("🏃 Morning Alarm & Run Target", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                        }

                        Text("Synced live with GPSLogger HUD over port 8080. If target pace is not matched, morning alarm will not stop and restarts!", color = Color.Gray, fontSize = 12.sp)

                        Text("Scheduled Morning Alarm: ${uiState.morningAlarmTimeText}", fontWeight = FontWeight.Bold, color = Color(0xFFF78166), fontSize = 14.sp)

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onUpdateMorningAlarmTime(5, 30) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D))
                            ) {
                                Text("05:30 AM", fontSize = 11.sp)
                            }
                            Button(
                                onClick = { onUpdateMorningAlarmTime(6, 0) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D))
                            ) {
                                Text("06:00 AM", fontSize = 11.sp)
                            }
                            Button(
                                onClick = { onUpdateMorningAlarmTime(6, 30) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D))
                            ) {
                                Text("06:30 AM", fontSize = 11.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Target Distance: ${String.format(java.util.Locale.US, "%.1f", localDistKm)} km", color = Color(0xFFE6EDF3), fontSize = 13.sp)
                        Slider(
                            value = localDistKm,
                            onValueChange = {
                                localDistKm = it
                                onUpdateMorningTargets(it.toDouble(), localPaceMin)
                            },
                            valueRange = 0.5f..10.0f,
                            steps = 18,
                            colors = SliderDefaults.colors(thumbColor = Color(0xFFF78166), activeTrackColor = Color(0xFFF78166))
                        )

                        Text("Target Pace: < ${String.format(java.util.Locale.US, "%.1f", localPaceMin)} min/km", color = Color(0xFFE6EDF3), fontSize = 13.sp)
                        Slider(
                            value = localPaceMin,
                            onValueChange = {
                                localPaceMin = it
                                onUpdateMorningTargets(localDistKm.toDouble(), it)
                            },
                            valueRange = 4.0f..10.0f,
                            steps = 11,
                            colors = SliderDefaults.colors(thumbColor = Color(0xFFF78166), activeTrackColor = Color(0xFFF78166))
                        )
                    }
                }
            }
        }
    }
}
