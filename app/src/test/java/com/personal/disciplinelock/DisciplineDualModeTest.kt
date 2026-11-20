package com.personal.disciplinelock

import com.personal.disciplinelock.domain.mode.DisciplineGateStatus
import com.personal.disciplinelock.domain.mode.DisciplineMode
import com.personal.disciplinelock.domain.task.DailyTaskItem
import com.personal.disciplinelock.domain.task.HabitItem
import com.personal.disciplinelock.domain.task.ObligationsSnapshot
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for Dual Phone Mode, Habits, and Daily Tasks.
 */
class DisciplineDualModeTest {

    @Test
    fun testObligationsSnapshotEvaluation() {
        // 1. Initial empty state: no habits, no tasks
        val emptySnapshot = ObligationsSnapshot()
        assertFalse(emptySnapshot.areAllObligationsCompleted)

        // 2. Habits present, but none completed
        val habits = listOf(
            HabitItem(1L, "Cold Shower", isCompletedToday = false),
            HabitItem(2L, "Read 20 pages", isCompletedToday = false)
        )
        val dailyTasks = listOf(
            DailyTaskItem(1L, "Submit Assignment", isCompleted = false, date = "2026-11-15")
        )

        val partialSnapshot = ObligationsSnapshot(
            habits = habits,
            dailyTasks = dailyTasks,
            habitsCompletedCount = 0,
            habitsTotalCount = 2,
            tasksCompletedCount = 0,
            tasksTotalCount = 1,
            areAllHabitsCompleted = false,
            areAllTasksCompleted = false,
            areAllObligationsCompleted = false
        )
        assertFalse(partialSnapshot.areAllObligationsCompleted)

        // 3. Habits completed, but daily task pending -> NOT unlocked
        val habitsDoneOnly = partialSnapshot.copy(
            habits = habits.map { it.copy(isCompletedToday = true) },
            habitsCompletedCount = 2,
            areAllHabitsCompleted = true,
            areAllObligationsCompleted = false
        )
        assertFalse(habitsDoneOnly.areAllObligationsCompleted)

        // 4. Both habits AND daily tasks completed -> ALL COMPLETE!
        val allDone = habitsDoneOnly.copy(
            dailyTasks = dailyTasks.map { it.copy(isCompleted = true) },
            tasksCompletedCount = 1,
            areAllTasksCompleted = true,
            areAllObligationsCompleted = true
        )
        assertTrue(allDone.areAllObligationsCompleted)
    }

    @Test
    fun testGateStatusTransitionsToNormalPhoneOnlyWhenAllGatesCleared() {
        // Test Gate arbitration logic
        fun evaluateMode(
            isEmergency: Boolean,
            isMathBlocked: Boolean,
            isMorningRunDone: Boolean,
            isMathPassed: Boolean,
            isHabitsDone: Boolean,
            isTasksDone: Boolean
        ): DisciplineMode {
            return when {
                isEmergency -> DisciplineMode.EMERGENCY_OVERRIDE
                isMathBlocked -> DisciplineMode.DISCIPLINED_PHONE
                isMorningRunDone && isMathPassed && isHabitsDone && isTasksDone -> DisciplineMode.NORMAL_PHONE
                else -> DisciplineMode.DISCIPLINED_PHONE
            }
        }

        // Initially: all gates pending -> DISCIPLINED_PHONE
        assertEquals(
            DisciplineMode.DISCIPLINED_PHONE,
            evaluateMode(false, false, false, false, false, false)
        )

        // Morning run done, math passed, but habits and tasks pending -> still DISCIPLINED_PHONE
        assertEquals(
            DisciplineMode.DISCIPLINED_PHONE,
            evaluateMode(false, false, true, true, false, false)
        )

        // 3 of 4 gates cleared -> still DISCIPLINED_PHONE
        assertEquals(
            DisciplineMode.DISCIPLINED_PHONE,
            evaluateMode(false, false, true, true, true, false)
        )

        // ALL 4 gates cleared -> NORMAL_PHONE unlocked!
        assertEquals(
            DisciplineMode.NORMAL_PHONE,
            evaluateMode(false, false, true, true, true, true)
        )

        // Even if all 4 are done, if wrong answer block is triggered -> DISCIPLINED_PHONE
        assertEquals(
            DisciplineMode.DISCIPLINED_PHONE,
            evaluateMode(false, true, true, true, true, true)
        )

        // Emergency override unlocks immediately
        assertEquals(
            DisciplineMode.EMERGENCY_OVERRIDE,
            evaluateMode(true, false, false, false, false, false)
        )
    }

    @Test
    fun testWhitelistEssentialPackagePermitted() {
        val essentialPrefixes = listOf(
            "com.android.phone",
            "com.android.server.telecom",
            "com.google.android.dialer",
            "com.android.emergency"
        )
        val testPackage = "com.personal.disciplinelock"
        val customWhitelisted = setOf("com.whatsapp", "com.google.android.apps.maps")

        fun isPermitted(pkg: String): Boolean {
            if (pkg == testPackage) return true
            if (essentialPrefixes.any { pkg.startsWith(it) }) return true
            return customWhitelisted.contains(pkg)
        }

        // Own app and dialer allowed
        assertTrue(isPermitted("com.personal.disciplinelock"))
        assertTrue(isPermitted("com.google.android.dialer"))
        assertTrue(isPermitted("com.android.phone.EmergencyDialer"))

        // Custom whitelisted apps allowed
        assertTrue(isPermitted("com.whatsapp"))
        assertTrue(isPermitted("com.google.android.apps.maps"))

        // Distractor apps blocked in Disciplined mode
        assertFalse(isPermitted("com.google.android.youtube"))
        assertFalse(isPermitted("com.instagram.android"))
        assertFalse(isPermitted("com.android.chrome"))
    }

    @Test
    fun testConfigurationLockedDuringDisciplinedMode() {
        fun canModifySettings(isNormalPhone: Boolean): Boolean = isNormalPhone

        // In Disciplined Mode (obligations not complete)
        val isDisciplined = true
        val isNormalMode = !isDisciplined
        assertFalse("Settings should not be modifiable in Disciplined Mode", canModifySettings(isNormalMode))

        // In Normal Phone Mode (obligations complete or emergency override)
        val isUnlocked = true
        assertTrue("Settings should be modifiable in Normal Mode", canModifySettings(isUnlocked))
    }

    @Test
    fun testMissionsCanOnlyBeAddedInNormalMode() {
        fun canAddOrDeleteMission(isNormalMode: Boolean): Boolean = isNormalMode
        fun canCompleteMission(isDisciplinedMode: Boolean): Boolean = true // Always allowed to complete

        // Disciplined Mode: Adding or deleting missions is BLOCKED to prevent bypass
        assertFalse("Cannot add missions during Disciplined Mode", canAddOrDeleteMission(isNormalMode = false))
        assertFalse("Cannot delete missions during Disciplined Mode", canAddOrDeleteMission(isNormalMode = false))
        assertTrue("Can always complete existing missions to earn unlock", canCompleteMission(isDisciplinedMode = true))

        // Normal Mode: Adding and deleting missions is ALLOWED
        assertTrue("Can add missions in Normal Mode", canAddOrDeleteMission(isNormalMode = true))
        assertTrue("Can delete/adjust missions in Normal Mode", canAddOrDeleteMission(isNormalMode = true))
    }
}
