package com.personal.disciplinelock.data.math

import kotlinx.serialization.Serializable

/**
 * Represents the type of answer expected for a math question.
 */
@Serializable
enum class AnswerType {
    /** Multiple choice - user picks from options */
    MCQ,
    /** Direct input - user types the answer */
    DIRECT_INPUT
}

/**
 * Represents a difficulty level for math questions.
 */
@Serializable
enum class DifficultyLevel {
    EASY,
    MEDIUM,
    HARD
}

/**
 * Represents a math question stored in the question bank (JSON file).
 * Questions are pre-authored and randomly selected during a quiz session.
 */
@Serializable
data class MathQuestionEntity(
    val id: Int,
    val question: String,
    val answerType: AnswerType,
    val correctAnswer: String,
    val options: List<String> = emptyList(), // only for MCQ type
    val difficulty: DifficultyLevel = DifficultyLevel.MEDIUM,
    val category: String = "general",
    val explanation: String = ""
)

/**
 * The root container for the question bank JSON file.
 */
@Serializable
data class QuestionBank(
    val questions: List<MathQuestionEntity>
)
