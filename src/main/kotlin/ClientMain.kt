package ru.sapozhnikov

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun main(args: Array<String>) = runBlocking {
    val url = args.firstOrNull() ?: "http://127.0.0.1:3000/mcp"

    val httpClient = HttpClient(CIO) { install(SSE) }

    val client = Client(
        clientInfo = Implementation(
            name = "example-client",
            version = "1.0.0"
        )
    )

    val transport = StreamableHttpClientTransport(
        client = httpClient,
        url = url
    )

    try {
        client.connect(transport)

        val tools = client.listTools().tools
        println("Available tools:")
        tools.forEach { tool ->
            println("  - ${tool.name}: ${tool.description}")
        }

        val result = client.callTool(
            name = "example-tool",
            arguments = buildJsonObject { put("input", "from client") }
        )
        println("Tool result:")
        result.content.forEach { content ->
            println("  $content")
        }
    } finally {
        client.close()
        httpClient.close()
    }
}
