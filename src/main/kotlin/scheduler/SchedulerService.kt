package ru.sapozhnikov.scheduler

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import ru.sapozhnikov.github.GitHubClient
import ru.sapozhnikov.github.GitHubResponse
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.sapozhnikov.AppLog

class SchedulerService(
    private val store: TaskStore,
    private val githubClient: GitHubClient,
    private val tickIntervalMs: Long = 1_000L,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob: Job? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    fun start() {
        if (tickJob?.isActive == true) return
        AppLog.info("Scheduler started (tick=${tickIntervalMs}ms)")
        tickJob = scope.launch {
            while (isActive) {
                try {
                    runDueTasks()
                } catch (e: Exception) {
                    AppLog.error("Scheduler tick failed", e)
                }
                delay(tickIntervalMs)
            }
        }
    }

    fun stop() {
        AppLog.info("Scheduler stopped")
        tickJob?.cancel()
        scope.cancel()
    }

    suspend fun createReminder(name: String, message: String, delayMinutes: Long): ScheduledTask {
        require(delayMinutes > 0) { "delay_minutes must be positive" }
        val now = System.currentTimeMillis()
        val task = ScheduledTask(
            id = newId(),
            name = name,
            type = TaskType.REMINDER,
            intervalMs = null,
            nextRunAtEpochMs = now + delayMinutes * 60_000,
            createdAtEpochMs = now,
            message = message,
        )
        store.upsertTask(task)
        AppLog.info("Created REMINDER id=${task.id} name='$name' in ${delayMinutes}min")
        return task
    }

    suspend fun createCollector(
        name: String,
        intervalMinutes: Long,
        kind: CollectKind,
        note: String? = null,
        owner: String? = null,
        repo: String? = null,
    ): ScheduledTask {
        require(intervalMinutes > 0) { "interval_minutes must be positive" }
        validateCollectorParams(kind, note, owner, repo)
        val now = System.currentTimeMillis()
        val task = ScheduledTask(
            id = newId(),
            name = name,
            type = TaskType.COLLECT,
            intervalMs = intervalMinutes * 60_000,
            nextRunAtEpochMs = now + intervalMinutes * 60_000,
            createdAtEpochMs = now,
            collectKind = kind,
            collectNote = note,
            collectOwner = owner,
            collectRepo = repo,
        )
        store.upsertTask(task)
        AppLog.info("Created COLLECT id=${task.id} name='$name' kind=$kind every ${intervalMinutes}min")
        return task
    }

    suspend fun createSummary(name: String, intervalMinutes: Long, sourceTaskId: String): ScheduledTask {
        require(intervalMinutes > 0) { "interval_minutes must be positive" }
        val source = store.getTask(sourceTaskId)
            ?: throw IllegalArgumentException("Source task '$sourceTaskId' not found")
        require(source.type == TaskType.COLLECT) {
            "Source task must be a COLLECT task, got ${source.type}"
        }
        val now = System.currentTimeMillis()
        val task = ScheduledTask(
            id = newId(),
            name = name,
            type = TaskType.SUMMARY,
            intervalMs = intervalMinutes * 60_000,
            nextRunAtEpochMs = now + intervalMinutes * 60_000,
            createdAtEpochMs = now,
            sourceTaskId = sourceTaskId,
        )
        store.upsertTask(task)
        AppLog.info("Created SUMMARY id=${task.id} name='$name' source=$sourceTaskId every ${intervalMinutes}min")
        return task
    }

    suspend fun cancelTask(taskId: String): ScheduledTask {
        val task = store.getTask(taskId) ?: throw IllegalArgumentException("Task '$taskId' not found")
        if (task.status == TaskStatus.CANCELLED) {
            AppLog.info("Task id=$taskId already cancelled")
            return task
        }
        val updated = task.copy(status = TaskStatus.CANCELLED)
        store.upsertTask(updated)
        AppLog.info("Cancelled task id=$taskId name='${task.name}' type=${task.type}")
        return updated
    }

    suspend fun cancelAllTasks(type: TaskType? = null): Int {
        val toCancel = store.listTasks().filter { task ->
            task.status == TaskStatus.ACTIVE && (type == null || task.type == type)
        }
        toCancel.forEach { task ->
            store.upsertTask(task.copy(status = TaskStatus.CANCELLED))
            AppLog.info("Cancelled task id=${task.id} name='${task.name}' type=${task.type}")
        }
        AppLog.info("Cancelled ${toCancel.size} task(s)${type?.let { " of type $it" } ?: ""}")
        return toCancel.size
    }

    suspend fun listTasks(): List<ScheduledTask> = store.listTasks()

    suspend fun getSummary(taskId: String? = null): AggregatedSummary {
        val now = System.currentTimeMillis()
        val tasks = if (taskId != null) {
            listOfNotNull(store.getTask(taskId))
        } else {
            store.listTasks()
        }
        val runs = tasks.flatMap { store.getRunsForTask(it.id) }
        val taskSummaries = tasks.map { task ->
            val taskRuns = store.getRunsForTask(task.id)
            TaskSummary(
                taskId = task.id,
                name = task.name,
                type = task.type,
                status = task.status,
                runCount = task.runCount,
                lastRunAtEpochMs = task.lastRunAtEpochMs,
                nextRunAtEpochMs = if (task.status == TaskStatus.ACTIVE) task.nextRunAtEpochMs else null,
                metrics = aggregateMetrics(taskRuns),
                latestSummary = taskRuns.lastOrNull()?.summary,
            )
        }
        val text = buildSummaryText(now, taskSummaries, runs.size)
        return AggregatedSummary(
            generatedAtEpochMs = now,
            taskCount = tasks.size,
            activeTaskCount = tasks.count { it.status == TaskStatus.ACTIVE },
            totalRuns = runs.size,
            tasks = taskSummaries,
            text = text,
        )
    }

    private suspend fun runDueTasks() {
        val now = System.currentTimeMillis()
        val dueTasks = store.getActiveTasksDue(now)
        for (task in dueTasks) {
            executeTask(task, now)
        }
    }

    private suspend fun executeTask(task: ScheduledTask, now: Long) {
        val current = store.getTask(task.id)
        if (current == null || current.status != TaskStatus.ACTIVE) {
            AppLog.info("Skipping ${task.type} id=${task.id} — status=${current?.status ?: "deleted"}")
            return
        }

        AppLog.info("Executing ${current.type} id=${current.id} name='${current.name}'")
        val result = when (current.type) {
            TaskType.REMINDER -> executeReminder(current, now)
            TaskType.COLLECT -> executeCollect(current, now)
            TaskType.SUMMARY -> executeSummary(current, now)
        }
        store.addRun(result.run)

        if (store.getTask(current.id)?.status != TaskStatus.ACTIVE) {
            AppLog.info("Task id=${current.id} was cancelled during execution, run saved but not rescheduled")
        } else {
            store.upsertTask(result.updatedTask)
        }

        val status = if (result.run.success) "OK" else "FAIL"
        AppLog.info("Finished ${current.type} id=${current.id} [$status] ${result.run.summary.take(120)}")
    }

    private fun executeReminder(task: ScheduledTask, now: Long): ExecutionResult {
        val message = task.message.orEmpty()
        val summary = "🔔 Напоминание «${task.name}»: $message (в ${formatTime(now)})"
        val run = TaskRunRecord(
            id = newId(),
            taskId = task.id,
            executedAtEpochMs = now,
            success = true,
            summary = summary,
        )
        val updated = task.copy(
            status = TaskStatus.COMPLETED,
            lastRunAtEpochMs = now,
            runCount = task.runCount + 1,
        )
        return ExecutionResult(run, updated)
    }

    private suspend fun executeCollect(task: ScheduledTask, now: Long): ExecutionResult {
        val kind = task.collectKind ?: CollectKind.NOTE
        val collected = when (kind) {
            CollectKind.NOTE -> collectNote(task, now)
            CollectKind.GITHUB_REPO -> collectGitHubRepo(task)
            CollectKind.GITHUB_ISSUES -> collectGitHubIssues(task)
        }
        val run = TaskRunRecord(
            id = newId(),
            taskId = task.id,
            executedAtEpochMs = now,
            success = collected.success,
            summary = collected.summary,
            metrics = collected.metrics,
        )
        val updated = task.copy(
            lastRunAtEpochMs = now,
            runCount = task.runCount + 1,
            nextRunAtEpochMs = now + (task.intervalMs ?: 60_000),
        )
        return ExecutionResult(run, updated)
    }

    private suspend fun executeSummary(task: ScheduledTask, now: Long): ExecutionResult {
        val sourceId = task.sourceTaskId
        val sourceRuns = if (sourceId != null) store.getRunsForTask(sourceId) else emptyList()
        val metrics = aggregateMetrics(sourceRuns)
        val summary = buildString {
            append("📊 Сводка «${task.name}» за ${formatTime(now)}\n")
            append("Источник: $sourceId (${sourceRuns.size} записей)\n")
            if (metrics.isEmpty()) {
                append("Данных для агрегации пока нет.")
            } else {
                metrics.forEach { (name, agg) ->
                    append("• $name: последнее=${formatNumber(agg.latest)}, мин=${formatNumber(agg.min)}, ")
                    append("макс=${formatNumber(agg.max)}, среднее=${formatNumber(agg.avg)}")
                    agg.delta?.let { append(", Δ=${formatSignedNumber(it)}") }
                    append(" (n=${agg.samples})\n")
                }
            }
            sourceRuns.takeLast(3).forEach { run ->
                append("  — ${formatTime(run.executedAtEpochMs)}: ${run.summary}\n")
            }
        }.trimEnd()
        val run = TaskRunRecord(
            id = newId(),
            taskId = task.id,
            executedAtEpochMs = now,
            success = true,
            summary = summary,
            metrics = metrics.mapValues { it.value.latest },
        )
        val updated = task.copy(
            lastRunAtEpochMs = now,
            runCount = task.runCount + 1,
            nextRunAtEpochMs = now + (task.intervalMs ?: 60_000),
        )
        return ExecutionResult(run, updated)
    }

    private fun collectNote(task: ScheduledTask, now: Long): CollectResult {
        val note = task.collectNote ?: task.name
        return CollectResult(
            success = true,
            summary = "Заметка «${task.name}»: $note",
            metrics = mapOf("timestamp" to now.toDouble()),
        )
    }

    private suspend fun collectGitHubRepo(task: ScheduledTask): CollectResult {
        val owner = task.collectOwner.orEmpty()
        val repo = task.collectRepo.orEmpty()
        return when (val response = githubClient.getRepository(owner, repo)) {
            is GitHubResponse.Success -> {
                val root = json.parseToJsonElement(response.body).jsonObject
                val stars = root["stargazers_count"]?.jsonPrimitive?.longOrNull?.toDouble()
                val forks = root["forks_count"]?.jsonPrimitive?.longOrNull?.toDouble()
                val openIssues = root["open_issues_count"]?.jsonPrimitive?.longOrNull?.toDouble()
                val metrics = buildMap {
                    stars?.let { put("stars", it) }
                    forks?.let { put("forks", it) }
                    openIssues?.let { put("open_issues", it) }
                }
                CollectResult(
                    success = true,
                    summary = "GitHub $owner/$repo: ⭐ ${stars?.toLong() ?: "?"} stars, forks ${forks?.toLong() ?: "?"}",
                    metrics = metrics,
                )
            }
            is GitHubResponse.Error -> CollectResult(
                success = false,
                summary = "GitHub API error ($owner/$repo): HTTP ${response.statusCode}",
            )
        }
    }

    private suspend fun collectGitHubIssues(task: ScheduledTask): CollectResult {
        val owner = task.collectOwner.orEmpty()
        val repo = task.collectRepo.orEmpty()
        return when (val response = githubClient.listIssues(owner, repo, "open")) {
            is GitHubResponse.Success -> {
                val issues = json.parseToJsonElement(response.body).jsonArray
                val count = issues.size.toDouble()
                CollectResult(
                    success = true,
                    summary = "GitHub $owner/$repo: $count open issues (sample)",
                    metrics = mapOf("open_issues_sample" to count),
                )
            }
            is GitHubResponse.Error -> CollectResult(
                success = false,
                summary = "GitHub API error ($owner/$repo): HTTP ${response.statusCode}",
            )
        }
    }

    private fun aggregateMetrics(runs: List<TaskRunRecord>): Map<String, MetricAggregation> {
        val byMetric = mutableMapOf<String, MutableList<Double>>()
        runs.filter { it.success }.forEach { run ->
            run.metrics.forEach { (key, value) ->
                if (key != "timestamp") {
                    byMetric.getOrPut(key) { mutableListOf() }.add(value)
                }
            }
        }
        return byMetric.mapValues { (_, values) ->
            val min = values.min()
            val max = values.max()
            val avg = values.average()
            val latest = values.last()
            val delta = if (values.size >= 2) latest - values[values.size - 2] else null
            MetricAggregation(min, max, avg, latest, delta, values.size)
        }
    }

    private fun buildSummaryText(now: Long, tasks: List<TaskSummary>, totalRuns: Int): String = buildString {
        append("=== Сводка планировщика (${formatTime(now)}) ===\n")
        append("Задач: ${tasks.size}, активных: ${tasks.count { it.status == TaskStatus.ACTIVE }}, ")
        append("выполнений: $totalRuns\n\n")
        if (tasks.isEmpty()) {
            append("Задач нет. Создайте reminder, collector или summary.")
            return@buildString
        }
        tasks.forEach { task ->
            append("▸ [${task.type}] ${task.name} (${task.taskId})\n")
            append("  Статус: ${task.status}, запусков: ${task.runCount}")
            task.nextRunAtEpochMs?.let { append(", следующий: ${formatTime(it)}") }
            append("\n")
            task.latestSummary?.let { append("  Последний результат: $it\n") }
            task.metrics.forEach { (name, agg) ->
                append("  $name → latest=${formatNumber(agg.latest)}, avg=${formatNumber(agg.avg)}")
                agg.delta?.let { append(", Δ=${formatSignedNumber(it)}") }
                append("\n")
            }
            append("\n")
        }
    }.trimEnd()

    private fun validateCollectorParams(kind: CollectKind, note: String?, owner: String?, repo: String?) {
        when (kind) {
            CollectKind.NOTE -> require(!note.isNullOrBlank()) { "note is required for NOTE collector" }
            CollectKind.GITHUB_REPO, CollectKind.GITHUB_ISSUES -> {
                require(!owner.isNullOrBlank()) { "owner is required for GitHub collector" }
                require(!repo.isNullOrBlank()) { "repo is required for GitHub collector" }
            }
        }
    }

    private fun formatTime(epochMs: Long): String = timeFormatter.format(Instant.ofEpochMilli(epochMs))

    private fun formatNumber(value: Double): String =
        if (value == value.roundToInt().toDouble()) value.roundToInt().toString() else "%.2f".format(value)

    private fun formatSignedNumber(value: Double): String {
        val formatted = formatNumber(kotlin.math.abs(value))
        return when {
            value > 0 -> "+$formatted"
            value < 0 -> "-$formatted"
            else -> "0"
        }
    }

    private fun newId(): String = UUID.randomUUID().toString().take(8)

    private data class ExecutionResult(val run: TaskRunRecord, val updatedTask: ScheduledTask)
    private data class CollectResult(val success: Boolean, val summary: String, val metrics: Map<String, Double> = emptyMap())
}
