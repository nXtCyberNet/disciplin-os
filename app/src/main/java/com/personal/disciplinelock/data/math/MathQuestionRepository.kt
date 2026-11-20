package com.personal.disciplinelock.data.math

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * Repository that manages the math question bank.
 * 
 * - Loads questions from a bundled JSON asset file (assets/math_questions.json)
 * - Tracks which questions have been correctly answered (and removes them from pool)
 * - Provides random question selection excluding already-used questions in current session
 * - Persists the "used question IDs" so correctly answered questions are permanently removed
 */
class MathQuestionRepository(private val context: Context) {

    companion object {
        private const val TAG = "MathQuestionRepo"
        private const val ASSET_FILE = "math_questions.json"
        private const val PREFS_NAME = "math_question_prefs"
        private const val KEY_REMOVED_IDS = "removed_question_ids"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private var allQuestions: List<MathQuestionEntity> = emptyList()
    private var sessionUsedIds: MutableSet<Int> = mutableSetOf()

    init {
        loadQuestions()
    }

    /**
     * Load all questions from the bundled JSON asset.
     */
    private fun loadQuestions() {
        try {
            val jsonString = context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
            val bank = json.decodeFromString<QuestionBank>(jsonString)
            allQuestions = bank.questions
            Log.i(TAG, "Loaded ${allQuestions.size} questions from asset")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to load questions from asset: ${e.message}", e)
            allQuestions = emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse questions JSON: ${e.message}", e)
            allQuestions = emptyList()
        }
    }

    /**
     * Get the set of permanently removed question IDs (correctly answered previously).
     */
    fun getRemovedIds(): Set<Int> {
        val raw = prefs.getString(KEY_REMOVED_IDS, "") ?: ""
        if (raw.isBlank()) return emptySet()
        return raw.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
    }

    /**
     * Get all available questions (excluding permanently removed ones).
     */
    fun getAvailableQuestions(): List<MathQuestionEntity> {
        val removedIds = getRemovedIds()
        return allQuestions.filter { it.id !in removedIds }
    }

    /**
     * Get a random question that hasn't been used in this session
     * and hasn't been permanently removed.
     * Returns null if no questions are available.
     */
    fun getRandomQuestion(): MathQuestionEntity? {
        val removedIds = getRemovedIds()
        val available = allQuestions.filter { it.id !in removedIds && it.id !in sessionUsedIds }
        
        if (available.isEmpty()) {
            // If all non-removed questions have been used in session, reset session tracking
            sessionUsedIds.clear()
            val fallback = allQuestions.filter { it.id !in removedIds }
            if (fallback.isEmpty()) return null
            val picked = fallback.random()
            sessionUsedIds.add(picked.id)
            return picked
        }

        val picked = available.random()
        sessionUsedIds.add(picked.id)
        return picked
    }

    /**
     * Mark a question as correctly answered - permanently removes it from the pool.
     * This ensures the user can't memorize answers.
     */
    fun markAsCorrectlyAnswered(questionId: Int) {
        val current = getRemovedIds().toMutableSet()
        current.add(questionId)
        prefs.edit().putString(KEY_REMOVED_IDS, current.joinToString(",")).apply()
        Log.i(TAG, "Permanently removed question $questionId. Total removed: ${current.size}")
    }

    /**
     * Mark multiple questions as correctly answered (batch removal after quiz completion).
     */
    fun markMultipleAsCorrectlyAnswered(questionIds: List<Int>) {
        val current = getRemovedIds().toMutableSet()
        current.addAll(questionIds)
        prefs.edit().putString(KEY_REMOVED_IDS, current.joinToString(",")).apply()
        Log.i(TAG, "Permanently removed ${questionIds.size} questions. Total removed: ${current.size}")
    }

    /**
     * Reset session tracking (called when starting a new quiz).
     */
    fun resetSession() {
        sessionUsedIds.clear()
    }

    /**
     * Get total count of questions in the bank.
     */
    fun getTotalQuestionCount(): Int = allQuestions.size

    /**
     * Get count of remaining available questions.
     */
    fun getAvailableQuestionCount(): Int = getAvailableQuestions().size

    /**
     * Reset all removed questions (admin function to replenish the pool).
     */
    fun resetRemovedQuestions() {
        prefs.edit().remove(KEY_REMOVED_IDS).apply()
        Log.i(TAG, "Reset all removed questions. Full pool restored.")
    }
}
