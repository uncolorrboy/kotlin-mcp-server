package ru.sapozhnikov.files

import ru.sapozhnikov.AppLog
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class FileSaveService(
    private val outputDir: Path = Path.of(System.getenv("FILE_OUTPUT_DIR") ?: "data/files"),
) {
    fun save(filename: String, content: String): String {
        require(filename.isNotBlank()) { "filename must not be blank" }

        val safeName = sanitizeFilename(filename, defaultName = "file")
        Files.createDirectories(outputDir)

        val filePath = outputDir.resolve(safeName).normalize().toAbsolutePath()
        val basePath = outputDir.toAbsolutePath().normalize()
        require(filePath.startsWith(basePath)) { "Invalid filename" }

        Files.writeString(filePath, content, StandardCharsets.UTF_8)
        val absolutePath = filePath.toString()
        AppLog.info("Saved text file: $absolutePath (${Files.size(filePath)} bytes)")
        return absolutePath
    }

}
