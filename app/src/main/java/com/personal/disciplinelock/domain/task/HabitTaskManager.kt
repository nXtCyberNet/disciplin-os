package com.personal.disciplinelock.domain.task

import com.personal.disciplinelock.data.task.HabitTaskDatabaseHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manager that coordinates Daily Habits and Today's Tasks.
 *
 * Exposes a reactive state flow of the daily obligations snapshot
 * used by the Discipline Gate to determine whether the phone can unlock.
 */
class HabitTaskManager(private val dbHelper: HabitTaskDatabaseHelper) {

    private val _snapshot = MutableStateFlow(ObligationsSnapshot())
    val snapshot: StateFlow<ObligationsSnapshot> = _snapshot.asStateFlow()

    private val _todoTasks = MutableStateFlow<List<TodoTaskItem>>(emptyList())
    val todoTasks: StateFlow<List<TodoTaskItem>> = _todoTasks.asStateFlow()

    init {
        refresh()
    }

    fun getTodayString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    /**
     * Re-queries SQLite and updates the obligations snapshot.
     */
    fun refresh() {
        val todayStr = getTodayString()

        // 1. Fetch habits and check completion for today
        val rawHabits = dbHelper.getAllActiveHabits()
        val habitItems = rawHabits.map { habit ->
            val isDone = dbHelper.isHabitCompletedOnDate(habit.id, todayStr)
            val streak = dbHelper.calculateStreak(habit.id, todayStr)
            val vType = try {
                MissionVerificationType.valueOf(habit.verificationType)
            } catch (_: Exception) {
                MissionVerificationType.WEBHOOK
            }
            HabitItem(
                id = habit.id,
                title = habit.title,
                category = habit.category,
                isCompletedToday = isDone,
                streak = streak,
                verificationType = vType,
                verificationEndpoint = habit.verificationEndpoint,
                verificationExpectedResponse = habit.verificationExpected,
                verificationStatus = habit.verificationStatus
            )
        }

        // 2. Fetch today's tasks (automatically cloned/instantiated for today if recurring)
        val taskItems = dbHelper.getDailyTasksForDate(todayStr)

        val habitsDone = habitItems.count { it.isCompletedToday }
        val tasksDone = taskItems.count { it.isCompleted }

        // Both habits and tasks must be complete.
        // If taskItems is empty, tasks do not block completion.
        val habitsComplete = habitItems.isEmpty() || (habitsDone == habitItems.size)
        val tasksComplete = taskItems.isEmpty() || (tasksDone == taskItems.size)
        val hasAnyObligations = habitItems.isNotEmpty() || taskItems.isNotEmpty()
        val allComplete = hasAnyObligations && habitsComplete && tasksComplete

        val overallStreak = dbHelper.calculateOverallStreak(todayStr)
        val longestStreak = dbHelper.getLongestStreak()

        _snapshot.value = ObligationsSnapshot(
            habits = habitItems,
            dailyTasks = taskItems,
            habitsCompletedCount = habitsDone,
            habitsTotalCount = habitItems.size,
            tasksCompletedCount = tasksDone,
            tasksTotalCount = taskItems.size,
            areAllHabitsCompleted = habitsComplete,
            areAllTasksCompleted = tasksComplete,
            areAllObligationsCompleted = allComplete,
            overallStreak = overallStreak,
            longestStreak = longestStreak
        )

        _todoTasks.value = dbHelper.getAllTodoTasks()
    }

    /**
     * Retrieve daily tasks for any specific date.
     */
    fun getDailyTasksForDate(dateStr: String): List<DailyTaskItem> {
        return dbHelper.getDailyTasksForDate(dateStr)
    }

    /**
     * Records that all obligations for the given date were fully cleared.
     */
    fun recordDayCompleted(todayStr: String = getTodayString(), totalMissions: Int = 0): Boolean {
        val res = dbHelper.recordDayCompletion(todayStr, totalMissions)
        refresh()
        return res
    }

    /**
     * Retrieve habits and their completion statuses for any specific date.
     */
    fun getHabitsForDate(dateStr: String): List<HabitItem> {
        val rawHabits = dbHelper.getAllActiveHabits()
        return rawHabits.map { habit ->
            val isDone = dbHelper.isHabitCompletedOnDate(habit.id, dateStr)
            val streak = dbHelper.calculateStreak(habit.id, dateStr)
            val vType = try {
                val parsed = MissionVerificationType.valueOf(habit.verificationType)
                if (parsed == MissionVerificationType.MANUAL) MissionVerificationType.WEBHOOK else parsed
            } catch (_: Exception) {
                MissionVerificationType.WEBHOOK
            }
            HabitItem(
                id = habit.id,
                title = habit.title,
                category = habit.category,
                isCompletedToday = isDone,
                streak = streak,
                verificationType = vType,
                verificationEndpoint = habit.verificationEndpoint,
                verificationExpectedResponse = habit.verificationExpected,
                verificationStatus = habit.verificationStatus
            )
        }
    }

    // ==================== Casual To-Do Tasks ====================

    fun addTodoTask(title: String): Long {
        val id = dbHelper.insertTodoTask(title)
        refresh()
        return id
    }

    fun toggleTodoTask(id: Long) {
        dbHelper.toggleTodoTask(id)
        refresh()
    }

    fun deleteTodoTask(id: Long): Boolean {
        val deleted = dbHelper.deleteTodoTask(id)
        refresh()
        return deleted
    }

    fun clearCompletedTodoTasks(): Int {
        val count = dbHelper.clearCompletedTodoTasks()
        refresh()
        return count
    }

    // ==================== Habit Actions ====================

    fun toggleHabit(habitId: Long, allowManual: Boolean = false): Boolean {
        val todayStr = getTodayString()
        val currentDone = dbHelper.isHabitCompletedOnDate(habitId, todayStr)
        if (!currentDone && !allowManual) {
            // Webhook verification is required to complete this habit
            return false
        }
        dbHelper.setHabitCompletion(habitId, todayStr, !currentDone)
        refresh()
        return true
    }

    fun addHabit(title: String, category: String = "General"): Long {
        val id = dbHelper.insertHabit(title, category)
        refresh()
        return id
    }

    fun deleteHabit(habitId: Long): Boolean {
        val success = dbHelper.deleteHabit(habitId)
        refresh()
        return success
    }

    fun updateHabitVerification(
        habitId: Long,
        type: MissionVerificationType,
        endpoint: String?,
        expectedResponse: String?
    ) {
        dbHelper.updateHabitVerification(habitId, type.name, endpoint, expectedResponse)
        refresh()
    }

    // ==================== Daily Task Actions ====================

    fun toggleDailyTask(taskId: Long, allowManual: Boolean = false): Boolean {
        val todayStr = getTodayString()
        val tasks = dbHelper.getDailyTasksForDate(todayStr)
        val task = tasks.find { it.id == taskId } ?: return false
        if (!task.isCompleted && !allowManual) {
            // Webhook verification is required to complete this daily task
            return false
        }
        dbHelper.setDailyTaskCompletion(taskId, !task.isCompleted)
        refresh()
        return true
    }

    fun setDailyTaskCompletion(taskId: Long, completed: Boolean) {
        dbHelper.setDailyTaskCompletion(taskId, completed)
        refresh()
    }

    fun addDailyTask(
        title: String,
        verificationType: MissionVerificationType = MissionVerificationType.WEBHOOK
    ): Long {
        val todayStr = getTodayString()
        val id = dbHelper.insertDailyTask(
            title = title,
            dateStr = todayStr,
            verificationType = verificationType.name
        )
        refresh()
        return id
    }

    /**
     * Verifies a mission by target (numeric ID or title/slug) received from an inbound webhook.
     * Marks the mission verified and completed in SQLite, and triggers an immediate refresh.
     * Returns Pair(Boolean, String?) indicating whether a match was found and its title.
     */
    fun verifyMissionByWebhook(target: String, source: String = "PC Backend Webhook"): Pair<Boolean, String?> {
        val todayStr = getTodayString()
        val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val targetClean = target.trim()
        val targetId = targetClean.toLongOrNull()

        // 1. Check Daily Tasks by numeric ID
        val dailyTasks = dbHelper.getDailyTasksForDate(todayStr)
        if (targetId != null) {
            val task = dailyTasks.find { it.id == targetId }
            if (task != null) {
                dbHelper.setDailyTaskVerificationStatus(task.id, "Verified via $source at $timeStr")
                dbHelper.setDailyTaskCompletion(task.id, true)
                refresh()
                return Pair(true, task.title)
            }
        }

        // 2. Check Habits by numeric ID
        val habits = dbHelper.getAllActiveHabits()
        if (targetId != null) {
            val habit = habits.find { it.id == targetId }
            if (habit != null) {
                dbHelper.setHabitVerificationStatus(habit.id, "Verified via $source at $timeStr")
                dbHelper.setHabitCompletion(habit.id, todayStr, true)
                refresh()
                return Pair(true, habit.title)
            }
        }

        // 3. Match Daily Tasks by Title / Slug / Key
        val normalizedTarget = targetClean.lowercase().replace("-", " ").replace("_", " ").trim()
        val matchedTask = dailyTasks.find { task ->
            val norm = task.title.lowercase().replace("-", " ").replace("_", " ").trim()
            norm == normalizedTarget || norm.contains(normalizedTarget) || normalizedTarget.contains(norm)
        }
        if (matchedTask != null) {
            dbHelper.setDailyTaskVerificationStatus(matchedTask.id, "Verified via $source at $timeStr")
            dbHelper.setDailyTaskCompletion(matchedTask.id, true)
            refresh()
            return Pair(true, matchedTask.title)
        }

        // 4. Match Habits by Title / Slug / Key
        val matchedHabit = habits.find { habit ->
            val norm = habit.title.lowercase().replace("-", " ").replace("_", " ").trim()
            norm == normalizedTarget || norm.contains(normalizedTarget) || normalizedTarget.contains(norm)
        }
        if (matchedHabit != null) {
            dbHelper.setHabitVerificationStatus(matchedHabit.id, "Verified via $source at $timeStr")
            dbHelper.setHabitCompletion(matchedHabit.id, todayStr, true)
            refresh()
            return Pair(true, matchedHabit.title)
        }

        return Pair(false, null)
    }

    /**
     * Automatically verifies walking or step missions when a target step count is reached.
     */
    fun verifyStepsGoal(steps: Int, source: String = "DisciplineHealth"): Pair<Boolean, String?> {
        val todayStr = getTodayString()
        val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())

        // 1. Check daily tasks for step goals (e.g. "Walk 5000 Steps", "5k Steps", "Walk", "Steps")
        val dailyTasks = dbHelper.getDailyTasksForDate(todayStr).filter { !it.isCompleted }
        for (task in dailyTasks) {
            val titleLower = task.title.lowercase()
            if (titleLower.contains("step") || titleLower.contains("walk") || titleLower.contains("stride")) {
                val targetNumber = extractTargetSteps(titleLower) ?: 5000
                if (steps >= targetNumber) {
                    dbHelper.setDailyTaskVerificationStatus(task.id, "Verified: $steps steps ($source) at $timeStr")
                    dbHelper.setDailyTaskCompletion(task.id, true)
                    refresh()
                    return Pair(true, task.title)
                }
            }
        }

        // 2. Check habits for step goals
        val habits = dbHelper.getAllActiveHabits().filter { !dbHelper.isHabitCompletedOnDate(it.id, todayStr) }
        for (habit in habits) {
            val titleLower = habit.title.lowercase()
            if (titleLower.contains("step") || titleLower.contains("walk") || titleLower.contains("stride")) {
                val targetNumber = extractTargetSteps(titleLower) ?: 5000
                if (steps >= targetNumber) {
                    dbHelper.setHabitVerificationStatus(habit.id, "Verified: $steps steps ($source) at $timeStr")
                    dbHelper.setHabitCompletion(habit.id, todayStr, true)
                    refresh()
                    return Pair(true, habit.title)
                }
            }
        }

        return Pair(false, null)
    }

    private fun extractTargetSteps(text: String): Int? {
        val regexK = """(\d+)\s*k""".toRegex()
        val matchK = regexK.find(text)
        if (matchK != null) {
            val num = matchK.groupValues[1].toIntOrNull()
            if (num != null) return num * 1000
        }
        val regexNum = """(\d{3,6})""".toRegex()
        val matchNum = regexNum.find(text)
        if (matchNum != null) {
            return matchNum.groupValues[1].toIntOrNull()
        }
        return null
    }

    fun deleteDailyTask(taskId: Long): Boolean {
        val success = dbHelper.deleteDailyTask(taskId)
        refresh()
        return success
    }

    fun updateDailyTaskVerification(
        taskId: Long,
        type: MissionVerificationType,
        endpoint: String?,
        expectedResponse: String?
    ) {
        dbHelper.updateDailyTaskVerification(taskId, type.name, endpoint, expectedResponse)
        refresh()
    }

    fun setDailyTaskVerificationStatus(
        taskId: Long,
        status: String?,
        verifiedAt: Long? = System.currentTimeMillis()
    ) {
        dbHelper.setDailyTaskVerificationStatus(taskId, status, verifiedAt)
        refresh()
    }

    suspend fun verifyDailyTaskViaApi(
        taskId: Long,
        verificationEngine: com.personal.disciplinelock.domain.webhook.MissionVerificationEngine
    ): com.personal.disciplinelock.domain.webhook.VerificationResult {
        val todayStr = getTodayString()
        val task = dbHelper.getDailyTasksForDate(todayStr).find { it.id == taskId }
            ?: return com.personal.disciplinelock.domain.webhook.VerificationResult.Failure("Mission not found.")

        val endpoint = task.verificationEndpoint.orEmpty()
        val expected = task.verificationExpectedResponse
        val result = verificationEngine.verifyMission(taskId, task.verificationType, endpoint, expected)

        when (result) {
            is com.personal.disciplinelock.domain.webhook.VerificationResult.Success -> {
                dbHelper.setDailyTaskVerificationStatus(taskId, "Verified: ${result.message}", result.timestamp)
                dbHelper.setDailyTaskCompletion(taskId, true)
                refresh()
            }
            is com.personal.disciplinelock.domain.webhook.VerificationResult.Failure -> {
                dbHelper.setDailyTaskVerificationStatus(taskId, "Failed: ${result.errorMessage}", null)
                refresh()
            }
        }
        return result
    }

    suspend fun verifyHabitViaApi(
        habitId: Long,
        verificationEngine: com.personal.disciplinelock.domain.webhook.MissionVerificationEngine
    ): com.personal.disciplinelock.domain.webhook.VerificationResult {
        val todayStr = getTodayString()
        val habit = dbHelper.getAllActiveHabits().find { it.id == habitId }
            ?: return com.personal.disciplinelock.domain.webhook.VerificationResult.Failure("Habit not found.")

        val endpoint = habit.verificationEndpoint.orEmpty()
        val expected = habit.verificationExpected
        val vType = runCatching {
            com.personal.disciplinelock.domain.task.MissionVerificationType.valueOf(habit.verificationType)
        }.getOrDefault(com.personal.disciplinelock.domain.task.MissionVerificationType.MANUAL)
        val result = verificationEngine.verifyMission(habitId, vType, endpoint, expected)

        when (result) {
            is com.personal.disciplinelock.domain.webhook.VerificationResult.Success -> {
                dbHelper.setHabitVerificationStatus(habitId, "Verified: ${result.message}")
                dbHelper.setHabitCompletion(habitId, todayStr, true)
                refresh()
            }
            is com.personal.disciplinelock.domain.webhook.VerificationResult.Failure -> {
                dbHelper.setHabitVerificationStatus(habitId, "Failed: ${result.errorMessage}")
                refresh()
            }
        }
        return result
    }

    fun markAllTasksAndHabitsCompleted() {
        val todayStr = getTodayString()
        val rawHabits = dbHelper.getAllActiveHabits()
        rawHabits.forEach { habit ->
            dbHelper.setHabitCompletion(habit.id, todayStr, true)
        }
        val rawTasks = dbHelper.getDailyTasksForDate(todayStr)
        rawTasks.forEach { task ->
            dbHelper.setDailyTaskCompletion(task.id, true)
        }
        refresh()
    }

    fun areAllObligationsCompleted(): Boolean {
        return _snapshot.value.areAllObligationsCompleted
    }
}
