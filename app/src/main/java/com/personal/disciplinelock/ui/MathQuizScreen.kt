package com.personal.disciplinelock.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personal.disciplinelock.data.math.AnswerType
import com.personal.disciplinelock.domain.math.MathQuizState

/**
 * The main Math Quiz screen composable.
 * Displayed as a tab within the LockGateScreen.
 */
@Composable
fun MathQuizTabContent(
    quizState: MathQuizState,
    onStartQuiz: () -> Unit,
    onSubmitAnswer: (String) -> Unit,
    onSubmitMcq: (Int) -> Unit,
    onSelectMcqOption: (Int) -> Unit,
    mathEngineEnabled: Boolean,
    quizPassed: Boolean
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        item {
            Text(
                "\uD83E\uDDE0 Math Engine",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontSize = 20.sp
            )
            Text(
                "Complete the math quiz to unlock app access. Wrong answers will block the phone for 5 minutes.",
                color = Color.LightGray,
                fontSize = 12.sp
            )
        }

        // Status Badge
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!mathEngineEnabled) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF37474F)
                    ) {
                        Text(
                            "Math Engine: DISABLED",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                } else if (quizPassed) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF1B5E20)
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF69F0AE), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "Quiz Passed - Apps Unlocked",
                                color = Color(0xFF69F0AE),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFFB71C1C)
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFFFF5252), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "Quiz Required - Apps Locked",
                                color = Color(0xFFFF5252),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Text(
                    "Pool: ${quizState.availableQuestionCount} Q's",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
            }
        }

        // Block Screen
        if (quizState.isBlocked) {
            item { BlockedScreen(quizState) }
        }
        // Quiz Completed Screen
        else if (quizState.quizCompleted) {
            item { QuizCompletedScreen(quizState, onStartQuiz) }
        }
        // Active Quiz
        else if (quizState.isQuizActive && quizState.currentQuestion != null) {
            // Timer & Progress
            item { QuizProgressHeader(quizState) }
            // Question Card
            item {
                QuestionCard(
                    quizState = quizState,
                    onSubmitAnswer = onSubmitAnswer,
                    onSubmitMcq = onSubmitMcq,
                    onSelectMcqOption = onSelectMcqOption
                )
            }
        }
        // Start Quiz Button
        else if (!quizPassed && mathEngineEnabled) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2229))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Quiz,
                            contentDescription = null,
                            tint = Color(0xFF64B5F6),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Ready for the Math Challenge?",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "${quizState.totalQuestions} questions \u2022 ${quizState.timeLimitSeconds / 60} min time limit\nWrong answer = 5 min phone block",
                            color = Color.Gray,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onStartQuiz,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
                            modifier = Modifier.fillMaxWidth(0.7f)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Start Quiz", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                }
            }
        }

        // Feedback
        val feedback = quizState.lastFeedback
        if (!feedback.isNullOrEmpty() && !quizState.isBlocked) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (quizState.lastFeedbackIsError) Color(0xFF3E2723) else Color(0xFF1B5E20)
                    )
                ) {
                    Text(
                        feedback,
                        color = if (quizState.lastFeedbackIsError) Color(0xFFFFB74D) else Color(0xFFC8E6C9),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun BlockedScreen(quizState: MathQuizState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFB71C1C))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Block,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "\uD83D\uDD12 PHONE BLOCKED",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Wrong answer detected. Phone is locked.",
                color = Color(0xFFFFCDD2),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Big countdown timer
            val minutes = quizState.blockRemainingSeconds / 60
            val seconds = quizState.blockRemainingSeconds % 60
            Text(
                String.format("%02d:%02d", minutes, seconds),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = Color.White,
                fontSize = 56.sp
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Quiz will restart automatically when block expires",
                color = Color(0xFFFFCDD2),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )

            // Show the feedback about the wrong answer
            val feedback = quizState.lastFeedback
            if (!feedback.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF880E0E))
                ) {
                    Text(
                        feedback,
                        color = Color(0xFFFFCDD2),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun QuizCompletedScreen(quizState: MathQuizState, onStartQuiz: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B5E20))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.EmojiEvents,
                contentDescription = null,
                tint = Color(0xFFFFD54F),
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "\uD83C\uDF89 Quiz Complete!",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "All ${quizState.totalQuestions} questions answered correctly!\nApps are now unlocked. Previously answered questions have been removed from the pool.",
                color = Color(0xFFC8E6C9),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Remaining questions in pool: ${quizState.availableQuestionCount}",
                color = Color(0xFFA5D6A7),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun QuizProgressHeader(quizState: MathQuizState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2229))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Progress bar
            val progress by animateFloatAsState(
                targetValue = quizState.currentQuestionIndex.toFloat() / quizState.totalQuestions.toFloat(),
                label = "progress"
            )
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = Color(0xFF69F0AE),
                trackColor = Color(0xFF263238)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Question counter
                Text(
                    "Question ${quizState.currentQuestionIndex + 1} / ${quizState.totalQuestions}",
                    color = Color(0xFF64B5F6),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )

                // Timer
                val minutes = quizState.timeRemainingSeconds / 60
                val seconds = quizState.timeRemainingSeconds % 60
                val timerColor = when {
                    quizState.timeRemainingSeconds <= 30 -> Color(0xFFFF5252)
                    quizState.timeRemainingSeconds <= 60 -> Color(0xFFFFB74D)
                    else -> Color(0xFF69F0AE)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Timer,
                        contentDescription = null,
                        tint = timerColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        String.format("%02d:%02d", minutes, seconds),
                        color = timerColor,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    )
                }
            }

            // Step indicators
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (i in 0 until quizState.totalQuestions) {
                    val isDone = i < quizState.currentQuestionIndex
                    val isCurrent = i == quizState.currentQuestionIndex
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                when {
                                    isDone -> Color(0xFF69F0AE)
                                    isCurrent -> Color(0xFF1976D2)
                                    else -> Color(0xFF37474F)
                                }
                            )
                    )
                }
            }
        }
    }
}

@Composable
fun QuestionCard(
    quizState: MathQuizState,
    onSubmitAnswer: (String) -> Unit,
    onSubmitMcq: (Int) -> Unit,
    onSelectMcqOption: (Int) -> Unit
) {
    val question = quizState.currentQuestion ?: return
    var directAnswer by remember(question.id) { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2229))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Category & Type badge
            Row {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF1565C0)
                ) {
                    Text(
                        question.category.uppercase(),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = when (question.answerType) {
                        AnswerType.MCQ -> Color(0xFF6A1B9A)
                        AnswerType.DIRECT_INPUT -> Color(0xFFE65100)
                    }
                ) {
                    Text(
                        if (question.answerType == AnswerType.MCQ) "MCQ" else "TYPE ANSWER",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = when (question.difficulty) {
                        com.personal.disciplinelock.data.math.DifficultyLevel.EASY -> Color(0xFF2E7D32)
                        com.personal.disciplinelock.data.math.DifficultyLevel.MEDIUM -> Color(0xFFF57F17)
                        com.personal.disciplinelock.data.math.DifficultyLevel.HARD -> Color(0xFFC62828)
                    }
                ) {
                    Text(
                        question.difficulty.name,
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Question text
            Text(
                question.question,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontSize = 16.sp,
                lineHeight = 24.sp
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Answer section
            when (question.answerType) {
                AnswerType.MCQ -> {
                    McqAnswerSection(
                        options = question.options,
                        selectedIndex = quizState.selectedMcqOption,
                        onSelectOption = onSelectMcqOption,
                        onSubmit = onSubmitMcq
                    )
                }
                AnswerType.DIRECT_INPUT -> {
                    DirectInputSection(
                        answer = directAnswer,
                        onAnswerChange = { directAnswer = it },
                        onSubmit = {
                            if (directAnswer.isNotBlank()) {
                                onSubmitAnswer(directAnswer)
                                directAnswer = ""
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun McqAnswerSection(
    options: List<String>,
    selectedIndex: Int,
    onSelectOption: (Int) -> Unit,
    onSubmit: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { index, option ->
            val isSelected = index == selectedIndex
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectOption(index) }
                    .then(
                        if (isSelected) Modifier.border(
                            2.dp,
                            Color(0xFF64B5F6),
                            RoundedCornerShape(12.dp)
                        ) else Modifier
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) Color(0xFF1A237E) else Color(0xFF263238)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Option letter (A, B, C, D)
                    Surface(
                        shape = CircleShape,
                        color = if (isSelected) Color(0xFF1976D2) else Color(0xFF37474F),
                        modifier = Modifier.size(28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Text(
                                ('A' + index).toString(),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        option,
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { if (selectedIndex >= 0) onSubmit(selectedIndex) },
            enabled = selectedIndex >= 0,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF1976D2),
                disabledContainerColor = Color(0xFF37474F)
            )
        ) {
            Text("Submit Answer", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun DirectInputSection(
    answer: String,
    onAnswerChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    Column {
        OutlinedTextField(
            value = answer,
            onValueChange = onAnswerChange,
            label = { Text("Your Answer") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF64B5F6),
                unfocusedBorderColor = Color.Gray
            ),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onSubmit,
            enabled = answer.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF1976D2),
                disabledContainerColor = Color(0xFF37474F)
            )
        ) {
            Text("Submit Answer", fontWeight = FontWeight.Bold)
        }
    }
}
