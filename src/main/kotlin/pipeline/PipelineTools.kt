package ru.sapozhnikov.pipeline

import ru.sapozhnikov.AppLog
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

fun Server.registerPipelineTools(pipeline: PipelineService) {
    addTool(
        name = "search",
        description = "Ищет репозитории на GitHub по запросу.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("query", stringProperty())
            },
            required = listOf("query"),
        ),
    ) { request ->
        val query = request.arguments.requiredString("query")
            ?: return@addTool errorResult("Parameter 'query' is required")
        runPipeline {
            val result = pipeline.search(query)
            successResult(result)
        }
    }

    addTool(
        name = "summarize",
        description = "Формирует краткую сводку по результатам поиска.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("pipeline_id", stringProperty())
            },
            required = listOf("pipeline_id"),
        ),
    ) { request ->
        val pipelineId = request.arguments.requiredString("pipeline_id")
            ?: return@addTool errorResult("Parameter 'pipeline_id' is required")
        runPipeline {
            val result = pipeline.summarize(pipelineId)
            successResult(result)
        }
    }

    addTool(
        name = "saveToFile",
        description = "Сохраняет сводку в файл.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("pipeline_id", stringProperty())
                put("filename", stringProperty())
            },
            required = listOf("pipeline_id"),
        ),
    ) { request ->
        val pipelineId = request.arguments.requiredString("pipeline_id")
            ?: return@addTool errorResult("Parameter 'pipeline_id' is required")
        val filename = request.arguments.optionalString("filename")
        runPipeline {
            val result = pipeline.saveToFile(pipelineId, filename)
            successResult(result)
        }
    }

    addTool(
        name = "run_pipeline",
        description = "Ищет репозитории на GitHub, готовит сводку и сохраняет её в файл.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("query", stringProperty())
                put("filename", stringProperty())
            },
            required = listOf("query"),
        ),
    ) { request ->
        val query = request.arguments.requiredString("query")
            ?: return@addTool errorResult("Parameter 'query' is required")
        val filename = request.arguments.optionalString("filename")
        runPipeline {
            val result = pipeline.runPipeline(query, filename)
            toolResult(formatPipelineRun(result))
        }
    }
}

private fun toolResult(text: String): CallToolResult {
    AppLog.info("Tool response size: ${text.length} chars")
    return CallToolResult(content = listOf(TextContent(text)))
}

private fun runPipeline(block: suspend () -> CallToolResult): CallToolResult = runBlocking {
    try {
        block()
    } catch (e: IllegalArgumentException) {
        errorResult(e.message ?: "Invalid argument")
    } catch (e: IllegalStateException) {
        errorResult(e.message ?: "Invalid pipeline state")
    } catch (e: Exception) {
        errorResult("Pipeline error: ${e.message}")
    }
}

private fun successResult(result: PipelineStepResult): CallToolResult =
    toolResult(formatStepResult(result))

private fun formatStepResult(result: PipelineStepResult): String = buildString {
    appendLine("pipeline_id: ${result.pipelineId}")
    appendLine("step: ${result.step}")
    appendLine("steps_completed: ${result.context.stepsCompleted.joinToString(" → ")}")
    appendLine()
    append(result.output)
}

private fun formatPipelineRun(result: PipelineRunResult): String = buildString {
    appendLine("status: completed")
    appendLine("pipeline_id: ${result.pipelineId}")
    appendLine("saved_to: ${result.savedFilePath}")
    appendLine()
    append(result.summary)
}

private fun stringProperty() = buildJsonObject {
    put("type", "string")
}

private fun Map<String, kotlinx.serialization.json.JsonElement>?.requiredString(name: String): String? =
    this?.get(name)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

private fun Map<String, kotlinx.serialization.json.JsonElement>?.optionalString(name: String): String? =
    this?.get(name)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

private fun errorResult(message: String): CallToolResult =
    CallToolResult(content = listOf(TextContent(message)), isError = true)
