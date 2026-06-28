package ru.sapozhnikov.files

import kotlin.test.Test
import kotlin.test.assertEquals

class FilenameSanitizerTest {
    @Test
    fun `preserves cyrillic name with md extension`() {
        assertEquals("отчёт.md", sanitizeFilename("отчёт.md"))
    }

    @Test
    fun `preserves ascii name with md extension`() {
        assertEquals("kotlin-report.md", sanitizeFilename("kotlin-report.md"))
    }

    @Test
    fun `adds txt extension when missing`() {
        assertEquals("report.txt", sanitizeFilename("report"))
    }

    @Test
    fun `uses basename when path is provided`() {
        assertEquals("report.md", sanitizeFilename("docs/report.md"))
    }

    @Test
    fun `does not collapse cyrillic name to md txt`() {
        assertEquals("документ.md", sanitizeFilename("документ.md"))
    }
}
