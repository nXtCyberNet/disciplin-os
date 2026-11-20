package com.personal.disciplinelock

import com.personal.disciplinelock.data.math.AnswerType
import com.personal.disciplinelock.data.math.DifficultyLevel
import com.personal.disciplinelock.data.math.MathQuestionEntity
import com.personal.disciplinelock.data.math.QuestionBank
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MathEngineLogicTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun testParseMathQuestionsJsonAsset() {
        val assetFile = File("src/main/assets/math_questions.json")
        val jsonString = if (assetFile.exists()) {
            assetFile.readText()
        } else {
            // Also check from root
            File("app/src/main/assets/math_questions.json").readText()
        }

        val bank = json.decodeFromString<QuestionBank>(jsonString)
        assertNotNull(bank)
        assertTrue("Bank should have at least 50 questions", bank.questions.size >= 50)

        // Verify each question has valid data
        val ids = mutableSetOf<Int>()
        for (q in bank.questions) {
            assertTrue("ID must be positive", q.id > 0)
            assertTrue("ID must be unique", ids.add(q.id))
            assertTrue("Question text must not be empty", q.question.isNotBlank())
            assertTrue("Correct answer must not be empty", q.correctAnswer.isNotBlank())

            if (q.answerType == AnswerType.MCQ) {
                assertTrue("MCQ must have options", q.options.isNotEmpty())
                assertTrue(
                    "MCQ options must contain correct answer: ${q.correctAnswer} in ${q.options}",
                    q.options.contains(q.correctAnswer)
                )
            }
        }
    }

    @Test
    fun testDirectInputVerification() {
        val q = MathQuestionEntity(
            id = 1,
            question = "What is 15 + 27?",
            answerType = AnswerType.DIRECT_INPUT,
            correctAnswer = "42",
            options = emptyList(),
            difficulty = DifficultyLevel.EASY,
            category = "arithmetic"
        )

        // Exact match
        assertTrue(verifyAnswer(q, "42"))
        // Whitespace trimmed
        assertTrue(verifyAnswer(q, " 42 "))
        // Numeric tolerance
        assertTrue(verifyAnswer(q, "42.0"))
        // Wrong answer
        assertFalse(verifyAnswer(q, "43"))
        assertFalse(verifyAnswer(q, "abc"))
        assertFalse(verifyAnswer(q, ""))
    }

    @Test
    fun testMcqVerification() {
        val q = MathQuestionEntity(
            id = 2,
            question = "What is the square root of 64?",
            answerType = AnswerType.MCQ,
            correctAnswer = "8",
            options = listOf("6", "7", "8", "9"),
            difficulty = DifficultyLevel.EASY,
            category = "arithmetic"
        )

        assertTrue(verifyAnswer(q, "8"))
        assertTrue(verifyAnswer(q, " 8 "))
        assertFalse(verifyAnswer(q, "7"))
        assertFalse(verifyAnswer(q, "6"))
    }

    @Test
    fun testPoolExclusionLogic() {
        val allQuestions = listOf(
            MathQuestionEntity(1, "Q1", AnswerType.DIRECT_INPUT, "1"),
            MathQuestionEntity(2, "Q2", AnswerType.DIRECT_INPUT, "2"),
            MathQuestionEntity(3, "Q3", AnswerType.DIRECT_INPUT, "3"),
            MathQuestionEntity(4, "Q4", AnswerType.DIRECT_INPUT, "4"),
            MathQuestionEntity(5, "Q5", AnswerType.DIRECT_INPUT, "5")
        )

        val removedIds = mutableSetOf(1, 3)
        val available = allQuestions.filter { it.id !in removedIds }

        assertEquals(3, available.size)
        assertEquals(listOf(2, 4, 5), available.map { it.id })

        // When questions 2 and 4 are answered correctly, remove them
        removedIds.addAll(listOf(2, 4))
        val remaining = allQuestions.filter { it.id !in removedIds }
        assertEquals(1, remaining.size)
        assertEquals(5, remaining[0].id)

        // When pool is exhausted, reset restores all
        removedIds.clear()
        val restored = allQuestions.filter { it.id !in removedIds }
        assertEquals(5, restored.size)
    }

    @Test
    fun testFiveMinuteBlockCalculation() {
        val blockDurationMs = 5 * 60 * 1000L // 300,000 ms
        val blockStartTime = System.currentTimeMillis()
        val blockEndTime = blockStartTime + blockDurationMs

        val remainingSec = (blockEndTime - blockStartTime) / 1000L
        assertEquals(300L, remainingSec)

        val minutes = remainingSec / 60
        val seconds = remainingSec % 60
        assertEquals(5L, minutes)
        assertEquals(0L, seconds)
    }

    private fun verifyAnswer(question: MathQuestionEntity, answer: String): Boolean {
        val cleaned = answer.trim().lowercase()
        val expected = question.correctAnswer.trim().lowercase()

        return when (question.answerType) {
            AnswerType.MCQ -> cleaned == expected
            AnswerType.DIRECT_INPUT -> {
                if (cleaned == expected) return true
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
}
