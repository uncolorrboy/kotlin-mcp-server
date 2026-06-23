package ru.sapozhnikov.github

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess

class GitHubClient {

    private val client = HttpClient(CIO) {
        defaultRequest {
            url("https://api.github.com")
            headers.append(HttpHeaders.Accept, "application/vnd.github+json")
            headers.append("X-GitHub-Api-Version", "2022-11-28")
        }
    }

    suspend fun getRepository(owner: String, repo: String): GitHubResponse =
        request("/repos/$owner/$repo")

    suspend fun listIssues(owner: String, repo: String, state: String): GitHubResponse =
        request("/repos/$owner/$repo/issues?state=$state&per_page=30")

    suspend fun getUser(username: String): GitHubResponse =
        request("/users/$username")

    suspend fun searchRepositories(query: String): GitHubResponse =
        request("/search/repositories?q=${encodeQuery(query)}&per_page=10")

    private suspend fun request(path: String): GitHubResponse {
        val response: HttpResponse = client.get(path)
        val body = response.body<String>()
        return if (response.status.isSuccess()) {
            GitHubResponse.Success(body)
        } else {
            GitHubResponse.Error(response.status.value, body)
        }
    }

    fun close() = client.close()

    private fun encodeQuery(query: String): String =
        query.map { char ->
            when (char) {
                ' ' -> "%20"
                else -> char
            }
        }.joinToString("")
}

sealed class GitHubResponse {
    data class Success(val body: String) : GitHubResponse()
    data class Error(val statusCode: Int, val body: String) : GitHubResponse()
}
