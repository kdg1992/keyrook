// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.transfer.CsvMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

class DialogActionsTest {
    @TempDir lateinit var directory: Path

    @AfterEach fun restoreGerman() { UiText.select(AppLanguage.GERMAN) }

    /** Answers every request with [answer] and records what was asked. */
    private class Answering(private val answer: (DialogRequest<*>) -> Any?) : Dialogs {
        val asked = mutableListOf<DialogRequest<*>>()
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> ask(request: DialogRequest<T>): T? { asked += request; return answer(request) as T? }
    }

    private fun input(password: String, repeated: String = password, key: String = "") =
        CredentialInput(password.toCharArray(), repeated.toCharArray(), key)

    private fun CredentialInput.erased() = password.all { it == '\u0000' } && repeated.all { it == '\u0000' }

    @Test fun `entered passwords are erased after credentials are built`() {
        val entered = input("synthetic-password")
        val dialogs = Answering { entered }
        askCredentials(dialogs, "Keyrook", confirm = true)!!.close()
        assertTrue(entered.erased())
        val request = dialogs.asked.single() as CredentialRequest
        assertTrue(request.confirm)
        assertFalse(request.replacing)
    }

    @Test fun `rejected credential input is erased on every path`() {
        val mismatch = input("synthetic-password", "other-password")
        assertThrows(IllegalArgumentException::class.java) { askCredentials(Answering { mismatch }, "Keyrook", confirm = true) }
        assertTrue(mismatch.erased())
        val empty = input("")
        assertThrows(IllegalArgumentException::class.java) { askCredentials(Answering { empty }, "Keyrook") }
        val short = directory.resolve("short.key")
        Files.write(short, ByteArray(16) { 7 })
        val badKey = input("synthetic-password", key = short.toString())
        assertThrows(IllegalArgumentException::class.java) { askCredentials(Answering { badKey }, "Keyrook") }
        assertTrue(badKey.erased())
        assertNull(askCredentials(Answering { null }, "Keyrook"))
    }

    @Test fun `a valid key file is read and combined with the password`() {
        val key = directory.resolve("factor.key")
        Files.write(key, ByteArray(32) { it.toByte() })
        val entered = input("synthetic-password", key = key.toString())
        askCredentials(Answering { entered }, "Keyrook")!!.close()
        assertTrue(entered.erased())
    }

    @Test fun `choices map back by position and cancellation means no`() {
        val labels = mutableListOf<List<String>>()
        val dialogs = Answering { request -> labels += (request as ChoiceRequest).labels; 1 }
        listOf(Locale.GERMAN, Locale.ENGLISH).forEach { locale ->
            UiText.select(if (locale == Locale.GERMAN) AppLanguage.GERMAN else AppLanguage.ENGLISH)
            assertEquals(ImportFormat.MAPPED_CSV, dialogs.choose("Format", ImportFormat.entries, ImportFormat::label))
        }
        assertNotEquals(labels[0], labels[1])
        assertNull(Answering { 99 }.choose("Format", PlaintextFormat.entries, PlaintextFormat::label))
        assertNull(Answering { null }.choose("Format", PlaintextFormat.entries, PlaintextFormat::label))
        assertFalse(Answering { null }.confirm("Continue?"))
        assertFalse(Answering { false }.confirm("Continue?"))
        assertTrue(Answering { true }.confirm("Continue?"))
    }

    @Test fun `CSV mapping dialog receives column names and suggestions only`() {
        val columns = listOf("Name", "Login", "Passwort", "Extra")
        val dialogs = Answering { request ->
            val mapping = request as CsvMappingRequest
            assertEquals(columns, mapping.columns)
            assertEquals(CsvMapping("Name", null, "Login", "Passwort", null), mapping.suggested)
            CsvMapping("Extra")
        }
        assertEquals(CsvMapping("Extra"), askCsvMapping(dialogs, columns))
        assertNull(askCsvMapping(Answering { null }, columns))
        assertEquals("2: a b", csvColumnLabel(listOf("x", "a\nb"), 1))
    }

    @Test fun `locking cancels a waiting dialog and the operation expires`() {
        val worker = Executors.newSingleThreadExecutor()
        try {
            VaultController().use { controller ->
                val host = DialogHost()
                val token = controller.sessionEpoch.capture()
                val waiting = worker.submit<Boolean> {
                    withOperationGuard(controller, token) { host.confirm("Continue?") }
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (host.shown == null) {
                    check(System.nanoTime() < deadline) { "No dialog shown" }
                    SwingUtilities.invokeAndWait {}
                    Thread.sleep(5)
                }
                val shown = host.shown as ConfirmRequest
                SwingUtilities.invokeAndWait {
                    controller.sessionEpoch.invalidate()
                    host.cancelAll()
                    assertNull(host.shown)
                    host.answer(shown, true)
                }
                val failure = assertThrows(ExecutionException::class.java) { waiting.get(5, TimeUnit.SECONDS) }
                assertTrue(failure.cause is IllegalStateException)
                val late = worker.submit<Boolean> { withOperationGuard(controller, token) { host.confirm("Again?") } }
                assertTrue(assertThrows(ExecutionException::class.java) { late.get(5, TimeUnit.SECONDS) }.cause is IllegalStateException)
                SwingUtilities.invokeAndWait {}
                assertNull(host.shown)
            }
        } finally { worker.shutdownNow() }
    }

    @Test fun `dialog buttons are translated in both catalogs`() {
        assertEquals("OK", UiText.localized(Locale.ENGLISH, "common.ok"))
        listOf("common.cancel", "common.yes", "common.no", "dialog.password", "dialog.repeatPassword").forEach { key ->
            assertTrue(UiText.localized(Locale.ENGLISH, key).isNotBlank())
            assertNotEquals(UiText.localized(Locale.GERMAN, key), UiText.localized(Locale.ENGLISH, key))
        }
        assertEquals("Ja", UiText.localized(Locale.GERMAN, "common.yes"))
        assertEquals("No", UiText.localized(Locale.ENGLISH, "common.no"))
    }
}
