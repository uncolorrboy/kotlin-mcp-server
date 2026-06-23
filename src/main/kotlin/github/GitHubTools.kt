package ru.sapozhnikov.github

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

fun Server.registerGitHubTools(githubClient: GitHubClient) {
    addTool(
        name = "github_get_repository",
        description = "Получить информацию о репозитории GitHub (звёзды, описание, язык, ветка по умолчанию и т.д.)",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("owner", stringProperty("Владелец репозитория (логин пользователя или организации)"))
                put("repo", stringProperty("Название репозитория"))
            },
            required = listOf("owner", "repo"),
        ),
    ) { request ->
        val owner = request.arguments.requiredString("owner") ?: return@addTool errorResult("Parameter 'owner' is required")
        val repo = request.arguments.requiredString("repo") ?: return@addTool errorResult("Parameter 'repo' is required")
        githubClient.getRepository(owner, repo).toToolResult()
    }

    addTool(
        name = "github_list_issues",
        description = "Получить список issues в репозитории GitHub",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("owner", stringProperty("Владелец репозитория (логин пользователя или организации)"))
                put("repo", stringProperty("Название репозитория"))
                put(
                    "state",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Фильтр по состоянию issue: open, closed или all")
                        put("enum", kotlinx.serialization.json.buildJsonArray {
                            add(kotlinx.serialization.json.JsonPrimitive("open"))
                            add(kotlinx.serialization.json.JsonPrimitive("closed"))
                            add(kotlinx.serialization.json.JsonPrimitive("all"))
                        })
                    },
                )
            },
            required = listOf("owner", "repo"),
        ),
    ) { request ->
        val owner = request.arguments.requiredString("owner") ?: return@addTool errorResult("Parameter 'owner' is required")
        val repo = request.arguments.requiredString("repo") ?: return@addTool errorResult("Parameter 'repo' is required")
        val state = request.arguments?.get("state")?.jsonPrimitive?.content ?: "open"
        if (state !in setOf("open", "closed", "all")) {
            return@addTool errorResult("Parameter 'state' must be one of: open, closed, all")
        }
        githubClient.listIssues(owner, repo, state).toToolResult()
    }

    addTool(
        name = "github_get_user",
        description = "Получить публичную информацию о профиле пользователя GitHub",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("username", stringProperty("Имя пользователя GitHub (логин)"))
            },
            required = listOf("username"),
        ),
    ) { request ->
        val username = request.arguments.requiredString("username")
            ?: return@addTool errorResult("Parameter 'username' is required")
        githubClient.getUser(username).toToolResult()
    }

    addTool(
        name = "github_search_repositories",
        description = "Поиск репозиториев GitHub по запросу (например, 'language:kotlin stars:>100')",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("query", stringProperty("Поисковый запрос в синтаксисе GitHub Search"))
            },
            required = listOf("query"),
        ),
    ) { request ->
        val query = request.arguments.requiredString("query")
            ?: return@addTool errorResult("Parameter 'query' is required")
        githubClient.searchRepositories(query).toToolResult()
    }
}

private fun stringProperty(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun Map<String, kotlinx.serialization.json.JsonElement>?.requiredString(name: String): String? =
    this?.get(name)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

private fun errorResult(message: String): CallToolResult =
    CallToolResult(content = listOf(TextContent(message)), isError = true)

private fun GitHubResponse.toToolResult(): CallToolResult = when (this) {
    is GitHubResponse.Success -> CallToolResult(content = listOf(TextContent(body)))
    is GitHubResponse.Error -> CallToolResult(
        content = listOf(TextContent("GitHub API error ($statusCode): $body")),
        isError = true,
    )
}
