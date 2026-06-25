package ru.sapozhnikov.scheduler

import kotlinx.serialization.Serializable

@Serializable
enum class TaskType {
    REMINDER,
    COLLECT,
    SUMMARY,
}

@Serializable
enum class TaskStatus {
    ACTIVE,
    COMPLETED,
    CANCELLED,
}

@Serializable
enum class CollectKind {
    NOTE,
    GITHUB_REPO,
    GITHUB_ISSUES,
}

@Serializable
data class ScheduledTask(
    val id: String,
    val name: String,
    val type: TaskType,
    val intervalMs: Long?,
    val nextRunAtEpochMs: Long,
    val createdAtEpochMs: Long,
    val lastRunAtEpochMs: Long? = null,
    val runCount: Int = 0,
    val status: TaskStatus = TaskStatus.ACTIVE,
    val message: String? = null,
    val collectKind: CollectKind? = null,
    val collectOwner: String? = null,
    val collectRepo: String? = null,
    val collectNote: String? = null,
    val sourceTaskId: String? = null,
)

@Serializable
data class TaskRunRecord(
    val id: String,
    val taskId: String,
    val executedAtEpochMs: Long,
    val success: Boolean,
    val summary: String,
    val metrics: Map<String, Double> = emptyMap(),
)

data class AggregatedSummary(
    val generatedAtEpochMs: Long,
    val taskCount: Int,
    val activeTaskCount: Int,
    val totalRuns: Int,
    val tasks: List<TaskSummary>,
    val text: String,
)

data class TaskSummary(
    val taskId: String,
    val name: String,
    val type: TaskType,
    val status: TaskStatus,
    val runCount: Int,
    val lastRunAtEpochMs: Long?,
    val nextRunAtEpochMs: Long?,
    val metrics: Map<String, MetricAggregation>,
    val latestSummary: String?,
)

data class MetricAggregation(
    val min: Double,
    val max: Double,
    val avg: Double,
    val latest: Double,
    val delta: Double?,
    val samples: Int,
)
