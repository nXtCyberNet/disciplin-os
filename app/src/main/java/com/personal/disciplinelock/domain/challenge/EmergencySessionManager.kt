package com.personal.disciplinelock.domain.challenge

import android.content.Context
import android.content.SharedPreferences
import com.personal.disciplinelock.domain.model.ChallengeCategory
import com.personal.disciplinelock.domain.model.ChallengeQuestion
import com.personal.disciplinelock.domain.model.ChallengeSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class EmergencySessionManager(
    private val context: Context,
    private val emergencyWindowMinutes: Long = 15L
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("emergency_tracker", Context.MODE_PRIVATE)

    private val _sessionState = MutableStateFlow(createNewSession())
    val sessionState: StateFlow<ChallengeSessionState> = _sessionState.asStateFlow()

    companion object {
        private const val KEY_UNLOCK_HISTORY = "unlock_history_json"
        private const val KEY_EMERGENCY_UNLOCKED_UNTIL = "emergency_unlocked_until"
        private const val ROLLING_WINDOW_HOURS = 24L
        const val REQUIRED_CONSECUTIVE = 3
    }

    fun getUnlockedUntilTimestamp(): Long {
        return prefs.getLong(KEY_EMERGENCY_UNLOCKED_UNTIL, 0L)
    }

    fun isCurrentlyEmergencyUnlocked(): Boolean {
        return System.currentTimeMillis() < getUnlockedUntilTimestamp()
    }

    fun getRemainingEmergencyWindowMillis(): Long {
        val until = getUnlockedUntilTimestamp()
        val now = System.currentTimeMillis()
        return if (until > now) until - now else 0L
    }

    fun getRecentUnlocksCount(): Int {
        val now = System.currentTimeMillis()
        val cutoff = now - TimeUnit.HOURS.toMillis(ROLLING_WINDOW_HOURS)
        val history = getUnlockHistory().filter { it >= cutoff }
        return history.size
    }

    fun getCooldownSeconds(): Long {
        val count = getRecentUnlocksCount()
        val history = getUnlockHistory()
        if (history.isEmpty()) return 0L
        val lastUnlock = history.last()
        val now = System.currentTimeMillis()

        val requiredCooldownMs = when {
            count >= 3 -> TimeUnit.MINUTES.toMillis(15)
            count == 2 -> TimeUnit.MINUTES.toMillis(5)
            else -> 0L
        }

        val elapsed = now - lastUnlock
        return if (elapsed < requiredCooldownMs) {
            (requiredCooldownMs - elapsed) / 1000L
        } else {
            0L
        }
    }

    fun createNewSession(): ChallengeSessionState {
        val cooldownSec = getCooldownSeconds()
        val question = MathChallengeGenerator.generate()
        return ChallengeSessionState(
            currentQuestion = question,
            consecutiveCorrect = 0,
            requiredConsecutive = REQUIRED_CONSECUTIVE,
            isCooldownActive = cooldownSec > 0,
            cooldownRemainingSeconds = cooldownSec
        )
    }

    fun checkCooldown() {
        val cooldown = getCooldownSeconds()
        _sessionState.value = _sessionState.value.copy(
            isCooldownActive = cooldown > 0,
            cooldownRemainingSeconds = cooldown
        )
    }

    sealed class AnswerResult {
        data class Correct(val consecutive: Int, val required: Int, val unlocked: Boolean, val windowMinutes: Long) : AnswerResult()
        data class Incorrect(val expectedAnswer: Long, val explanation: String) : AnswerResult()
        data class CooldownActive(val remainingSeconds: Long) : AnswerResult()
    }

    fun submitAnswer(input: String): AnswerResult {
        val current = _sessionState.value
        val cooldown = getCooldownSeconds()
        if (cooldown > 0) {
            _sessionState.value = current.copy(
                isCooldownActive = true,
                cooldownRemainingSeconds = cooldown
            )
            return AnswerResult.CooldownActive(cooldown)
        }

        val isCorrect = MathChallengeGenerator.verifyAnswer(current.currentQuestion, input)

        return if (isCorrect) {
            val newConsecutive = current.consecutiveCorrect + 1
            if (newConsecutive >= REQUIRED_CONSECUTIVE) {
                // Grant emergency window
                grantEmergencyUnlock()
                _sessionState.value = createNewSession()
                AnswerResult.Correct(newConsecutive, REQUIRED_CONSECUTIVE, unlocked = true, windowMinutes = emergencyWindowMinutes)
            } else {
                // Progress to next question
                val nextCategory = when (newConsecutive) {
                    1 -> ChallengeCategory.COMBINATORICS
                    2 -> ChallengeCategory.DISCRETE_PROBABILITY
                    else -> null
                }
                val nextQuestion = MathChallengeGenerator.generate(nextCategory)
                _sessionState.value = current.copy(
                    currentQuestion = nextQuestion,
                    consecutiveCorrect = newConsecutive,
                    totalAttemptsInSession = current.totalAttemptsInSession + 1,
                    lastErrorFeedback = null
                )
                AnswerResult.Correct(newConsecutive, REQUIRED_CONSECUTIVE, unlocked = false, windowMinutes = emergencyWindowMinutes)
            }
        } else {
            // Reset consecutive counter on failure
            val explanation = current.currentQuestion.hintOrExplanation
            val nextQuestion = MathChallengeGenerator.generate()
            _sessionState.value = current.copy(
                currentQuestion = nextQuestion,
                consecutiveCorrect = 0,
                totalAttemptsInSession = current.totalAttemptsInSession + 1,
                lastErrorFeedback = "Incorrect: ${current.currentQuestion.prompt.lines().first()} → Answer was ${current.currentQuestion.expectedAnswer}."
            )
            AnswerResult.Incorrect(current.currentQuestion.expectedAnswer, explanation)
        }
    }

    private fun grantEmergencyUnlock() {
        val now = System.currentTimeMillis()
        val unlockedUntil = now + TimeUnit.MINUTES.toMillis(emergencyWindowMinutes)
        prefs.edit().putLong(KEY_EMERGENCY_UNLOCKED_UNTIL, unlockedUntil).apply()

        // Append to history
        val history = getUnlockHistory().toMutableList()
        history.add(now)
        val cutoff = now - TimeUnit.HOURS.toMillis(ROLLING_WINDOW_HOURS)
        val trimmed = history.filter { it >= cutoff }
        val array = JSONArray()
        trimmed.forEach { array.put(it) }
        prefs.edit().putString(KEY_UNLOCK_HISTORY, array.toString()).apply()
    }

    private fun getUnlockHistory(): List<Long> {
        val raw = prefs.getString(KEY_UNLOCK_HISTORY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val list = mutableListOf<Long>()
            for (i in 0 until array.length()) {
                list.add(array.getLong(i))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun revokeEmergencyUnlock() {
        prefs.edit().remove(KEY_EMERGENCY_UNLOCKED_UNTIL).apply()
    }
}
