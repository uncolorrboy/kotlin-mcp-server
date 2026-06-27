package ru.sapozhnikov.pipeline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import ru.sapozhnikov.AppLog
import ru.sapozhnikov.github.GitHubClient
import ru.sapozhnikov.github.GitHubResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PipelineService(
    private val fetchSearchData: suspend (String) -> String,
    private val outputDir: Path = Path.of(System.getenv("PIPELINE_OUTPUT_DIR") ?: "data/pipeline"),
) {
    private val contexts = ConcurrentHashMap<String, PipelineContext>()
    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    suspend fun search(query: String): PipelineStepResult {
        require(query.isNotBlank()) { "query must not be blank" }

        val pipelineId = newPipelineId()
        val searchData = fetchSearchData(query)
        val context = PipelineContext(
            id = pipelineId,
            query = query,
            searchData = searchData,
            stepsCompleted = listOf("search"),
        )
        contexts[pipelineId] = context
        AppLog.info("Pipeline search id=$pipelineId query='$query' (${searchData.lines().size} lines)")

        return PipelineStepResult(
            pipelineId = pipelineId,
            step = "search",
            output = formatSearchOutput(context),
            context = context,
        )
    }

    fun summarize(pipelineId: String): PipelineStepResult {
        val context = requireContext(pipelineId)
        val searchData = context.searchData
            ?: throw IllegalStateException("Pipeline '$pipelineId' has no search data. Run search first.")

        val summary = buildSummary(context.query.orEmpty(), searchData)
        val updated = context.copy(
            summary = summary,
            stepsCompleted = context.stepsCompleted + "summarize",
        )
        contexts[pipelineId] = updated
        AppLog.info("Pipeline summarize id=$pipelineId (${summary.lines().size} lines)")

        return PipelineStepResult(
            pipelineId = pipelineId,
            step = "summarize",
            output = summary,
            context = updated,
        )
    }

    fun saveToFile(pipelineId: String, filename: String? = null): PipelineStepResult {
        val context = requireContext(pipelineId)
        val summary = context.summary
            ?: throw IllegalStateException("Pipeline '$pipelineId' has no summary. Run summarize first.")

        val safeName = sanitizeFilename(filename ?: defaultFilename(context.query))
        Files.createDirectories(outputDir)
        val filePath = outputDir.resolve(safeName)
        Files.writeString(filePath, summary, StandardCharsets.UTF_8)

        val absolutePath = filePath.toAbsolutePath().toString()
        val updated = context.copy(
            savedFilePath = absolutePath,
            stepsCompleted = context.stepsCompleted + "saveToFile",
        )
        contexts[pipelineId] = updated
        AppLog.info("Pipeline saveToFile id=$pipelineId path=$absolutePath")

        return PipelineStepResult(
            pipelineId = pipelineId,
            step = "saveToFile",
            output = "Saved to $absolutePath (${Files.size(filePath)} bytes)",
            context = updated,
        )
    }

    suspend fun runPipeline(query: String, filename: String? = null): PipelineRunResult {
        val searchStep = search(query)
        val summarizeStep = summarize(searchStep.pipelineId)
        val saveStep = saveToFile(searchStep.pipelineId, filename)

        AppLog.info(
            "Pipeline run completed id=${searchStep.pipelineId} " +
                "steps=${saveStep.context.stepsCompleted.joinToString(" → ")}",
        )

        return PipelineRunResult(
            pipelineId = searchStep.pipelineId,
            steps = listOf(searchStep, summarizeStep, saveStep),
            savedFilePath = saveStep.context.savedFilePath.orEmpty(),
            summary = summarizeStep.output,
        )
    }

    fun getContext(pipelineId: String): PipelineContext? = contexts[pipelineId]

    private fun buildSummary(query: String, searchData: String): String {
        val repoLines = searchData.lines().filter { it.matches(Regex("^\\d+\\. .+")) }
        val starLines = searchData.lines().filter { it.trimStart().startsWith("Stars:") }
        val languages = starLines.mapNotNull { line ->
            Regex("Language: (\\S+)").find(line)?.groupValues?.get(1)
        }.filter { it != "n/a" }
        val languageCounts = languages.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }

        return buildString {
            appendLine("=== Search Summary ===")
            appendLine("Generated: ${formatTime(System.currentTimeMillis())}")
            appendLine("Query: $query")
            appendLine()
            appendLine("Repositories in result set: ${repoLines.size}")
            repoLines.take(5).forEach { appendLine("• ${it.substringAfter(". ")}") }
            if (repoLines.size > 5) {
                appendLine("• ... and ${repoLines.size - 5} more")
            }
            appendLine()
            if (languageCounts.isNotEmpty()) {
                appendLine("Top languages:")
                languageCounts.take(3).forEach { (lang, count) ->
                    appendLine("• $lang: $count")
                }
                appendLine()
            }
            appendLine("--- Raw search excerpt ---")
            append(searchData.lines().take(15).joinToString("\n"))
            if (searchData.lines().size > 15) {
                append("\n...")
            }
        }
    }

    private fun formatSearchOutput(context: PipelineContext): String {
        val searchData = context.searchData.orEmpty()
        val repoLines = searchData.lines().filter { it.matches(Regex("^\\d+\\. .+")) }
        return buildString {
            appendLine("Found ${repoLines.size} repositories for query: ${context.query}")
            repoLines.take(5).forEach { line ->
                appendLine("• ${line.substringAfter(". ")}")
            }
            if (repoLines.size > 5) {
                appendLine("• ... and ${repoLines.size - 5} more")
            }
        }.trimEnd()
    }

    private fun requireContext(pipelineId: String): PipelineContext =
        contexts[pipelineId] ?: throw IllegalArgumentException("Pipeline '$pipelineId' not found")

    private fun sanitizeFilename(name: String): String {
        val cleaned = name.replace(Regex("[^a-zA-Z0-9._-]"), "_").trim('_', '.')
        if (cleaned.isBlank()) return "pipeline-result.txt"
        val lastDot = cleaned.lastIndexOf('.')
        val hasExtension = lastDot > 0 && lastDot < cleaned.length - 1
        return if (hasExtension) cleaned else "$cleaned.txt"
    }

    private fun defaultFilename(query: String?): String {
        val slug = query?.lowercase()
            ?.replace(Regex("[^a-z0-9]+"), "-")
            ?.trim('-')
            ?.take(40)
            ?.ifBlank { null }
            ?: "result"
        return "pipeline-$slug-${System.currentTimeMillis()}.txt"
    }

    private fun formatTime(epochMs: Long): String =
        timeFormatter.format(Instant.ofEpochMilli(epochMs))

    private fun newPipelineId(): String = UUID.randomUUID().toString().take(8)
}

fun PipelineService(
    githubClient: GitHubClient,
    outputDir: Path = Path.of(System.getenv("PIPELINE_OUTPUT_DIR") ?: "data/pipeline"),
): PipelineService {
    val json = Json { ignoreUnknownKeys = true }
    return PipelineService(
        fetchSearchData = { query ->
            when (val response = githubClient.searchRepositories(query)) {
                is GitHubResponse.Success -> parseGitHubSearchResponse(json, query, response.body)
                is GitHubResponse.Error -> throw IllegalStateException(
                    "GitHub search failed (HTTP ${response.statusCode}): ${response.body.take(200)}",
                )
            }
        },
        outputDir = outputDir,
    )
}

private fun parseGitHubSearchResponse(json: Json, query: String, body: String): String {
    val root = json.parseToJsonElement(body).jsonObject
    val total = root["total_count"]?.jsonPrimitive?.longOrNull ?: 0
    val items = root["items"]?.jsonArray.orEmpty()

    return buildString {
        appendLine("Search query: $query")
        appendLine("Total repositories found: $total")
        appendLine("Returned: ${items.size}")
        appendLine()
        items.forEachIndexed { index, element ->
            val repo = element.jsonObject
            val fullName = repo["full_name"]?.jsonPrimitive?.content ?: "unknown"
            val stars = repo["stargazers_count"]?.jsonPrimitive?.longOrNull ?: 0
            val language = repo["language"]?.jsonPrimitive?.content ?: "n/a"
            val description = repo["description"]?.jsonPrimitive?.content ?: ""
            appendLine("${index + 1}. $fullName")
            appendLine("   Stars: $stars | Language: $language")
            if (description.isNotBlank()) {
                appendLine("   Description: $description")
            }
            appendLine()
        }
    }.trimEnd()
}
