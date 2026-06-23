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

fun main(args: Array<String>) {
    val host = System.getenv("HOST") ?: "0.0.0.0"
    val port = args.firstOrNull()?.toIntOrNull() ?: 3000
    val allowedHosts = System.getenv("MCP_ALLOWED_HOSTS")
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.takeIf { it.isNotEmpty() }
    val githubClient = GitHubClient()

    val mcpServer = Server(
        serverInfo = Implementation(
            name = "github-mcp-server",
            version = "1.0.0",
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
            ),
        ),
    )

    mcpServer.registerGitHubTools(githubClient)

    Runtime.getRuntime().addShutdownHook(Thread {
        githubClient.close()
    })

    embeddedServer(CIO, host = host, port = port) {
        mcpStreamableHttp(
            enableDnsRebindingProtection = allowedHosts != null,
            allowedHosts = allowedHosts,
        ) {
            mcpServer
        }
    }.start(wait = true)
}
