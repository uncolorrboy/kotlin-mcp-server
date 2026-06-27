package ru.sapozhnikov.pipeline

data class PipelineContext(
    val id: String,
    val query: String? = null,
    val searchData: String? = null,
    val summary: String? = null,
    val savedFilePath: String? = null,
    val stepsCompleted: List<String> = emptyList(),
)

data class PipelineStepResult(
    val pipelineId: String,
    val step: String,
    val output: String,
    val context: PipelineContext,
)

data class PipelineRunResult(
    val pipelineId: String,
    val steps: List<PipelineStepResult>,
    val savedFilePath: String,
    val summary: String,
)
