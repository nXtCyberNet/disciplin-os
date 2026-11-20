package com.personal.disciplinelock.ui.missions

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personal.disciplinelock.domain.mode.DisciplineGateStatus
import com.personal.disciplinelock.domain.task.DailyTaskItem
import com.personal.disciplinelock.domain.task.HabitItem
import com.personal.disciplinelock.domain.whitelist.WhitelistAppItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MissionsScreen(
    habits: List<HabitItem>,
    dailyTasks: List<DailyTaskItem>,
    gateStatus: DisciplineGateStatus = DisciplineGateStatus(),
    dailyResetFormattedTime: String = "12:00 AM",
    isNormalMode: Boolean = false,
    serverIp: String = "127.0.0.1",
    serverPort: Int = 8080,
    isMathPassed: Boolean = false,
    isMathBlocked: Boolean = false,
    mathBlockRemainingSeconds: Long = 0L,
    overallStreak: Int = 0,
    onNavigateToMathQuiz: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onToggleDailyTask: (Long) -> Unit,
    onAddDailyTask: (String) -> Unit,
    onDeleteDailyTask: (Long) -> Unit,
    onToggleHabit: (Long) -> Unit,
    whitelistedApps: List<WhitelistAppItem> = emptyList(),
    onLaunchApp: (String) -> Unit = {},
    onNavigateToWhitelist: () -> Unit = {},
    onToggleTestMode: () -> Unit = {}
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var newMissionTitle by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("DISCIPLINE OS", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = Color.White)
                        Text("Obligations & Lock Gate", fontSize = 11.sp, color = Color.Gray)
                    }
                },
                actions = {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF21262D),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("🔥", fontSize = 12.sp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("$overallStreak days", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF0883E))
                        }
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.LightGray)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0D1117),
                    titleContentColor = Color.White
                )
            )
        },
        containerColor = Color(0xFF0D1117)
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Master Discipline Gate Status Banner
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
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
                                Icon(
                                    imageVector = if (isNormalMode) Icons.Default.LockOpen else Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = if (isNormalMode) Color(0xFF3FB950) else Color(0xFF58A6FF)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    if (isNormalMode) "Normal Phone Unlocked" else "Disciplined Mode Active",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = Color.White
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isNormalMode) Color(0x223FB950) else Color(0x2258A6FF)
                            ) {
                                Text(
                                    "${gateStatus.completedGates}/${gateStatus.totalGates} GATES",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isNormalMode) Color(0xFF3FB950) else Color(0xFF58A6FF),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        LinearProgressIndicator(
                            progress = { gateStatus.progressFraction },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                            color = if (isNormalMode) Color(0xFF3FB950) else Color(0xFF58A6FF),
                            trackColor = Color(0xFF21262D)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Reset: $dailyResetFormattedTime", fontSize = 11.sp, color = Color.Gray)
                            Text(gateStatus.statusSummary, fontSize = 11.sp, color = Color.LightGray)
                        }

                        HorizontalDivider(color = Color(0xFF30363D), thickness = 0.8.dp)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Testing Mode Controller",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    if (isNormalMode) "Phone is unlocked (Normal Mode)" else "Disciplined Mode is simulated",
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                            }
                            Button(
                                onClick = onToggleTestMode,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isNormalMode) Color(0xFF238636) else Color(0xFF1F6FEB)
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = if (isNormalMode) Icons.Default.Lock else Icons.Default.LockOpen,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = Color.White
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    if (isNormalMode) "Test Disciplined" else "Switch to Normal",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }

            // Math Quiz Gate Card
            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Psychology, contentDescription = null, tint = Color(0xFFA371F7))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Math Challenge Gate", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                            }

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isMathBlocked) Color(0x33F85149) else if (isMathPassed) Color(0x223FB950) else Color(0x22A371F7)
                            ) {
                                Text(
                                    if (isMathBlocked) "Blocked (${mathBlockRemainingSeconds}s)" else if (isMathPassed) "Passed ✅" else "Pending ⏳",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isMathBlocked) Color(0xFFF85149) else if (isMathPassed) Color(0xFF3FB950) else Color(0xFFA371F7),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Text(
                            "Solve the interactive math challenge to clear the cognitive focus gate.",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )

                        Button(
                            onClick = onNavigateToMathQuiz,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = if (isMathPassed) Color(0xFF21262D) else Color(0xFFA371F7))
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isMathPassed) "Open Math Practice & Quiz" else "Start Math Quiz Challenge", fontSize = 13.sp)
                        }
                    }
                }
            }

            // Webhook Receiver Card
            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF3FB950)))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Webhook Receiver Active", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("http://$serverIp:$serverPort/webhook/mission/{target}", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color.Gray)
                        }
                    }
                }
            }

            // Permitted Apps Shortcuts
            val allowedApps = whitelistedApps.filter { it.isWhitelisted }
            if (allowedApps.isNotEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Apps, contentDescription = null, tint = Color(0xFF58A6FF), modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Permitted Apps (${allowedApps.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                                }
                                Text(
                                    "Edit Whitelist",
                                    fontSize = 11.sp,
                                    color = Color(0xFF58A6FF),
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clickable { onNavigateToWhitelist() }
                                )
                            }

                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                items(allowedApps, key = { it.packageName }) { app ->
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .width(58.dp)
                                            .clickable { onLaunchApp(app.packageName) }
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(44.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(Color(0xFF21262D)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (app.icon != null) {
                                                Image(
                                                    bitmap = app.icon,
                                                    contentDescription = app.name,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            } else {
                                                Icon(
                                                    Icons.Default.Android,
                                                    contentDescription = null,
                                                    tint = Color(0xFF3FB950),
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            app.name,
                                            fontSize = 10.sp,
                                            color = Color.LightGray,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Section Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val clearedCount = dailyTasks.count { it.isCompleted } + habits.count { it.isCompletedToday }
                    val totalCount = dailyTasks.size + habits.size
                    Text(
                        "DAILY REQUIRED MISSIONS",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    Text("$clearedCount/$totalCount Cleared", fontSize = 11.sp, color = Color(0xFF58A6FF), fontWeight = FontWeight.Bold)
                }
            }

            if (dailyTasks.isEmpty() && habits.isEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("No missions currently active", color = Color.LightGray, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("Tap '+ Add Daily Mission' below or sync from dispatcher", color = Color.Gray, fontSize = 11.sp)
                        }
                    }
                }
            }

            // Daily Tasks List
            items(dailyTasks, key = { "task_${it.id}" }) { task ->
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                    modifier = Modifier.fillMaxWidth().clickable { onToggleDailyTask(task.id) }
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (task.isCompleted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (task.isCompleted) Color(0xFF3FB950) else Color.Gray,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                task.title,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = if (task.isCompleted) Color.Gray else Color.White,
                                textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None
                            )
                            if (!task.verificationStatus.isNullOrBlank()) {
                                Text(task.verificationStatus ?: "", fontSize = 11.sp, color = Color(0xFF3FB950), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (task.isCompleted) {
                            Surface(shape = RoundedCornerShape(6.dp), color = Color(0x223FB950)) {
                                Text("Verified", fontSize = 10.sp, color = Color(0xFF3FB950), fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        } else {
                            Surface(shape = RoundedCornerShape(6.dp), color = Color(0x2258A6FF)) {
                                Text("Pending Webhook", fontSize = 10.sp, color = Color(0xFF58A6FF), fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    }
                }
            }

            // Habits List
            items(habits, key = { "habit_${it.id}" }) { habit ->
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                    modifier = Modifier.fillMaxWidth().clickable { onToggleHabit(habit.id) }
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (habit.isCompletedToday) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (habit.isCompletedToday) Color(0xFF3FB950) else Color.Gray,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                habit.title,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = if (habit.isCompletedToday) Color.Gray else Color.White,
                                textDecoration = if (habit.isCompletedToday) TextDecoration.LineThrough else TextDecoration.None
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(habit.category, fontSize = 11.sp, color = Color.Gray)
                                if (habit.streak > 0) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("🔥 ${habit.streak}d streak", fontSize = 11.sp, color = Color(0xFFF0883E), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // Add Daily Mission Button
            item {
                OutlinedButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF58A6FF))
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Add Daily Mission", fontWeight = FontWeight.Bold)
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Daily Mission", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = newMissionTitle,
                    onValueChange = { newMissionTitle = it },
                    label = { Text("Mission Title") },
                    placeholder = { Text("Enter mission title") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF58A6FF),
                        unfocusedBorderColor = Color(0xFF30363D)
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = newMissionTitle.trim()
                        if (trimmed.isNotEmpty()) {
                            onAddDailyTask(trimmed)
                            newMissionTitle = ""
                            showAddDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF58A6FF))
                ) {
                    Text("Add Mission")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF161B22)
        )
    }
}
