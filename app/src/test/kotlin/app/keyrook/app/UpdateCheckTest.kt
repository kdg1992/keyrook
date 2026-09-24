// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.update.ReleaseSource
import app.keyrook.core.update.ReleaseVersion
import app.keyrook.core.update.UpdateCheckResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow

class UpdateCheckTest {
    @TempDir lateinit var directory: Path

    private class CountingSource(private val body: () -> ByteArray) : ReleaseSource {
        var requests = 0
        override fun latestRelease(): ByteArray { requests++; return body() }
    }

    private fun release(tag: String) =
        """{"tag_name":"$tag","draft":false,"prerelease":false,"html_url":"https://evil.invalid/","body":"Fixes"}""".toByteArray()

    private fun checks(source: ReleaseSource, version: String = "0.4.0") =
        UpdateChecks(version, source, background = { it.run() }, deliver = { it.run() })

    @Test fun `request contains only the fixed address and two headers`() {
        val request = latestReleaseRequest("0.4.0")
        assertEquals("GET", request.method())
        assertEquals(URI("https://api.github.com/repos/kdg1992/keyrook/releases/latest"), request.uri())
        assertNull(request.uri().rawQuery)
        assertNull(request.uri().rawUserInfo)
        assertEquals(mapOf("Accept" to listOf("application/vnd.github+json"), "User-Agent" to listOf("Keyrook/0.4.0")),
            request.headers().map())
        assertEquals(Duration.ofSeconds(10), request.timeout().get())
        assertTrue(request.bodyPublisher().map { it.contentLength() <= 0 }.orElse(true))
        assertEquals(listOf("Keyrook/dev"), latestReleaseRequest("dev").headers().allValues("User-Agent"))
        assertEquals(listOf("Keyrook/dev"), latestReleaseRequest("0.4.0\r\nCookie: x").headers().allValues("User-Agent"))
        assertEquals(listOf("Keyrook/dev"), latestReleaseRequest(null).headers().allValues("User-Agent"))
    }

    @Test fun `client refuses redirects and stores no cookies or credentials`() {
        val client = updateHttpClient()
        try {
            assertEquals(HttpClient.Redirect.NEVER, client.followRedirects())
            assertTrue(client.cookieHandler().isEmpty)
            assertTrue(client.authenticator().isEmpty)
            assertEquals(Duration.ofSeconds(10), client.connectTimeout().get())
            assertTrue(client.proxy().isPresent)
        } finally { client.shutdownNow() }
    }

    @Test fun `response bodies are bounded`() {
        var cancelled = false
        val subscription = object : Flow.Subscription {
            override fun request(n: Long) = Unit
            override fun cancel() { cancelled = true }
        }
        val fitting = BoundedBodySubscriber(8)
        fitting.onSubscribe(subscription)
        fitting.onNext(listOf(ByteBuffer.wrap(byteArrayOf(1, 2, 3)), ByteBuffer.wrap(byteArrayOf(4, 5, 6, 7, 8))))
        fitting.onComplete()
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), fitting.body.toCompletableFuture().get())
        assertFalse(cancelled)
        val oversized = BoundedBodySubscriber(8)
        oversized.onSubscribe(subscription)
        oversized.onNext(listOf(ByteBuffer.wrap(ByteArray(5))))
        oversized.onNext(listOf(ByteBuffer.wrap(ByteArray(4))))
        oversized.onComplete()
        assertTrue(cancelled)
        val failure = assertThrows(ExecutionException::class.java) { oversized.body.toCompletableFuture().get() }
        assertInstanceOf(IOException::class.java, failure.cause)
    }

    @Test fun `no request is made unless enabled or clicked`() {
        val source = CountingSource { release("v9.0.0") }
        val updates = checks(source)
        updates.checkOnStart(AppSettings())
        assertEquals(0, source.requests)
        assertEquals(UpdateStatus.Idle, updates.status)
        assertNull(updates.notice)
        updates.checkOnStart(AppSettings(checkUpdatesOnStart = true))
        assertEquals(1, source.requests)
        updates.checkNow()
        assertEquals(2, source.requests)
    }

    @Test fun `only an automatic check with a newer version shows the notice`() {
        val newer = checks(CountingSource { release("v0.5.0") })
        newer.checkNow()
        val result = (newer.status as UpdateStatus.Finished).result as UpdateCheckResult.UpdateAvailable
        assertEquals(ReleaseVersion(0, 5, 0), result.latest)
        assertEquals("Fixes", result.notes)
        assertNull(newer.notice)
        newer.checkOnStart(AppSettings(checkUpdatesOnStart = true))
        assertEquals(result, newer.notice)
        newer.dismissNotice()
        assertNull(newer.notice)
        val same = checks(CountingSource { release("v0.4.0") })
        same.checkOnStart(AppSettings(checkUpdatesOnStart = true))
        assertInstanceOf(UpdateCheckResult.UpToDate::class.java, (same.status as UpdateStatus.Finished).result)
        assertNull(same.notice)
        val failing = checks(CountingSource { throw IOException("host.example: detail") })
        failing.checkOnStart(AppSettings(checkUpdatesOnStart = true))
        assertEquals(UpdateStatus.Finished(UpdateCheckResult.Failed), failing.status)
        assertNull(failing.notice)
    }

    @Test fun `development builds never contact the source`() {
        val source = CountingSource { release("v9.0.0") }
        val updates = checks(source, version = "dev")
        updates.checkNow()
        updates.checkOnStart(AppSettings(checkUpdatesOnStart = true))
        assertEquals(0, source.requests)
        assertEquals(UpdateStatus.Finished(UpdateCheckResult.DevelopmentBuild), updates.status)
        assertNull(updates.notice)
    }

    @Test fun `concurrent requests are not started twice`() {
        val source = CountingSource { release("v9.0.0") }
        val pending = mutableListOf<Runnable>()
        val updates = UpdateChecks("0.4.0", source, background = { pending.add(it) }, deliver = { it.run() })
        updates.checkNow()
        updates.checkNow()
        assertEquals(UpdateStatus.Checking, updates.status)
        assertEquals(1, pending.size)
        pending.single().run()
        assertEquals(1, source.requests)
    }

    @Test fun `release page opens only the constructed and validated address`() {
        val opened = mutableListOf<URI>()
        val result = UpdateCheckResult.UpdateAvailable(ReleaseVersion(0, 4, 0), ReleaseVersion(1, 2, 3), "")
        assertTrue(openReleasePage(result) { opened.add(it) })
        assertEquals(listOf(URI("https://github.com/kdg1992/keyrook/releases/tag/v1.2.3")), opened)
        assertFalse(openReleasePage(result) { throw UnsupportedOperationException() })
    }

    @Test fun `update check on start is off by default and persists when enabled`() {
        val config = directory.toRealPath().resolve("config")
        val store = SettingsStore(config)
        assertFalse(store.current().checkUpdatesOnStart)
        assertTrue(store.update { it.copy(checkUpdatesOnStart = true) })
        assertTrue(Files.readString(config.resolve(SettingsStore.FILE_NAME)).contains("\"updateCheck\": \"ON_START\""))
        assertTrue(SettingsStore(config).current().checkUpdatesOnStart)
        assertTrue(store.update { it.copy(checkUpdatesOnStart = false) })
        assertFalse(SettingsStore(config).current().checkUpdatesOnStart)
    }

    @Test fun `older settings files and unknown values keep the check disabled`() {
        val config = Files.createDirectory(directory.toRealPath().resolve("config"))
        val file = config.resolve(SettingsStore.FILE_NAME)
        Files.writeString(file, """{"version":1,"theme":"DARK","language":"ENGLISH","inactivityMinutes":15,"clipboardSeconds":60}""")
        val old = SettingsStore(config).current()
        assertEquals(AppSettings(theme = ThemeMode.DARK, inactivityMinutes = 15, clipboardSeconds = 60, language = AppLanguage.ENGLISH), old)
        assertFalse(old.checkUpdatesOnStart)
        listOf("\"on_start\"", "\"YES\"", "\"\"", "null", "\"MANUAL\"").forEach {
            Files.writeString(file, """{"version":1,"theme":"DARK","updateCheck":$it}""")
            val loaded = SettingsStore(config).current()
            assertEquals(ThemeMode.DARK, loaded.theme, it)
            assertFalse(loaded.checkUpdatesOnStart, it)
        }
        Files.writeString(file, """{"version":1,"theme":"DARK","updateCheck":true}""")
        assertEquals(AppSettings(), SettingsStore(config).current())
    }
}
