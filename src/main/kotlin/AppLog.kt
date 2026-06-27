package ru.sapozhnikov

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object AppLog {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())
    private val lock = Any()

    private var logPath: Path = Path.of(System.getenv("LOG_FILE_PATH") ?: "data/app.log")
    private var maxLines: Int = System.getenv("LOG_MAX_LINES")?.toIntOrNull()?.coerceAtLeast(1) ?: 3000

    fun configure(logFilePath: Path, maxLogLines: Int = 3000) {
        synchronized(lock) {
            logPath = logFilePath
            maxLines = maxLogLines.coerceAtLeast(1)
        }
    }

    fun info(message: String) = log("INFO", message)

    fun warn(message: String) = log("WARN", message)

    fun error(message: String, throwable: Throwable? = null) {
        log("ERROR", message)
        throwable?.stackTraceToString()
            ?.lineSequence()
            ?.filter { it.isNotBlank() }
            ?.forEach { log("ERROR", it) }
    }

    private fun log(level: String, message: String) {
        val line = "${formatter.format(Instant.now())} [$level] $message"
        println(line)
        synchronized(lock) {
            appendLine(line)
        }
    }

    private fun appendLine(line: String) {
        try {
            logPath.parent?.let { Files.createDirectories(it) }
            val lines = if (Files.exists(logPath)) {
                Files.readAllLines(logPath, StandardCharsets.UTF_8).toMutableList()
            } else {
                mutableListOf()
            }
            lines.add(line)
            val trimmed = if (lines.size > maxLines) lines.takeLast(maxLines) else lines
            Files.write(logPath, trimmed, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            System.err.println("Failed to write log file: ${e.message}")
        }
    }
}
