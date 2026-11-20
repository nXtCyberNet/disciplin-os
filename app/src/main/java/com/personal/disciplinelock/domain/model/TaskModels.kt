package com.personal.disciplinelock.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class TaskItem(
    val id: String,
    val content: String,
    val isCompleted: Boolean = false,
    val dueDate: String? = null,
    val priority: Int = 1
)

data class TaskCheckResult(
    val allCompleted: Boolean,
    val pendingCount: Int,
    val pendingTasks: List<TaskItem> = emptyList(),
    val errorMessage: String? = null,
    val isOffline: Boolean = false,
    val lastCheckedTimestamp: Long = System.currentTimeMillis()
)
