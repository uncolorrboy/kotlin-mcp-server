package ru.sapozhnikov

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import ru.sapozhnikov.github.GitHubClient
import ru.sapozhnikov.pipeline.PipelineService
import ru.sapozhnikov.pipeline.registerPipelineTools
import java.nio.file.Path

fun main(args: Array<String>) {
    val host = System.getenv("HOST") ?: "0.0.0.0"
    val port = args.firstOrNull()?.toIntOrNull() ?: 3000
    val allowedHosts = System.getenv("MCP_ALLOWED_HOSTS")
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.takeIf { it.isNotEmpty() }
    val logPath = Path.of(System.getenv("LOG_FILE_PATH") ?: "data/app.log")
    val maxLogLines = System.getenv("LOG_MAX_LINES")?.toIntOrNull() ?: 3000
    AppLog.configure(logPath, maxLogLines)

    AppLog.info("Starting MCP server on $host:$port")
    AppLog.info("Log file: ${logPath.toAbsolutePath()} (max $maxLogLines lines)")
    allowedHosts?.let { AppLog.info("Allowed hosts: ${it.joinToString()}") }

    val githubClient = GitHubClient()
    val pipelineOutputDir = Path.of(System.getenv("PIPELINE_OUTPUT_DIR") ?: "data/pipeline")
    val pipelineService = PipelineService(githubClient, pipelineOutputDir)

    val mcpServer = Server(
        serverInfo = Implementation(
            name = "ai-challenge-mcp-server",
            version = "1.4.0",
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
            ),
        ),
    )

    mcpServer.registerPipelineTools(pipelineService)

    Runtime.getRuntime().addShutdownHook(Thread {
        AppLog.info("Shutting down...")
        githubClient.close()
    })

    AppLog.info("MCP endpoint: http://$host:$port/mcp")
    AppLog.info("Pipeline output: ${pipelineOutputDir.toAbsolutePath()}")

    embeddedServer(CIO, host = host, port = port) {
        mcpStreamableHttp(
            enableDnsRebindingProtection = allowedHosts != null,
            allowedHosts = allowedHosts,
        ) {
            mcpServer
        }
    }.start(wait = true)
}
