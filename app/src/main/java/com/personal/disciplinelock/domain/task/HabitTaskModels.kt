package com.personal.disciplinelock.domain.task

/**
 * Verification methods supported for missions.
 */
enum class MissionVerificationType(val displayName: String) {
    WEBHOOK("PC Backend Webhook (Inbound)"),
    MANUAL("Manual Checkbox")
}

/**
 * Represents a recurring daily habit mission.
 */
data class HabitItem(
    val id: Long,
    val title: String,
    val category: String = "General",
    val isCompletedToday: Boolean = false,
    val streak: Int = 0,
    val verificationType: MissionVerificationType = MissionVerificationType.WEBHOOK,
    val verificationEndpoint: String? = null,
    val verificationExpectedResponse: String? = null,
    val verificationStatus: String? = null
)

/**
 * Represents a daily mission (defaulted to everyday).
 */
data class DailyTaskItem(
    val id: Long,
    val title: String,
    val isCompleted: Boolean = false,
    val date: String,
    val verificationType: MissionVerificationType = MissionVerificationType.WEBHOOK,
    val verificationEndpoint: String? = null,
    val verificationExpectedResponse: String? = null,
    val verificationStatus: String? = null,
    val lastVerifiedAt: Long? = null,
    val isRecurringEveryday: Boolean = true
)

/**
 * Snapshot of all daily obligations (habits + tasks).
 */
data class ObligationsSnapshot(
    val habits: List<HabitItem> = emptyList(),
    val dailyTasks: List<DailyTaskItem> = emptyList(),
    val habitsCompletedCount: Int = 0,
    val habitsTotalCount: Int = 0,
    val tasksCompletedCount: Int = 0,
    val tasksTotalCount: Int = 0,
    val areAllHabitsCompleted: Boolean = false,
    val areAllTasksCompleted: Boolean = false,
    val areAllObligationsCompleted: Boolean = false,
    val overallStreak: Int = 0,
    val longestStreak: Int = 0
)

/**
 * Represents a casual everyday to-do task (independent of lock obligations/missions).
 */
data class TodoTaskItem(
    val id: Long,
    val title: String,
    val isCompleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
