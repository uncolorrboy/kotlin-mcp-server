package ru.sapozhnikov.scheduler

interface TaskStore {
    suspend fun getTask(id: String): ScheduledTask?
    suspend fun getActiveTasksDue(nowEpochMs: Long): List<ScheduledTask>
    suspend fun upsertTask(task: ScheduledTask)
    suspend fun addRun(run: TaskRunRecord)
    suspend fun getRunsForTask(taskId: String): List<TaskRunRecord>
    suspend fun listTasks(): List<ScheduledTask>
    fun close()
}
