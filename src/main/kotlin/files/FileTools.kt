package ru.sapozhnikov.files

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ru.sapozhnikov.AppLog

fun Server.registerFileTools(fileSaveService: FileSaveService) {
    addTool(
        name = "saveTextFile",
        description = "Сохраняет текстовый файл на сервере.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("filename", stringProperty())
                put("content", stringProperty())
            },
            required = listOf("filename", "content"),
        ),
    ) { request ->
        val filename = request.arguments.requiredString("filename")
            ?: return@addTool errorResult("Parameter 'filename' is required")
        val content = request.arguments?.get("content")?.jsonPrimitive?.content
            ?: return@addTool errorResult("Parameter 'content' is required")

        try {
            val path = fileSaveService.save(filename, content)
            val text = "Saved to $path"
            AppLog.info("Tool response size: ${text.length} chars")
            CallToolResult(content = listOf(TextContent(text)))
        } catch (e: IllegalArgumentException) {
            errorResult(e.message ?: "Invalid argument")
        } catch (e: Exception) {
            errorResult("Failed to save file: ${e.message}")
        }
    }
}

private fun stringProperty() = buildJsonObject {
    put("type", "string")
}

private fun Map<String, kotlinx.serialization.json.JsonElement>?.requiredString(name: String): String? =
    this?.get(name)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

private fun errorResult(message: String): CallToolResult =
    CallToolResult(content = listOf(TextContent(message)), isError = true)
