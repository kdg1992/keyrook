// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core

import app.keyrook.core.update.ReleaseCheck
import app.keyrook.core.update.ReleaseSource
import app.keyrook.core.update.ReleaseVersion
import app.keyrook.core.update.UpdateCheckResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class ReleaseCheckTest {
    private fun release(tag: String, draft: Boolean = false, prerelease: Boolean = false, body: String = "Notes") =
        """{"url":"https://evil.invalid/x","html_url":"https://evil.invalid/release","tag_name":"$tag",
            "draft":$draft,"prerelease":$prerelease,"body":"$body","assets":[{"browser_download_url":"https://evil.invalid/a.msi"}]}"""

    private class CountingSource(private val body: () -> ByteArray) : ReleaseSource {
        var requests = 0
        override fun latestRelease(): ByteArray { requests++; return body() }
    }

    @Test fun `tags must be canonical stable versions`() {
        assertEquals(ReleaseVersion(1, 2, 3), ReleaseVersion.parseTag("v1.2.3"))
        assertEquals(ReleaseVersion(0, 10, 0), ReleaseVersion.parseTag("v0.10.0"))
        listOf("1.2.3", "v1.2", "v1.2.3.4", "v1.2.3-rc1", "v1.2.3+build", " v1.2.3", "v1.2.3\n", "V1.2.3", "v01.2.3",
            "v1.02.3", "v1.2.03", "v99999999999.0.0", "v1.2.x", "v١.2.3", "", "vv1.2.3").forEach {
            assertNull(ReleaseVersion.parseTag(it), it)
        }
        assertEquals(ReleaseVersion(0, 4, 0), ReleaseVersion.parseRunning("0.4.0"))
        listOf(null, "dev", "", "v0.4.0", "0.4.0-SNAPSHOT", "0.4").forEach { assertNull(ReleaseVersion.parseRunning(it), "$it") }
    }

    @Test fun `versions compare numerically`() {
        val ordered = listOf("v0.4.0", "v0.4.1", "v0.9.0", "v0.10.0", "v1.0.0", "v1.0.10", "v2.0.0").map { ReleaseVersion.parseTag(it)!! }
        assertEquals(ordered, ordered.shuffled(java.util.Random(7)).sorted())
        assertTrue(ReleaseVersion(0, 10, 0) > ReleaseVersion(0, 9, 99))
        assertEquals(0, ReleaseVersion(1, 2, 3).compareTo(ReleaseVersion(1, 2, 3)))
    }

    @Test fun `newer same and older releases produce the matching result`() {
        val newer = ReleaseCheck.check("0.4.0", ReleaseSource { release("v0.10.0").toByteArray() })
        assertEquals(UpdateCheckResult.UpdateAvailable(ReleaseVersion(0, 4, 0), ReleaseVersion(0, 10, 0), "Notes"), newer)
        assertEquals(UpdateCheckResult.UpToDate(ReleaseVersion(0, 4, 0), ReleaseVersion(0, 4, 0)),
            ReleaseCheck.check("0.4.0", ReleaseSource { release("v0.4.0").toByteArray() }))
        assertEquals(UpdateCheckResult.UpToDate(ReleaseVersion(1, 0, 0), ReleaseVersion(0, 9, 0)),
            ReleaseCheck.check("1.0.0", ReleaseSource { release("v0.9.0").toByteArray() }))
    }

    @Test fun `release link is built from the validated version and never from the response`() {
        val result = ReleaseCheck.check("0.4.0", ReleaseSource { release("v1.2.3").toByteArray() }) as UpdateCheckResult.UpdateAvailable
        assertEquals("https://github.com/kdg1992/keyrook/releases/tag/v1.2.3", result.releasePage)
        assertEquals("https://github.com/kdg1992/keyrook/releases/tag/v0.10.0", ReleaseCheck.releasePage(ReleaseVersion(0, 10, 0)))
        assertFalse(result.toString().contains("evil.invalid"))
        assertEquals("https://api.github.com/repos/kdg1992/keyrook/releases/latest", ReleaseCheck.LATEST_RELEASE_API)
    }

    @Test fun `development builds are not checked`() {
        listOf(null, "dev", "0.4.0-SNAPSHOT").forEach {
            val source = CountingSource { release("v9.0.0").toByteArray() }
            assertEquals(UpdateCheckResult.DevelopmentBuild, ReleaseCheck.check(it, source))
            assertEquals(0, source.requests)
        }
    }

    @Test fun `drafts prereleases bad tags and malformed documents fail`() {
        val rejected = listOf(
            release("v9.0.0", draft = true), release("v9.0.0", prerelease = true), release("v9.0.0-rc1"), release("9.0.0"),
            release("v09.0.0"), """{"tag_name":"v9.0.0","draft":false}""", """{"tag_name":"v9.0.0","prerelease":false}""",
            """{"draft":false,"prerelease":false}""", """{"tag_name":"v9.0.0","draft":"false","prerelease":false}""",
            """{"tag_name":9,"draft":false,"prerelease":false}""", """{"tag_name":null,"draft":false,"prerelease":false}""",
            """{"tag_name":"v9.0.0","draft":null,"prerelease":false}""", "not json", "", "[]", "null", "{",
            """{tag_name:"v9.0.0",draft:false,prerelease:false}""", """{"tag_name":"v9.0.0","draft":false,"prerelease":false,"body":7}""",
        )
        rejected.forEach {
            assertNull(ReleaseCheck.parseLatest(it.toByteArray()), it)
            assertEquals(UpdateCheckResult.Failed, ReleaseCheck.check("0.4.0", ReleaseSource { it.toByteArray() }), it)
        }
        assertNull(ReleaseCheck.parseLatest(byteArrayOf(0xC3.toByte(), 0x28)))
        assertNotNull(ReleaseCheck.parseLatest("""{"tag_name":"v9.0.0","draft":false,"prerelease":false,"body":null}""".toByteArray()))
    }

    @Test fun `oversized bodies and source failures fail without detail`() {
        val padding = " ".repeat(ReleaseCheck.MAX_BODY_BYTES)
        val oversized = (release("v9.0.0") + padding).toByteArray()
        assertTrue(oversized.size > ReleaseCheck.MAX_BODY_BYTES)
        assertNull(ReleaseCheck.parseLatest(oversized))
        assertEquals(UpdateCheckResult.Failed, ReleaseCheck.check("0.4.0", ReleaseSource { oversized }))
        val fitting = (release("v9.0.0") + " ".repeat(1024)).toByteArray()
        assertNotNull(ReleaseCheck.parseLatest(fitting))
        assertEquals(UpdateCheckResult.Failed, ReleaseCheck.check("0.4.0", ReleaseSource { throw IOException("secret detail") }))
        assertEquals(UpdateCheckResult.Failed, ReleaseCheck.check("0.4.0", ReleaseSource { throw IllegalStateException("status 500") }))
        assertEquals("Failed", UpdateCheckResult.Failed.toString())
    }

    @Test fun `release notes are reduced to bounded plain text`() {
        assertEquals("a\nb\nc d", ReleaseCheck.plainNotes("a\r\nb\rc\td"))
        assertEquals("abc", ReleaseCheck.plainNotes("a\u0000b\u001b[31m".replace("[31m", "") + "‮c​\u0085"))
        assertEquals("<b>x</b> [link](https://evil.invalid)", ReleaseCheck.plainNotes("<b>x</b> [link](https://evil.invalid)"))
        val long = ReleaseCheck.plainNotes("x".repeat(ReleaseCheck.MAX_NOTES_CHARS * 2))
        assertEquals(ReleaseCheck.MAX_NOTES_CHARS + 1, long.length)
        assertTrue(long.endsWith("…"))
        assertEquals("🔒", ReleaseCheck.plainNotes("🔒"))
        assertEquals("", ReleaseCheck.plainNotes("\uD800"))
        val parsed = ReleaseCheck.parseLatest("""{"tag_name":"v1.0.0","draft":false,"prerelease":false,"body":"Fix\u0007\r\n<script>"}""".toByteArray())
        assertEquals("Fix\n<script>", parsed!!.notes)
    }
}
