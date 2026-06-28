package ru.sapozhnikov.files

private val unsafeChars = Regex("[^\\p{L}\\p{N}._-]")
private val extensionPattern = Regex("^[\\p{L}\\p{N}]{1,10}$")

fun sanitizeFilename(name: String, defaultName: String = "file"): String {
    val basename = name.substringAfterLast('/').substringAfterLast('\\').trim()
    if (basename.isBlank()) return "$defaultName.txt"

    val lastDot = basename.lastIndexOf('.')
    val (namePart, extension) = if (lastDot > 0 && lastDot < basename.length - 1) {
        val ext = basename.substring(lastDot + 1)
        if (ext.matches(extensionPattern)) {
            basename.substring(0, lastDot) to ext.lowercase()
        } else {
            basename to null
        }
    } else {
        basename to null
    }

    val cleanedBase = namePart
        .replace(unsafeChars, "_")
        .trim('_')
        .ifBlank { defaultName }

    return if (extension != null) "$cleanedBase.$extension" else "$cleanedBase.txt"
}
