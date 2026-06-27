package ru.sapozhnikov.pipeline

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PipelineServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private val sampleSearchData = """
        Search query: language:kotlin
        Total repositories found: 2
        Returned: 2

        1. kotlin/kotlin
           Stars: 50000 | Language: Kotlin
           Description: The Kotlin Programming Language

        2. JetBrains/kotlin
           Stars: 1000 | Language: Kotlin
           Description: Kotlin examples
    """.trimIndent()

    @Test
    fun `manual chain passes data between search summarize and saveToFile`() = runBlocking {
        val service = createService()

        val searchStep = service.search("language:kotlin")
        assertEquals("search", searchStep.step)
        assertContains(searchStep.output, "kotlin/kotlin")
        assertNotNull(searchStep.context.searchData)
        assertTrue(searchStep.context.searchData!!.lines().size > searchStep.output.lines().size)

        val summarizeStep = service.summarize(searchStep.pipelineId)
        assertEquals("summarize", summarizeStep.step)
        assertContains(summarizeStep.output, "Search Summary")
        assertContains(summarizeStep.output, "kotlin/kotlin")
        assertNotNull(summarizeStep.context.summary)

        val saveStep = service.saveToFile(searchStep.pipelineId, "test-result.txt")
        assertEquals("saveToFile", saveStep.step)
        assertTrue(saveStep.output.contains("Saved to"))

        val savedPath = Path.of(saveStep.context.savedFilePath!!)
        assertTrue(Files.exists(savedPath))
        val fileContent = Files.readString(savedPath)
        assertEquals(summarizeStep.output, fileContent)

        val finalContext = service.getContext(searchStep.pipelineId)!!
        assertEquals(listOf("search", "summarize", "saveToFile"), finalContext.stepsCompleted)
    }

    @Test
    fun `runPipeline executes full chain automatically`() = runBlocking {
        val service = createService()

        val result = service.runPipeline("language:kotlin", "auto-pipeline.txt")

        assertEquals(3, result.steps.size)
        assertEquals(listOf("search", "summarize", "saveToFile"), result.steps.map { it.step })
        assertTrue(result.savedFilePath.endsWith("auto-pipeline.txt"))
        assertContains(result.summary, "Repositories in result set: 2")

        val savedContent = Files.readString(Path.of(result.savedFilePath))
        assertEquals(result.summary, savedContent)

        val context = service.getContext(result.pipelineId)
        assertNotNull(context)
        assertEquals(result.pipelineId, context.id)
        assertEquals("language:kotlin", context.query)
        assertNotNull(context.searchData)
        assertNotNull(context.summary)
    }

    @Test
    fun `summarize fails without prior search data`() {
        val service = createService()

        val error = runCatching { service.summarize("missing-id") }.exceptionOrNull()
        assertNotNull(error)
        assertContains(error.message!!, "not found")
    }

    @Test
    fun `saveToFile fails without prior summarize`() = runBlocking {
        val service = createService()

        val searchStep = service.search("language:kotlin")
        val error = runCatching { service.saveToFile(searchStep.pipelineId) }.exceptionOrNull()
        assertNotNull(error)
        assertContains(error.message!!, "no summary")
    }

    @Test
    fun `saveToFile preserves md extension`() = runBlocking {
        val service = createService()

        val searchStep = service.search("language:kotlin")
        service.summarize(searchStep.pipelineId)
        val saveStep = service.saveToFile(searchStep.pipelineId, "kotlin-report.md")

        assertTrue(saveStep.context.savedFilePath!!.endsWith("kotlin-report.md"))
        assertTrue(Files.exists(Path.of(saveStep.context.savedFilePath!!)))
    }

    private fun createService(): PipelineService =
        PipelineService(fetchSearchData = { _ -> sampleSearchData }, outputDir = tempDir)
}
