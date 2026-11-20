package com.personal.disciplinelock

import com.personal.disciplinelock.domain.challenge.MathChallengeGenerator
import com.personal.disciplinelock.domain.model.ChallengeCategory
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class MathChallengeGeneratorTest {

    @Test
    fun testCombinationsFormula() {
        assertEquals(1L, MathChallengeGenerator.combinations(5, 0))
        assertEquals(5L, MathChallengeGenerator.combinations(5, 1))
        assertEquals(10L, MathChallengeGenerator.combinations(5, 2))
        assertEquals(10L, MathChallengeGenerator.combinations(5, 3))
        assertEquals(5L, MathChallengeGenerator.combinations(5, 4))
        assertEquals(1L, MathChallengeGenerator.combinations(5, 5))
        assertEquals(35L, MathChallengeGenerator.combinations(7, 3))
        assertEquals(210L, MathChallengeGenerator.combinations(10, 4))
    }

    @Test
    fun testPermutationsFormula() {
        assertEquals(1L, MathChallengeGenerator.permutations(5, 0))
        assertEquals(5L, MathChallengeGenerator.permutations(5, 1))
        assertEquals(20L, MathChallengeGenerator.permutations(5, 2))
        assertEquals(60L, MathChallengeGenerator.permutations(5, 3))
        assertEquals(120L, MathChallengeGenerator.permutations(5, 4))
        assertEquals(120L, MathChallengeGenerator.permutations(5, 5))
    }

    @Test
    fun testFactorialFormula() {
        assertEquals(1L, MathChallengeGenerator.factorial(0))
        assertEquals(1L, MathChallengeGenerator.factorial(1))
        assertEquals(2L, MathChallengeGenerator.factorial(2))
        assertEquals(6L, MathChallengeGenerator.factorial(3))
        assertEquals(24L, MathChallengeGenerator.factorial(4))
        assertEquals(120L, MathChallengeGenerator.factorial(5))
        assertEquals(720L, MathChallengeGenerator.factorial(6))
    }

    @Test
    fun testArithmeticGenerationProducesSolvableQuestions() {
        val seededRandom = Random(42)
        for (i in 0 until 50) {
            val question = MathChallengeGenerator.generate(ChallengeCategory.MULTI_STEP_ARITHMETIC, seededRandom)
            assertNotNull(question.prompt)
            assertTrue(question.prompt.isNotEmpty())
            // Expected answer must verify successfully
            assertTrue(MathChallengeGenerator.verifyAnswer(question, question.expectedAnswer.toString()))
            // Wrong answer must fail
            assertFalse(MathChallengeGenerator.verifyAnswer(question, (question.expectedAnswer + 1).toString()))
        }
    }

    @Test
    fun testCombinatoricsGeneration() {
        val seededRandom = Random(123)
        for (i in 0 until 50) {
            val question = MathChallengeGenerator.generate(ChallengeCategory.COMBINATORICS, seededRandom)
            assertTrue(question.expectedAnswer > 0)
            assertTrue(MathChallengeGenerator.verifyAnswer(question, " ${question.expectedAnswer} "))
        }
    }

    @Test
    fun testProbabilityGeneration() {
        val seededRandom = Random(999)
        for (i in 0 until 50) {
            val question = MathChallengeGenerator.generate(ChallengeCategory.DISCRETE_PROBABILITY, seededRandom)
            assertTrue(question.expectedAnswer >= 0)
            // Should accept integer or percentage formatted string
            assertTrue(MathChallengeGenerator.verifyAnswer(question, "${question.expectedAnswer}%"))
            assertTrue(MathChallengeGenerator.verifyAnswer(question, "${question.expectedAnswer}"))
        }
    }

    @Test
    fun testVerifyAnswerEdgeCases() {
        val question = MathChallengeGenerator.generate(ChallengeCategory.MULTI_STEP_ARITHMETIC)
        assertFalse(MathChallengeGenerator.verifyAnswer(question, ""))
        assertFalse(MathChallengeGenerator.verifyAnswer(question, "abc"))
        assertFalse(MathChallengeGenerator.verifyAnswer(question, "12.34"))
    }
}
