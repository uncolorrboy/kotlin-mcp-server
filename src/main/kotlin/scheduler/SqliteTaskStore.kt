package ru.sapozhnikov.scheduler

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

class SqliteTaskStore(
    dbPath: Path,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : TaskStore {
    private val mutex = Mutex()
    private val connection: Connection

    init {
        dbPath.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        connection = DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode=WAL")
            statement.execute("PRAGMA foreign_keys=ON")
        }
        initSchema()
    }

    override suspend fun getTask(id: String): ScheduledTask? = mutex.withLock {
        connection.prepareStatement(TASK_BY_ID).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { result ->
                if (result.next()) mapTask(result) else null
            }
        }
    }

    override suspend fun getActiveTasksDue(nowEpochMs: Long): List<ScheduledTask> = mutex.withLock {
        connection.prepareStatement(ACTIVE_TASKS_DUE).use { statement ->
            statement.setLong(1, nowEpochMs)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(mapTask(result))
                    }
                }
            }
        }
    }

    override suspend fun upsertTask(task: ScheduledTask) {
        mutex.withLock {
            connection.prepareStatement(UPSERT_TASK).use { statement ->
                statement.setString(1, task.id)
                statement.setString(2, task.name)
                statement.setString(3, task.type.name)
                statement.setObject(4, task.intervalMs)
                statement.setLong(5, task.nextRunAtEpochMs)
                statement.setLong(6, task.createdAtEpochMs)
                statement.setObject(7, task.lastRunAtEpochMs)
                statement.setInt(8, task.runCount)
                statement.setString(9, task.status.name)
                statement.setString(10, task.message)
                statement.setString(11, task.collectKind?.name)
                statement.setString(12, task.collectOwner)
                statement.setString(13, task.collectRepo)
                statement.setString(14, task.collectNote)
                statement.setString(15, task.sourceTaskId)
                statement.executeUpdate()
            }
        }
    }

    override suspend fun addRun(run: TaskRunRecord) {
        mutex.withLock {
            connection.prepareStatement(INSERT_RUN).use { statement ->
                statement.setString(1, run.id)
                statement.setString(2, run.taskId)
                statement.setLong(3, run.executedAtEpochMs)
                statement.setInt(4, if (run.success) 1 else 0)
                statement.setString(5, run.summary)
                statement.setString(6, json.encodeToString(run.metrics))
                statement.executeUpdate()
            }
        }
    }

    override suspend fun getRunsForTask(taskId: String): List<TaskRunRecord> = mutex.withLock {
        connection.prepareStatement(RUNS_BY_TASK).use { statement ->
            statement.setString(1, taskId)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(mapRun(result))
                    }
                }
            }
        }
    }

    override suspend fun listTasks(): List<ScheduledTask> = mutex.withLock {
        connection.prepareStatement(ALL_TASKS).use { statement ->
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(mapTask(result))
                    }
                }
            }
        }
    }

    override fun close() {
        connection.close()
    }

    private fun initSchema() {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS scheduled_tasks (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    type TEXT NOT NULL,
                    interval_ms INTEGER,
                    next_run_at_epoch_ms INTEGER NOT NULL,
                    created_at_epoch_ms INTEGER NOT NULL,
                    last_run_at_epoch_ms INTEGER,
                    run_count INTEGER NOT NULL DEFAULT 0,
                    status TEXT NOT NULL,
                    message TEXT,
                    collect_kind TEXT,
                    collect_owner TEXT,
                    collect_repo TEXT,
                    collect_note TEXT,
                    source_task_id TEXT
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS task_runs (
                    id TEXT PRIMARY KEY,
                    task_id TEXT NOT NULL,
                    executed_at_epoch_ms INTEGER NOT NULL,
                    success INTEGER NOT NULL,
                    summary TEXT NOT NULL,
                    metrics_json TEXT NOT NULL DEFAULT '{}',
                    FOREIGN KEY (task_id) REFERENCES scheduled_tasks(id)
                )
                """.trimIndent(),
            )
            statement.execute(
                "CREATE INDEX IF NOT EXISTS idx_tasks_active_due ON scheduled_tasks(status, next_run_at_epoch_ms)",
            )
            statement.execute(
                "CREATE INDEX IF NOT EXISTS idx_runs_task_id ON task_runs(task_id, executed_at_epoch_ms)",
            )
        }
    }

    private fun mapTask(result: ResultSet): ScheduledTask = ScheduledTask(
        id = result.getString("id"),
        name = result.getString("name"),
        type = TaskType.valueOf(result.getString("type")),
        intervalMs = result.getNullableLong("interval_ms"),
        nextRunAtEpochMs = result.getLong("next_run_at_epoch_ms"),
        createdAtEpochMs = result.getLong("created_at_epoch_ms"),
        lastRunAtEpochMs = result.getNullableLong("last_run_at_epoch_ms"),
        runCount = result.getInt("run_count"),
        status = TaskStatus.valueOf(result.getString("status")),
        message = result.getString("message"),
        collectKind = result.getString("collect_kind")?.let { CollectKind.valueOf(it) },
        collectOwner = result.getString("collect_owner"),
        collectRepo = result.getString("collect_repo"),
        collectNote = result.getString("collect_note"),
        sourceTaskId = result.getString("source_task_id"),
    )

    private fun ResultSet.getNullableLong(column: String): Long? {
        val value = getLong(column)
        return if (wasNull()) null else value
    }

    private fun mapRun(result: ResultSet): TaskRunRecord {
        val metricsJson = result.getString("metrics_json")
        val metrics = if (metricsJson.isNullOrBlank()) {
            emptyMap()
        } else {
            json.decodeFromString<Map<String, Double>>(metricsJson)
        }
        return TaskRunRecord(
            id = result.getString("id"),
            taskId = result.getString("task_id"),
            executedAtEpochMs = result.getLong("executed_at_epoch_ms"),
            success = result.getInt("success") == 1,
            summary = result.getString("summary"),
            metrics = metrics,
        )
    }

    private companion object {
        const val TASK_BY_ID = "SELECT * FROM scheduled_tasks WHERE id = ?"
        const val ACTIVE_TASKS_DUE =
            "SELECT * FROM scheduled_tasks WHERE status = 'ACTIVE' AND next_run_at_epoch_ms <= ?"
        const val ALL_TASKS = "SELECT * FROM scheduled_tasks ORDER BY created_at_epoch_ms DESC"
        const val RUNS_BY_TASK =
            "SELECT * FROM task_runs WHERE task_id = ? ORDER BY executed_at_epoch_ms ASC"
        const val UPSERT_TASK = """
            INSERT INTO scheduled_tasks (
                id, name, type, interval_ms, next_run_at_epoch_ms, created_at_epoch_ms,
                last_run_at_epoch_ms, run_count, status, message, collect_kind,
                collect_owner, collect_repo, collect_note, source_task_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                name = excluded.name,
                type = excluded.type,
                interval_ms = excluded.interval_ms,
                next_run_at_epoch_ms = excluded.next_run_at_epoch_ms,
                created_at_epoch_ms = excluded.created_at_epoch_ms,
                last_run_at_epoch_ms = excluded.last_run_at_epoch_ms,
                run_count = excluded.run_count,
                status = excluded.status,
                message = excluded.message,
                collect_kind = excluded.collect_kind,
                collect_owner = excluded.collect_owner,
                collect_repo = excluded.collect_repo,
                collect_note = excluded.collect_note,
                source_task_id = excluded.source_task_id
        """
        const val INSERT_RUN = """
            INSERT INTO task_runs (id, task_id, executed_at_epoch_ms, success, summary, metrics_json)
            VALUES (?, ?, ?, ?, ?, ?)
        """
    }
}
