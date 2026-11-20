package com.personal.disciplinelock.domain.math

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.personal.disciplinelock.data.math.AnswerType
import com.personal.disciplinelock.data.math.MathQuestionEntity
import com.personal.disciplinelock.data.math.MathQuestionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Represents the current state of a math quiz session.
 */
data class MathQuizState(
    val isQuizActive: Boolean = false,
    val currentQuestion: MathQuestionEntity? = null,
    val currentQuestionIndex: Int = 0,
    val totalQuestions: Int = 5,
    val correctAnswersInSession: MutableList<Int> = mutableListOf(), // question IDs answered correctly
    val timeLimitSeconds: Int = 300, // 5 minutes default
    val timeRemainingSeconds: Int = 300,
    val isBlocked: Boolean = false,
    val blockEndTimeMs: Long = 0L,
    val blockRemainingSeconds: Long = 0L,
    val quizCompleted: Boolean = false,
    val selectedMcqOption: Int = -1, // index of selected MCQ option (-1 = none)
    val lastFeedback: String? = null,
    val lastFeedbackIsError: Boolean = false,
    val availableQuestionCount: Int = 0
)

/**
 * The Math Engine Manager - core component that gates app access behind a math quiz.
 *
 * Flow:
 * 1. Before any app can open, the math quiz must be completed
 * 2. Configurable number of questions and time limit
 * 3. Wrong answer → 5 minute phone block → restart quiz
 * 4. Questions are MCQ or direct-input, loaded from JSON question bank
 * 5. Random selection from pool, correctly answered questions are permanently removed
 * 6. After completing quiz, all correctly answered questions are removed from pool
 */
class MathEngineManager(private val context: Context) {

    companion object {
        private const val TAG = "MathEngineManager"
        private const val PREFS_NAME = "math_engine_prefs"
        private const val KEY_NUM_QUESTIONS = "num_questions"
        private const val KEY_TIME_LIMIT_SECONDS = "time_limit_seconds"
        private const val KEY_BLOCK_UNTIL = "block_until_ms"
        private const val KEY_QUIZ_PASSED = "quiz_passed"
        private const val KEY_QUIZ_PASSED_TIMESTAMP = "quiz_passed_timestamp"
        private const val KEY_MATH_ENGINE_ENABLED = "math_engine_enabled"
        const val BLOCK_DURATION_MS = 5 * 60 * 1000L // 5 minutes
        private const val QUIZ_VALID_DURATION_MS = 24 * 60 * 60 * 1000L // 24 hours
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val questionRepository = MathQuestionRepository(context)

    private val _quizState = MutableStateFlow(MathQuizState())
    val quizState: StateFlow<MathQuizState> = _quizState.asStateFlow()

    // ==================== Configuration ====================

    var numQuestions: Int
        get() = prefs.getInt(KEY_NUM_QUESTIONS, 5)
        set(value) = prefs.edit().putInt(KEY_NUM_QUESTIONS, value.coerceIn(1, 20)).apply()

    var timeLimitSeconds: Int
        get() = prefs.getInt(KEY_TIME_LIMIT_SECONDS, 300)
        set(value) = prefs.edit().putInt(KEY_TIME_LIMIT_SECONDS, value.coerceIn(60, 1800)).apply()

    var isMathEngineEnabled: Boolean
        get() = prefs.getBoolean(KEY_MATH_ENGINE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MATH_ENGINE_ENABLED, value).apply()

    // ==================== Blocking Logic ====================

    /**
     * Check if the phone is currently blocked due to a wrong answer.
     */
    fun isCurrentlyBlocked(): Boolean {
        if (!isMathEngineEnabled) return false
        val blockUntil = prefs.getLong(KEY_BLOCK_UNTIL, 0L)
        return System.currentTimeMillis() < blockUntil
    }

    /**
     * Get remaining block time in seconds.
     */
    fun getBlockRemainingSeconds(): Long {
        if (!isMathEngineEnabled) return 0L
        val blockUntil = prefs.getLong(KEY_BLOCK_UNTIL, 0L)
        val now = System.currentTimeMillis()
        return if (blockUntil > now) (blockUntil - now) / 1000L else 0L
    }

    /**
     * Activate the 5-minute block.
     */
    private fun activateBlock() {
        val blockUntil = System.currentTimeMillis() + BLOCK_DURATION_MS
        prefs.edit().putLong(KEY_BLOCK_UNTIL, blockUntil).apply()
        Log.w(TAG, "Phone BLOCKED for 5 minutes until ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(blockUntil))}")
    }

    /**
     * Immediately clear any active penalty block.
     */
    fun clearBlock() {
        prefs.edit().remove(KEY_BLOCK_UNTIL).apply()
        _quizState.value = _quizState.value.copy(
            isBlocked = false,
            blockRemainingSeconds = 0L,
            blockEndTimeMs = 0L,
            lastFeedback = null
        )
        Log.i(TAG, "Penalty block cleared.")
    }

    /**
     * Clear the block (called when block time expires).
     */
    fun clearBlockIfExpired() {
        if (!isCurrentlyBlocked()) {
            prefs.edit().remove(KEY_BLOCK_UNTIL).apply()
        }
    }

    // ==================== Quiz Pass Status ====================

    /**
     * Check if the quiz has been passed (valid for 24 hours).
     */
    fun hasPassedQuiz(): Boolean {
        if (!isMathEngineEnabled) return true // if disabled, always pass
        val passed = prefs.getBoolean(KEY_QUIZ_PASSED, false)
        if (!passed) return false
        val timestamp = prefs.getLong(KEY_QUIZ_PASSED_TIMESTAMP, 0L)
        val now = System.currentTimeMillis()
        // Quiz pass expires after 24 hours
        return (now - timestamp) < QUIZ_VALID_DURATION_MS
    }

    /**
     * Mark quiz as passed.
     */
    fun markQuizPassed() {
        clearBlock()
        prefs.edit()
            .putBoolean(KEY_QUIZ_PASSED, true)
            .putLong(KEY_QUIZ_PASSED_TIMESTAMP, System.currentTimeMillis())
            .apply()
        _quizState.value = _quizState.value.copy(
            isQuizActive = false,
            quizCompleted = true,
            isBlocked = false,
            blockRemainingSeconds = 0L,
            blockEndTimeMs = 0L,
            lastFeedback = "🎉 Challenge Passed! Apps unlocked."
        )
        Log.i(TAG, "Quiz PASSED - apps are now accessible")
    }

    /**
     * Reset quiz pass status (e.g., for daily reset).
     */
    fun resetQuizPass() {
        prefs.edit()
            .putBoolean(KEY_QUIZ_PASSED, false)
            .remove(KEY_QUIZ_PASSED_TIMESTAMP)
            .apply()
    }

    // ==================== Quiz Session Management ====================

    /**
     * Start a new quiz session.
     */
    fun startQuiz(): Boolean {
        if (isCurrentlyBlocked()) {
            updateBlockState()
            return false
        }

        questionRepository.resetSession()
        val firstQuestion = questionRepository.getRandomQuestion()
        if (firstQuestion == null) {
            Log.w(TAG, "No questions available! Resetting removed questions.")
            questionRepository.resetRemovedQuestions()
            val retryQuestion = questionRepository.getRandomQuestion()
            if (retryQuestion == null) {
                Log.e(TAG, "Still no questions available after reset!")
                return false
            }
            startQuizWithQuestion(retryQuestion)
            return true
        }

        startQuizWithQuestion(firstQuestion)
        return true
    }

    private fun startQuizWithQuestion(question: MathQuestionEntity) {
        val totalQ = numQuestions
        val timeLimit = timeLimitSeconds
        _quizState.value = MathQuizState(
            isQuizActive = true,
            currentQuestion = question,
            currentQuestionIndex = 0,
            totalQuestions = totalQ,
            correctAnswersInSession = mutableListOf(),
            timeLimitSeconds = timeLimit,
            timeRemainingSeconds = timeLimit,
            isBlocked = false,
            quizCompleted = false,
            selectedMcqOption = -1,
            lastFeedback = null,
            availableQuestionCount = questionRepository.getAvailableQuestionCount()
        )
    }

    /**
     * Submit an answer to the current question.
     * Returns true if correct, false if wrong (which triggers a 5-min block + quiz restart).
     */
    fun submitAnswer(answer: String): Boolean {
        val state = _quizState.value
        val question = state.currentQuestion ?: return false

        val isCorrect = checkAnswer(question, answer)

        if (isCorrect) {
            handleCorrectAnswer(question, state)
            return true
        } else {
            handleWrongAnswer(question, state)
            return false
        }
    }

    /**
     * Submit MCQ answer by option index.
     */
    fun submitMcqAnswer(optionIndex: Int): Boolean {
        val state = _quizState.value
        val question = state.currentQuestion ?: return false
        if (question.answerType != AnswerType.MCQ || optionIndex < 0 || optionIndex >= question.options.size) {
            return false
        }
        return submitAnswer(question.options[optionIndex])
    }

    private fun checkAnswer(question: MathQuestionEntity, answer: String): Boolean {
        val cleaned = answer.trim().lowercase()
        val expected = question.correctAnswer.trim().lowercase()

        return when (question.answerType) {
            AnswerType.MCQ -> cleaned == expected
            AnswerType.DIRECT_INPUT -> {
                // Try exact match first
                if (cleaned == expected) return true
                // Try numeric comparison for number answers
                val parsedAnswer = cleaned.toDoubleOrNull()
                val parsedExpected = expected.toDoubleOrNull()
                if (parsedAnswer != null && parsedExpected != null) {
                    kotlin.math.abs(parsedAnswer - parsedExpected) < 0.001
                } else {
                    false
                }
            }
        }
    }

    private fun handleCorrectAnswer(question: MathQuestionEntity, state: MathQuizState) {
        val correctIds = state.correctAnswersInSession.toMutableList()
        correctIds.add(question.id)

        val nextIndex = state.currentQuestionIndex + 1

        if (nextIndex >= state.totalQuestions) {
            // Quiz completed! Remove all correctly answered questions from the pool
            questionRepository.markMultipleAsCorrectlyAnswered(correctIds)
            markQuizPassed()

            _quizState.value = state.copy(
                isQuizActive = false,
                quizCompleted = true,
                correctAnswersInSession = correctIds.toMutableList(),
                currentQuestionIndex = nextIndex,
                lastFeedback = "\uD83C\uDF89 Quiz Complete! All ${state.totalQuestions} questions answered correctly. Apps are now unlocked!",
                lastFeedbackIsError = false,
                selectedMcqOption = -1
            )
            Log.i(TAG, "Quiz COMPLETED. Removed ${correctIds.size} questions from pool.")
        } else {
            // Move to next question
            val nextQuestion = questionRepository.getRandomQuestion()
            _quizState.value = state.copy(
                currentQuestion = nextQuestion ?: state.currentQuestion,
                currentQuestionIndex = nextIndex,
                correctAnswersInSession = correctIds.toMutableList(),
                lastFeedback = "\u2705 Correct! (${nextIndex}/${state.totalQuestions})",
                lastFeedbackIsError = false,
                selectedMcqOption = -1
            )
        }
    }

    private fun handleWrongAnswer(question: MathQuestionEntity, state: MathQuizState) {
        // WRONG ANSWER: Block for 5 minutes and restart quiz
        activateBlock()

        _quizState.value = state.copy(
            isQuizActive = false,
            isBlocked = true,
            blockEndTimeMs = System.currentTimeMillis() + BLOCK_DURATION_MS,
            blockRemainingSeconds = BLOCK_DURATION_MS / 1000L,
            lastFeedback = "\u274C Wrong answer! The correct answer was: ${question.correctAnswer}" +
                    (if (question.explanation.isNotBlank()) "\n${question.explanation}" else "") +
                    "\n\n\uD83D\uDD12 Phone blocked for 5 minutes. Quiz will restart after.",
            lastFeedbackIsError = true,
            selectedMcqOption = -1,
            quizCompleted = false
        )
        Log.w(TAG, "WRONG ANSWER on question ${question.id}. Phone blocked for 5 minutes.")
    }

    /**
     * Update the block countdown state. Call this every second from a ticker.
     */
    fun updateBlockState() {
        val remaining = getBlockRemainingSeconds()
        val blocked = isCurrentlyBlocked()

        if (!blocked && _quizState.value.isBlocked) {
            // Block just expired - auto-start new quiz
            clearBlockIfExpired()
            _quizState.value = _quizState.value.copy(
                isBlocked = false,
                blockRemainingSeconds = 0L,
                blockEndTimeMs = 0L,
                lastFeedback = "Block expired. Starting new quiz...",
                lastFeedbackIsError = false
            )
            startQuiz()
        } else if (blocked) {
            _quizState.value = _quizState.value.copy(
                isBlocked = true,
                blockRemainingSeconds = remaining
            )
        }
    }

    /**
     * Update the quiz timer countdown. Call every second.
     */
    fun tickTimer() {
        val state = _quizState.value
        if (!state.isQuizActive || state.isBlocked) return

        val newTime = state.timeRemainingSeconds - 1
        if (newTime <= 0) {
            // Time's up! Treat as wrong answer - block and restart
            activateBlock()
            _quizState.value = state.copy(
                isQuizActive = false,
                isBlocked = true,
                blockEndTimeMs = System.currentTimeMillis() + BLOCK_DURATION_MS,
                blockRemainingSeconds = BLOCK_DURATION_MS / 1000L,
                timeRemainingSeconds = 0,
                lastFeedback = "\u23F0 Time's up! Phone blocked for 5 minutes. Quiz will restart.",
                lastFeedbackIsError = true,
                quizCompleted = false
            )
            Log.w(TAG, "Quiz TIMED OUT. Phone blocked for 5 minutes.")
        } else {
            _quizState.value = state.copy(timeRemainingSeconds = newTime)
        }
    }

    /**
     * Select an MCQ option (visual feedback before submitting).
     */
    fun selectMcqOption(index: Int) {
        _quizState.value = _quizState.value.copy(selectedMcqOption = index)
    }

    /**
     * Check if the math engine should gate app access right now.
     */
    fun shouldBlockAppAccess(): Boolean {
        if (!isMathEngineEnabled) return false
        if (isCurrentlyBlocked()) return true
        if (hasPassedQuiz()) return false
        return true // Quiz not passed yet
    }
}
