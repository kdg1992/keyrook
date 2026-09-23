// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class RuntimeCheckTest {
    @TempDir lateinit var directory: Path

    @Test fun `runtime check exercises providers and renderer and writes only fixed success marker`() {
        val report = directory.resolve("result.txt")
        assertEquals(0, runRuntimeCheck(arrayOf("--self-test", report.toString())))
        assertEquals(RUNTIME_CHECK_SUCCESS, Files.readString(report))
        Files.list(directory).use { assertEquals(listOf(report), it.toList()) }
    }

    @Test fun `diagnostic refuses unknown arguments and never replaces existing output`() {
        assertEquals(2, runRuntimeCheck(arrayOf("--unknown")))
        assertEquals(2, runRuntimeCheck(arrayOf("--self-test")))
        val existing = directory.resolve("existing.txt")
        Files.writeString(existing, "preserve")
        assertEquals(1, runRuntimeCheck(arrayOf("--self-test", existing.toString())))
        assertEquals("preserve", Files.readString(existing))
    }
}
