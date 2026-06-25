package ru.sapozhnikov

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import ru.sapozhnikov.github.GitHubClient
import ru.sapozhnikov.github.registerGitHubTools
import ru.sapozhnikov.scheduler.SchedulerService
import ru.sapozhnikov.scheduler.SqliteTaskStore
import ru.sapozhnikov.scheduler.registerSchedulerTools
import java.nio.file.Path

fun main(args: Array<String>) {
    val host = System.getenv("HOST") ?: "0.0.0.0"
    val port = args.firstOrNull()?.toIntOrNull() ?: 3000
    val allowedHosts = System.getenv("MCP_ALLOWED_HOSTS")
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.takeIf { it.isNotEmpty() }
    val dbPath = Path.of(System.getenv("TASK_STORE_PATH") ?: "data/scheduler.db")
    val logPath = Path.of(System.getenv("LOG_FILE_PATH") ?: "data/app.log")
    val maxLogLines = System.getenv("LOG_MAX_LINES")?.toIntOrNull() ?: 3000
    AppLog.configure(logPath, maxLogLines)

    AppLog.info("Starting MCP server on $host:$port")
    AppLog.info("Task store: ${dbPath.toAbsolutePath()}")
    AppLog.info("Log file: ${logPath.toAbsolutePath()} (max $maxLogLines lines)")
    allowedHosts?.let { AppLog.info("Allowed hosts: ${it.joinToString()}") }

    val githubClient = GitHubClient()
    val taskStore = SqliteTaskStore(dbPath)
    val schedulerService = SchedulerService(taskStore, githubClient)

    val mcpServer = Server(
        serverInfo = Implementation(
            name = "ai-challenge-mcp-server",
            version = "1.2.0",
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
            ),
        ),
    )

    mcpServer.registerGitHubTools(githubClient)
    mcpServer.registerSchedulerTools(schedulerService)

    schedulerService.start()

    Runtime.getRuntime().addShutdownHook(Thread {
        AppLog.info("Shutting down...")
        schedulerService.stop()
        taskStore.close()
        githubClient.close()
    })

    AppLog.info("MCP endpoint: http://$host:$port/mcp")

    embeddedServer(CIO, host = host, port = port) {
        mcpStreamableHttp(
            enableDnsRebindingProtection = allowedHosts != null,
            allowedHosts = allowedHosts,
        ) {
            mcpServer
        }
    }.start(wait = true)
}
