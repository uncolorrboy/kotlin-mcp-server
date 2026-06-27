package ru.sapozhnikov.scheduler

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

fun Server.registerSchedulerTools(scheduler: SchedulerService) {
    addTool(
        name = "scheduler_create_reminder",
        description = "Создать отложенное напоминание. Задача выполнится один раз через указанное число минут и сохранит результат в хранилище.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("name", stringProperty("Название напоминания"))
                put("message", stringProperty("Текст напоминания"))
                put("delay_minutes", numberProperty("Через сколько минут сработать (минимум 1)"))
            },
            required = listOf("name", "message", "delay_minutes"),
        ),
    ) { request ->
        val name = request.arguments.requiredString("name") ?: return@addTool errorResult("Parameter 'name' is required")
        val message = request.arguments.requiredString("message") ?: return@addTool errorResult("Parameter 'message' is required")
        val delayMinutes = request.arguments.requiredLong("delay_minutes")
            ?: return@addTool errorResult("Parameter 'delay_minutes' is required")
        runScheduler {
            val task = scheduler.createReminder(name, message, delayMinutes)
            successResult(
                "Напоминание создано: id=${task.id}, сработает через $delayMinutes мин.",
                task,
            )
        }
    }

    addTool(
        name = "scheduler_create_collector",
        description = "Создать периодический сбор данных (заметка, метрики GitHub-репозитория или open issues).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("name", stringProperty("Название задачи сбора"))
                put("interval_minutes", numberProperty("Интервал между запусками в минутах (минимум 1)"))
                put(
                    "kind",
                    enumProperty(
                        description = "Тип сбора данных",
                        values = listOf("note", "github_repo", "github_issues"),
                    ),
                )
                put("note", stringProperty("Текст заметки (обязательно для kind=note)"))
                put("owner", stringProperty("Владелец репозитория GitHub (для github_*)"))
                put("repo", stringProperty("Название репозитория GitHub (для github_*)"))
            },
            required = listOf("name", "interval_minutes", "kind"),
        ),
    ) { request ->
        val name = request.arguments.requiredString("name") ?: return@addTool errorResult("Parameter 'name' is required")
        val intervalMinutes = request.arguments.requiredLong("interval_minutes")
            ?: return@addTool errorResult("Parameter 'interval_minutes' is required")
        val kindStr = request.arguments.requiredString("kind") ?: return@addTool errorResult("Parameter 'kind' is required")
        val kind = parseCollectKind(kindStr)
            ?: return@addTool errorResult("Parameter 'kind' must be one of: note, github_repo, github_issues")
        val note = request.arguments.optionalString("note")
        val owner = request.arguments.optionalString("owner")
        val repo = request.arguments.optionalString("repo")
        runScheduler {
            val task = scheduler.createCollector(name, intervalMinutes, kind, note, owner, repo)
            successResult(
                "Сборщик создан: id=${task.id}, интервал $intervalMinutes мин., kind=$kindStr",
                task,
            )
        }
    }

    addTool(
        name = "scheduler_create_summary",
        description = "Создать регулярную сводку по данным из задачи-сборщика (COLLECT). Агрегирует метрики: min, max, avg, latest, delta.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("name", stringProperty("Название задачи сводки"))
                put("interval_minutes", numberProperty("Интервал между сводками в минутах (минимум 1)"))
                put("source_task_id", stringProperty("ID задачи-сборщика (из scheduler_create_collector)"))
            },
            required = listOf("name", "interval_minutes", "source_task_id"),
        ),
    ) { request ->
        val name = request.arguments.requiredString("name") ?: return@addTool errorResult("Parameter 'name' is required")
        val intervalMinutes = request.arguments.requiredLong("interval_minutes")
            ?: return@addTool errorResult("Parameter 'interval_minutes' is required")
        val sourceTaskId = request.arguments.requiredString("source_task_id")
            ?: return@addTool errorResult("Parameter 'source_task_id' is required")
        runScheduler {
            val task = scheduler.createSummary(name, intervalMinutes, sourceTaskId)
            successResult(
                "Сводка создана: id=${task.id}, источник=$sourceTaskId, интервал $intervalMinutes мин.",
                task,
            )
        }
    }

    addTool(
        name = "scheduler_list_tasks",
        description = "Список всех запланированных задач (reminder, collector, summary) с их статусом и расписанием.",
        inputSchema = ToolSchema(properties = buildJsonObject {}),
    ) {
        runScheduler {
            val tasks = scheduler.listTasks()
            if (tasks.isEmpty()) {
                CallToolResult(content = listOf(TextContent("Задач нет.")))
            } else {
                val text = tasks.joinToString("\n\n") { formatTask(it) }
                CallToolResult(content = listOf(TextContent(text)))
            }
        }
    }

    addTool(
        name = "scheduler_get_summary",
        description = "Получить агрегированную сводку по всем задачам или по конкретной задаче. Включает метрики, последние результаты и статистику запусков.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("task_id", stringProperty("ID задачи (опционально; без него — сводка по всем)"))
            },
        ),
    ) { request ->
        val taskId = request.arguments.optionalString("task_id")
        runScheduler {
            val summary = scheduler.getSummary(taskId)
            CallToolResult(content = listOf(TextContent(summary.text)))
        }
    }

    addTool(
        name = "scheduler_cancel_task",
        description = "Остановить одну задачу по ID. Задачи работают на сервере независимо от MCP-клиента — их нужно отменять явно через этот инструмент.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("task_id", stringProperty("ID задачи для отмены"))
            },
            required = listOf("task_id"),
        ),
    ) { request ->
        val taskId = request.arguments.requiredString("task_id")
            ?: return@addTool errorResult("Parameter 'task_id' is required")
        runScheduler {
            val task = scheduler.cancelTask(taskId)
            CallToolResult(content = listOf(TextContent("Задача ${task.id} («${task.name}») остановлена, статус: ${task.status}.")))
        }
    }

    addTool(
        name = "scheduler_cancel_all_tasks",
        description = "Остановить все активные задачи. Можно отфильтровать по типу: collect, summary, reminder. Используйте для полной остановки фонового сбора данных.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put(
                    "type",
                    enumProperty(
                        description = "Остановить только задачи этого типа (опционально)",
                        values = listOf("collect", "summary", "reminder"),
                    ),
                )
            },
        ),
    ) { request ->
        val typeStr = request.arguments.optionalString("type")
        val type = typeStr?.let { parseTaskType(it) }
            ?: if (typeStr != null) {
                return@addTool errorResult("Parameter 'type' must be one of: collect, summary, reminder")
            } else {
                null
            }
        runScheduler {
            val count = scheduler.cancelAllTasks(type)
            val scope = typeStr ?: "все"
            CallToolResult(content = listOf(TextContent("Остановлено задач ($scope): $count")))
        }
    }
}

private fun runScheduler(block: suspend () -> CallToolResult): CallToolResult = runBlocking {
    try {
        block()
    } catch (e: IllegalArgumentException) {
        errorResult(e.message ?: "Invalid argument")
    } catch (e: Exception) {
        errorResult("Scheduler error: ${e.message}")
    }
}

private fun successResult(message: String, task: ScheduledTask): CallToolResult =
    CallToolResult(content = listOf(TextContent("$message\n\n${formatTask(task)}")))

private fun formatTask(task: ScheduledTask): String = buildString {
    append("ID: ${task.id}\n")
    append("Название: ${task.name}\n")
    append("Тип: ${task.type}\n")
    append("Статус: ${task.status}\n")
    append("Запусков: ${task.runCount}\n")
    if (task.status == TaskStatus.ACTIVE) {
        append("Следующий запуск (epoch ms): ${task.nextRunAtEpochMs}\n")
    }
    task.message?.let { append("Сообщение: $it\n") }
    task.collectKind?.let { append("Сбор: $it\n") }
    task.sourceTaskId?.let { append("Источник сводки: $it\n") }
}

private fun parseCollectKind(value: String): CollectKind? = when (value.lowercase()) {
    "note" -> CollectKind.NOTE
    "github_repo" -> CollectKind.GITHUB_REPO
    "github_issues" -> CollectKind.GITHUB_ISSUES
    else -> null
}

private fun parseTaskType(value: String): TaskType? = when (value.lowercase()) {
    "collect" -> TaskType.COLLECT
    "summary" -> TaskType.SUMMARY
    "reminder" -> TaskType.REMINDER
    else -> null
}

private fun stringProperty(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun numberProperty(description: String) = buildJsonObject {
    put("type", "number")
    put("description", description)
}

private fun enumProperty(description: String, values: List<String>) = buildJsonObject {
    put("type", "string")
    put("description", description)
    put("enum", kotlinx.serialization.json.buildJsonArray {
        values.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
    })
}

private fun Map<String, kotlinx.serialization.json.JsonElement>?.requiredString(name: String): String? =
    this?.get(name)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

private fun Map<String, kotlinx.serialization.json.JsonElement>?.optionalString(name: String): String? =
    this?.get(name)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

private fun Map<String, kotlinx.serialization.json.JsonElement>?.requiredLong(name: String): Long? {
    val raw = this?.get(name)?.jsonPrimitive?.content ?: return null
    val value = raw.toLongOrNull() ?: return null
    return value.takeIf { it > 0 }
}

private fun errorResult(message: String): CallToolResult =
    CallToolResult(content = listOf(TextContent(message)), isError = true)
