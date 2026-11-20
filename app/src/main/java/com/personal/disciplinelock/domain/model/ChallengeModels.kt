package com.personal.disciplinelock.domain.model

enum class ChallengeCategory {
    MULTI_STEP_ARITHMETIC,
    COMBINATORICS,
    DISCRETE_PROBABILITY
}

data class ChallengeQuestion(
    val id: String,
    val prompt: String,
    val expectedAnswer: Long,
    val category: ChallengeCategory,
    val unitOrFormatNote: String = "Enter integer answer",
    val hintOrExplanation: String = ""
)

data class ChallengeSessionState(
    val currentQuestion: ChallengeQuestion,
    val consecutiveCorrect: Int,
    val requiredConsecutive: Int = 3,
    val totalAttemptsInSession: Int = 0,
    val sessionStartTime: Long = System.currentTimeMillis(),
    val isCooldownActive: Boolean = false,
    val cooldownRemainingSeconds: Long = 0,
    val lastErrorFeedback: String? = null
)
