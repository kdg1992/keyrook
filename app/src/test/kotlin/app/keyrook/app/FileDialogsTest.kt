// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.Locale

class FileDialogsTest {
    @Test fun `missing extensions are appended and present ones kept`() {
        val folder = Path.of("exports")
        assertEquals(folder.resolve("vault.keyrook"), withExtension(folder.resolve("vault"), "keyrook"))
        assertEquals(folder.resolve("vault.keyrook"), withExtension(folder.resolve("vault.keyrook"), "keyrook"))
        assertEquals(folder.resolve("Vault.KEYROOK"), withExtension(folder.resolve("Vault.KEYROOK"), "keyrook"))
        assertEquals(folder.resolve("vault.keyrook"), withExtension(folder.resolve("vault."), "keyrook"))
        assertEquals(folder.resolve("data.json.csv"), withExtension(folder.resolve("data.json"), "csv"))
        assertEquals(Path.of("key.pub"), withExtension(Path.of("key"), "pub"))
    }

    @Test fun `multi part extensions require the whole suffix`() {
        assertEquals(Path.of("daily.keyrook.bak"), withExtension(Path.of("daily"), "keyrook.bak"))
        assertEquals(Path.of("daily.bak.keyrook.bak"), withExtension(Path.of("daily.bak"), "keyrook.bak"))
        assertEquals(Path.of("daily.keyrook.bak"), withExtension(Path.of("daily.keyrook.bak"), "keyrook.bak"))
        assertTrue(hasExtension("A.KeyRook.Bak", "keyrook.bak"))
        assertFalse(hasExtension("a.bak", "keyrook.bak"))
        assertFalse(hasExtension(".json", "json"))
        assertFalse(hasExtension("json", "json"))
    }

    @Test fun `all files filter keeps the typed name`() {
        assertEquals(Path.of("words.list"), saveTarget(Path.of("words.list"), DialogFile.WORD_LIST, typedFilterSelected = false))
        assertEquals(Path.of("words.list.txt"), saveTarget(Path.of("words.list"), DialogFile.WORD_LIST, typedFilterSelected = true))
        assertEquals(Path.of("export.csv"), saveTarget(Path.of("export"), PlaintextFormat.CSV.fileType, typedFilterSelected = true))
    }

    @Test fun `import and export formats select matching file types`() {
        assertEquals(DialogFile.JSON, ImportFormat.KEYROOK_JSON.fileType)
        assertEquals(DialogFile.JSON, ImportFormat.BITWARDEN_JSON.fileType)
        assertEquals(DialogFile.CSV, ImportFormat.MAPPED_CSV.fileType)
        assertEquals(DialogFile.CSV, ImportFormat.KEEPASS_CSV.fileType)
        assertEquals(DialogFile.XML, ImportFormat.KEEPASS_XML.fileType)
        assertEquals("json", PlaintextFormat.JSON.fileType.extension)
        assertEquals("csv", PlaintextFormat.CSV.fileType.extension)
        assertEquals("keyrook.bak", DialogFile.BACKUP.extension)
    }

    @Test fun `public key names follow the algorithm`() {
        assertEquals("id_ed25519.pub", suggestedPublicKeyName("ssh-ed25519 AAAAC3Nza comment"))
        assertEquals("id_rsa.pub", suggestedPublicKeyName("ssh-rsa AAAAB3Nza"))
        assertEquals("public-key.pub", suggestedPublicKeyName("ecdsa-sha2-nistp256 AAAA"))
        assertEquals("public-key.pub", suggestedPublicKeyName(""))
    }

    @Test fun `every file type has a labelled filter in both languages`() {
        listOf(Locale.GERMAN, Locale.ENGLISH).forEach { locale ->
            DialogFile.entries.forEach { type ->
                val key = when (type) {
                    DialogFile.VAULT -> "credentials.vaultFilter"
                    DialogFile.KEY -> "credentials.keyFilter"
                    DialogFile.BACKUP -> "files.backup"
                    DialogFile.JSON -> "files.json"
                    DialogFile.CSV -> "files.csv"
                    DialogFile.XML -> "files.xml"
                    DialogFile.PUBLIC_KEY -> "files.publicKey"
                    DialogFile.WORD_LIST -> "files.wordList"
                    DialogFile.HTML -> "files.html"
                    DialogFile.ICS -> "files.ics"
                }
                assertTrue(UiText.localized(locale, key).contains("*.${type.extension}"), "$locale $type")
            }
        }
        assertEquals("a.json existiert bereits. Keyrook überschreibt keine Dateien; bitte einen neuen Namen wählen.",
            UiText.localized(Locale.GERMAN, "files.exists", "a.json"))
    }
}
