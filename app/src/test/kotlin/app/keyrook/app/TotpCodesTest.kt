// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.crypto.Secret
import app.keyrook.core.model.*
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.time.Instant
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TotpCodesTest {
    /** RFC 6238 appendix B SHA-1 key "12345678901234567890" in Base32. */
    private val rfcSecret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
    private val at59 = Instant.ofEpochSecond(59)

    @AfterEach fun restoreGerman() { UiText.select(AppLanguage.GERMAN) }

    private fun field(value: String, hidden: Boolean = true, kind: FieldKind = FieldKind.TEXT) =
        Field(Secret(value.toCharArray()), hidden, kind)

    private fun web(totp: String?) = EntryData.Web(field("https://example.invalid", false, FieldKind.URL),
        field("sample", false), field("synthetic-secret"), totp?.let { field(it) })

    private fun close(data: EntryData) = data.fields().forEach { it.value.close() }

    @Test fun `the TOTP quick action copies the current code, never the stored secret`() {
        val data = web(rfcSecret)
        val clipboard = Clipboard("synthetic-only")
        try {
            val totp = data.quickField(QuickField.TOTP)!!
            assertSame(data.totp, totp)
            assertTrue(EntryQuickActions.available(totp, QuickField.TOTP))
            ClipboardGuard(clipboard).use { guard ->
                assertEquals(1, EntryQuickActions.copyTotp(totp, at59, guard::copy))
                assertEquals("287082", clipboard.getContents(null)!!.getTransferData(DataFlavor.stringFlavor))
                guard.clear()
                assertEquals("", clipboard.getContents(null)!!.getTransferData(DataFlavor.stringFlavor))
            }
            assertEquals(rfcSecret, totp.value.useChars(::String), "stored secret unchanged")
            UiText.select(AppLanguage.ENGLISH)
            val copied = mutableListOf<String>()
            assertEquals(UiText.text("list.totpCopied", 30L),
                EntryQuickActions.copyTotpNotice(data, Instant.ofEpochSecond(60)) { copied += it })
            assertEquals(listOf("359152"), copied, "RFC 4226 counter 2")
        } finally { close(data) }
    }

    @Test fun `entries without a usable secret offer no TOTP action and copy nothing`() {
        val copied = mutableListOf<String>()
        UiText.select(AppLanguage.ENGLISH)
        listOf(web(null), web(""), blankData(EntryType.SERVER), blankData(EntryType.CUSTOM)).forEach { data ->
            try {
                data.quickField(QuickField.TOTP)?.let { assertFalse(EntryQuickActions.available(it, QuickField.TOTP)) }
                assertEquals(UiText.text("list.noQuickField"), EntryQuickActions.copyTotpNotice(data, at59) { copied += it })
            } finally { close(data) }
        }
        val invalid = web("not a secret")
        try {
            assertFalse(EntryQuickActions.available(invalid.totp!!, QuickField.TOTP))
            assertTrue(EntryQuickActions.available(invalid.totp!!), "the stored value itself is present")
            assertEquals(UiText.text("totp.invalid"), EntryQuickActions.copyTotpNotice(invalid, at59) { copied += it })
        } finally { close(invalid) }
        assertTrue(copied.isEmpty())
    }

    @Test fun `the shown code is kept within its period and replaced at the boundary`() {
        val totp = field(rfcSecret)
        try {
            val first = nextTotpDisplay(totp, null, at59)!!
            assertEquals("287082", first.code)
            assertEquals(Instant.ofEpochSecond(60), first.validUntil)
            assertEquals(1, first.remaining)
            assertEquals(30, first.period)
            val early = nextTotpDisplay(totp, null, Instant.ofEpochSecond(30, 200_000_000))!!
            assertEquals(30, early.remaining)
            assertEquals(29, nextTotpDisplay(totp, early, Instant.ofEpochSecond(31, 500_000_000))!!.remaining)
            val next = nextTotpDisplay(totp, first, Instant.ofEpochSecond(60))!!
            assertEquals(Instant.ofEpochSecond(90), next.validUntil)
            assertEquals(30, next.remaining)
            assertNotEquals(first.code, next.code)
        } finally { totp.value.close() }
        assertNull(nextTotpDisplay(totp, null, at59), "an erased secret yields no code")
        field("otpauth://hotp/x?secret=$rfcSecret").let { hotp ->
            assertNull(nextTotpDisplay(hotp, null, at59))
            hotp.value.close()
        }
    }

    @Test fun `an untouched invalid stored secret warns without blocking the save`() {
        val stored = web("imported, not base32")
        try {
            val initialValues = stored.fields().map { it.value.useChars(::String) }
            val slot = stored.totpSlot(stored, initialValues)!!
            assertEquals(TotpSlot(3, "imported, not base32"), slot)
            val untouched = validateEditor("Title", "", "", "", initialValues, emptyMap(), slot)
            assertTrue(untouched.valid, "unchanged value keeps the entry editable")
            assertTrue(untouched.values.isEmpty())
            assertEquals(InputError(InputProblem.INVALID_TOTP), untouched.warnings[3])
            assertEquals(InputError(InputProblem.INVALID_TOTP), untouched.valueMessage(3))
            val changed = validateEditor("Title", "", "", "", initialValues.dropLast(1) + "still not base32", emptyMap(), slot)
            assertFalse(changed.valid, "a changed invalid value blocks the save")
            assertEquals(InputError(InputProblem.INVALID_TOTP), changed.values[3])
            assertTrue(changed.warnings.isEmpty())
            val emptied = validateEditor("Title", "", "", "", initialValues.dropLast(1) + "", emptyMap(), slot)
            assertTrue(emptied.valid)
            assertNull(emptied.valueMessage(3))
            val fixed = validateEditor("Title", "", "", "", initialValues.dropLast(1) + rfcSecret, emptyMap(), slot)
            assertTrue(fixed.valid)
            assertNull(fixed.valueMessage(3))
            // The detail view and quick action still report the stored value as invalid.
            assertFalse(EntryQuickActions.available(stored.totp!!, QuickField.TOTP))
            UiText.select(AppLanguage.ENGLISH)
            assertEquals(UiText.text("totp.invalid"), EntryQuickActions.copyTotpNotice(stored, at59) { fail("copied") })
        } finally { close(stored) }
    }

    @Test fun `the code position is masked like every other shown value`() {
        val key = RevealKey("vault", "a", "2026-01-01T00:00:00Z")
        val shown = RevealState().toggle(key, RevealState.TOTP_CODE)
        assertTrue(shown.shows(key, RevealState.TOTP_CODE))
        assertFalse(shown.shows(key, RevealState.NOTES))
        assertFalse(shown.cleared().shows(key, RevealState.TOTP_CODE))
        assertFalse(shown.follow(key.copy(modifiedAt = "2026-01-02T00:00:00Z")).shows(key, RevealState.TOTP_CODE))
        assertFalse(shown.follow(key.copy(entryId = "b")).shows(key.copy(entryId = "b"), RevealState.TOTP_CODE))
        assertFalse(shown.toggle(key, RevealState.TOTP_CODE).shows(key, RevealState.TOTP_CODE))
    }

    @Test fun `the editor validates only the TOTP field with the core parser`() {
        assertNull(totpError(""))
        assertNull(totpError("   "))
        assertNull(totpError(rfcSecret.lowercase().chunked(4).joinToString(" ")))
        assertNull(totpError("otpauth://totp/Example:alice?secret=$rfcSecret&issuer=Example&digits=8&period=60"))
        listOf("not a secret", "otpauth://totp/x?secret=$rfcSecret&digits=10", "otpauth://hotp/x?secret=$rfcSecret",
            "otpauth://totp/x?secret=$rfcSecret&period=5", "GEZDGNBV").forEach {
            assertEquals(InputError(InputProblem.INVALID_TOTP), totpError(it), it)
        }
        val data = web("")
        try {
            assertEquals(3, data.totpIndex())
            assertNull(data.copy(totp = null).totpIndex())
            val values = listOf("https://example.invalid", "not a secret", "not a secret", "not a secret")
            val slot = data.totpSlot(data.copy(totp = null), values.dropLast(1))!!
            assertEquals(TotpSlot(3, null), slot, "a TOTP field added in this edit has no stored value")
            val validation = validateEditor("Title", "", "", "", values, emptyMap(), slot)
            assertEquals(setOf(3), validation.values.keys, "username and password stay free text")
            assertFalse(validation.valid)
            assertTrue(validateEditor("Title", "", "", "", values.dropLast(1) + rfcSecret, emptyMap(), slot).valid)
            assertTrue(validateEditor("Title", "", "", "", values, emptyMap()).valid, "no TOTP field, no TOTP rule")
        } finally { close(data) }
        blankData(EntryType.PANEL).let { panel -> assertNull(panel.totpIndex()); close(panel) }
        listOf(AppLanguage.GERMAN, AppLanguage.ENGLISH).forEach { language ->
            UiText.select(language)
            assertTrue(InputError(InputProblem.INVALID_TOTP).message().isNotBlank())
            listOf("editor.totpHint", "totp.code", "totp.invalid", "totp.failed", "list.copyTotp", "shortcuts.copyTotp")
                .forEach { assertTrue(UiText.text(it).isNotBlank(), it) }
            assertTrue(UiText.text("totp.remaining", 12L).contains("12"))
        }
    }
}
