package com.personal.disciplinelock.domain.challenge

import com.personal.disciplinelock.domain.model.ChallengeCategory
import com.personal.disciplinelock.domain.model.ChallengeQuestion
import java.util.UUID
import kotlin.random.Random

object MathChallengeGenerator {

    fun generate(category: ChallengeCategory? = null, random: Random = Random): ChallengeQuestion {
        val selectedCategory = category ?: ChallengeCategory.entries[random.nextInt(ChallengeCategory.entries.size)]
        return when (selectedCategory) {
            ChallengeCategory.MULTI_STEP_ARITHMETIC -> generateArithmetic(random)
            ChallengeCategory.COMBINATORICS -> generateCombinatorics(random)
            ChallengeCategory.DISCRETE_PROBABILITY -> generateProbability(random)
        }
    }

    private fun generateArithmetic(random: Random): ChallengeQuestion {
        val type = random.nextInt(3)
        return when (type) {
            0 -> {
                // (a * b) - (c * d) + e
                val a = random.nextInt(12, 28)
                val b = random.nextInt(11, 24)
                val c = random.nextInt(8, 19)
                val d = random.nextInt(7, 18)
                val e = random.nextInt(15, 65)
                val answer = ((a * b) - (c * d) + e).toLong()
                ChallengeQuestion(
                    id = UUID.randomUUID().toString(),
                    prompt = "Compute:\n($a × $b) - ($c × $d) + $e",
                    expectedAnswer = answer,
                    category = ChallengeCategory.MULTI_STEP_ARITHMETIC,
                    unitOrFormatNote = "Enter the exact integer result",
                    hintOrExplanation = "$a × $b = ${a * b}, $c × $d = ${c * d}. (${a * b}) - (${c * d}) + $e = $answer"
                )
            }
            1 -> {
                // (a + b) * c - d
                val a = random.nextInt(23, 79)
                val b = random.nextInt(18, 64)
                val c = random.nextInt(6, 16)
                val d = random.nextInt(35, 120)
                val answer = (((a + b) * c) - d).toLong()
                ChallengeQuestion(
                    id = UUID.randomUUID().toString(),
                    prompt = "Compute:\n(($a + $b) × $c) - $d",
                    expectedAnswer = answer,
                    category = ChallengeCategory.MULTI_STEP_ARITHMETIC,
                    unitOrFormatNote = "Enter the exact integer result",
                    hintOrExplanation = "$a + $b = ${a + b}; ${a + b} × $c = ${(a + b) * c}; ${(a + b) * c} - $d = $answer"
                )
            }
            else -> {
                // (a^2) - (b * c) + d
                val a = random.nextInt(14, 32)
                val b = random.nextInt(9, 21)
                val c = random.nextInt(8, 19)
                val d = random.nextInt(20, 80)
                val answer = ((a * a) - (b * c) + d).toLong()
                ChallengeQuestion(
                    id = UUID.randomUUID().toString(),
                    prompt = "Compute:\n($a² ) - ($b × $c) + $d",
                    expectedAnswer = answer,
                    category = ChallengeCategory.MULTI_STEP_ARITHMETIC,
                    unitOrFormatNote = "Enter the exact integer result",
                    hintOrExplanation = "$a² = ${a * a}; $b × $c = ${b * c}; ${a * a} - ${b * c} + $d = $answer"
                )
            }
        }
    }

    private fun generateCombinatorics(random: Random): ChallengeQuestion {
        val type = random.nextInt(3)
        return when (type) {
            0 -> {
                // Combinations C(n, k)
                val n = random.nextInt(6, 11)
                val k = random.nextInt(2, minOf(5, n - 1))
                val answer = combinations(n.toLong(), k.toLong())
                ChallengeQuestion(
                    id = UUID.randomUUID().toString(),
                    prompt = "From a candidate pool of $n people, how many distinct committees of $k people can be selected?",
                    expectedAnswer = answer,
                    category = ChallengeCategory.COMBINATORICS,
                    unitOrFormatNote = "Enter the number of combinations C($n, $k)",
                    hintOrExplanation = "C($n, $k) = $n! / ($k! × ($n - $k)!) = $answer"
                )
            }
            1 -> {
                // Permutations P(n, k)
                val n = random.nextInt(5, 9)
                val k = random.nextInt(2, minOf(4, n))
                val answer = permutations(n.toLong(), k.toLong())
                ChallengeQuestion(
                    id = UUID.randomUUID().toString(),
                    prompt = "In a race with $n runners (assuming no ties), in how many ways can the top $k positions (1st, 2nd...) be filled?",
                    expectedAnswer = answer,
                    category = ChallengeCategory.COMBINATORICS,
                    unitOrFormatNote = "Enter the number of permutations P($n, $k)",
                    hintOrExplanation = "P($n, $k) = $n! / ($n - $k)! = $answer"
                )
            }
            else -> {
                // Anagram / multiset permutation: e.g., "LEVEL" (5 letters, 2 Ls, 2 Es) or "STATISTICS"
                val words = listOf(
                    Triple("LEVEL", 5L, listOf(2L, 2L)),     // 5! / (2! 2!) = 30
                    Triple("RADAR", 5L, listOf(2L, 2L)),     // 5! / (2! 2!) = 30
                    Triple("APPLE", 5L, listOf(2L)),         // 5! / 2! = 60
                    Triple("PEPPER", 6L, listOf(3L, 2L)),    // 6! / (3! 2!) = 60
                    Triple("BANANA", 6L, listOf(3L, 2L)),    // 6! / (3! 2!) = 60
                    Triple("COFFEE", 6L, listOf(2L, 2L)),    // 6! / (2! 2!) = 180
                    Triple("CHEESE", 6L, listOf(3L))         // 6! / 3! = 120
                )
                val item = words[random.nextInt(words.size)]
                var ans = factorial(item.second)
                for (repeat in item.third) {
                    ans /= factorial(repeat)
                }
                ChallengeQuestion(
                    id = UUID.randomUUID().toString(),
                    prompt = "How many distinct anagrams (letter permutations) can be formed using all letters of '${item.first}'?",
                    expectedAnswer = ans,
                    category = ChallengeCategory.COMBINATORICS,
                    unitOrFormatNote = "Enter integer number of distinct arrangements",
                    hintOrExplanation = "${item.second}! divided by the factorials of repeated letter frequencies = $ans"
                )
            }
        }
    }

    private fun generateProbability(random: Random): ChallengeQuestion {
        val type = random.nextInt(3)
        return when (type) {
            0 -> {
                // Dice sum count: sum = S
                val targetSum = random.nextInt(7, 12)
                var count = 0
                for (d1 in 1..6) {
                    for (d2 in 1..6) {
                        if (d1 + d2 == targetSum) count++
                    }
                }
                ChallengeQuestion(
                    id = UUID.randomUUID().toString(),
                    prompt = "Two standard 6-sided dice are rolled simultaneously.\nHow many distinct outcomes (out of 36) yield a sum exactly equal to $targetSum?",
                    expectedAnswer = count.toLong(),
                    category = ChallengeCategory.DISCRETE_PROBABILITY,
                    unitOrFormatNote = "Enter the number of favorable outcomes (integer between 1 and 6)",
                    hintOrExplanation = "There are $count pairs (d1, d2) that sum to $targetSum out of 36 possible outcomes."
                )
            }
            1 -> {
                // Coin tosses: Number of outcomes with exactly k heads out of n tosses
                val n = random.nextInt(5, 8)
                val k = random.nextInt(2, n - 1)
                val count = combinations(n.toLong(), k.toLong())
                ChallengeQuestion(
                    id = UUID.randomUUID().toString(),
                    prompt = "If a fair coin is tossed $n times, in how many outcomes do you obtain exactly $k heads?",
                    expectedAnswer = count,
                    category = ChallengeCategory.DISCRETE_PROBABILITY,
                    unitOrFormatNote = "Enter number of favorable outcomes C($n, $k)",
                    hintOrExplanation = "Number of sequences with $k heads out of $n is C($n, $k) = $count"
                )
            }
            else -> {
                // Urn problem: Total balls, drawing 2 of same color without replacement. Answer as integer percentage rounded.
                val red = random.nextInt(3, 7)
                val blue = random.nextInt(3, 7)
                val total = red + blue
                // P(both red) = (red / total) * ((red - 1) / (total - 1))
                val numerator = red * (red - 1)
                val denominator = total * (total - 1)
                val percentage = Math.round((numerator.toDouble() / denominator.toDouble()) * 100.0)

                ChallengeQuestion(
                    id = UUID.randomUUID().toString(),
                    prompt = "An urn contains $red red balls and $blue blue balls (total $total).\n" +
                            "If 2 balls are drawn at random without replacement, what is the probability that BOTH balls are red?\n" +
                            "(Express as an integer percentage, rounded to the nearest percent)",
                    expectedAnswer = percentage,
                    category = ChallengeCategory.DISCRETE_PROBABILITY,
                    unitOrFormatNote = "Enter nearest whole integer percentage (e.g. 25)",
                    hintOrExplanation = "($red/$total) × (${red - 1}/${total - 1}) = $numerator/$denominator ≈ $percentage%"
                )
            }
        }
    }

    fun combinations(n: Long, k: Long): Long {
        if (k < 0 || k > n) return 0L
        if (k == 0L || k == n) return 1L
        val effectiveK = if (k > n - k) n - k else k
        var result = 1L
        for (i in 1L..effectiveK) {
            result = result * (n - i + 1L) / i
        }
        return result
    }

    fun permutations(n: Long, k: Long): Long {
        if (k < 0 || k > n) return 0L
        var result = 1L
        for (i in 0 until k) {
            result *= (n - i)
        }
        return result
    }

    fun factorial(n: Long): Long {
        var res = 1L
        for (i in 2L..n) {
            res *= i
        }
        return res
    }

    fun verifyAnswer(question: ChallengeQuestion, rawInput: String): Boolean {
        val cleaned = rawInput.trim().replace("%", "").trim()
        val parsed = cleaned.toLongOrNull() ?: return false
        return parsed == question.expectedAnswer
    }
}
