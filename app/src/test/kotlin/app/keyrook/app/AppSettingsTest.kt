// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.backup.BackupPolicy
import app.keyrook.core.crypto.KdfParameters
import app.keyrook.core.model.Vault
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64

class AppSettingsTest {
    @TempDir lateinit var directory: Path
    private val kdf = KdfParameters(iterations = 1)

    @Test fun `platform directories follow operating system conventions`() {
        val root = directory.toRealPath()
        val home = root.resolve("home").toString()
        val env = mapOf("APPDATA" to root.resolve("roaming").toString(), "XDG_CONFIG_HOME" to root.resolve("xdg").toString())
        assertEquals(root.resolve("roaming").resolve("Keyrook"), SettingsStore.platformSettingsDirectory("Windows 11", env::get, home))
        assertNull(SettingsStore.platformSettingsDirectory("Windows 11", { null }, home))
        assertEquals(Path.of(home, "Library", "Application Support", "Keyrook"),
            SettingsStore.platformSettingsDirectory("Mac OS X", env::get, home))
        assertEquals(root.resolve("xdg").resolve("keyrook"), SettingsStore.platformSettingsDirectory("Linux", env::get, home))
        assertEquals(Path.of(home, ".config", "keyrook"),
            SettingsStore.platformSettingsDirectory("Linux", mapOf("XDG_CONFIG_HOME" to "relative")::get, home))
        assertNull(SettingsStore.platformSettingsDirectory("Linux", { null }, null))
    }

    @Test fun `settings survive a restart and are written privately and atomically`() {
        val root = directory.toRealPath()
        val config = root.resolve("config").resolve("keyrook")
        val vault = root.resolve("a.keyrook")
        val folder = Files.createDirectory(root.resolve("backups"))
        val store = SettingsStore(config)
        assertEquals(AppSettings(), store.current())
        assertFalse(Files.exists(config))
        assertTrue(store.update { it.copy(theme = ThemeMode.DARK, inactivityMinutes = 15, clipboardSeconds = 60) })
        assertTrue(rememberUnlockedPath(store, vault))
        assertTrue(store.update { it.withBackup(vault, StoredBackup(folder, BackupPolicy(3, 4), true)) })
        val reloaded = SettingsStore(config).current()
        assertEquals(store.current(), reloaded)
        assertEquals(ThemeMode.DARK, reloaded.theme)
        assertEquals(vault, reloaded.lastVaultPath)
        assertEquals(StoredBackup(folder, BackupPolicy(3, 4), true), reloaded.backupFor(root.resolve("x").resolve("..").resolve("a.keyrook")))
        Files.list(config).use { files -> assertEquals(listOf(SettingsStore.FILE_NAME), files.map { it.fileName.toString() }.toList()) }
        val file = config.resolve(SettingsStore.FILE_NAME)
        if (Files.getFileAttributeView(file, PosixFileAttributeView::class.java) != null) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(config)))
        }
        assertTrue(Files.readString(file).contains("\"version\": 1"))
        val memory = SettingsStore(null)
        assertTrue(memory.update { it.copy(theme = ThemeMode.LIGHT) })
        assertEquals(ThemeMode.LIGHT, memory.current().theme)
    }

    @Test fun `language survives a restart and unknown languages fall back to the system choice`() {
        val config = directory.toRealPath().resolve("config")
        val store = SettingsStore(config)
        assertEquals(AppLanguage.SYSTEM, store.current().language)
        assertTrue(store.update { it.copy(language = AppLanguage.ENGLISH, theme = ThemeMode.DARK) })
        assertTrue(Files.readString(config.resolve(SettingsStore.FILE_NAME)).contains("\"language\": \"ENGLISH\""))
        assertEquals(AppLanguage.ENGLISH, SettingsStore(config).current().language)
        assertEquals(ThemeMode.DARK, SettingsStore(config).current().theme)
        assertTrue(store.update { it.copy(language = AppLanguage.GERMAN) })
        assertEquals(AppLanguage.GERMAN, SettingsStore(config).current().language)
        Files.writeString(config.resolve(SettingsStore.FILE_NAME), """{"version":1,"theme":"DARK","language":"KLINGON"}""")
        assertEquals(AppSettings(theme = ThemeMode.DARK), SettingsStore(config).current())
    }

    @Test fun `settings files written before the language preference still load`() {
        val root = directory.toRealPath()
        val config = Files.createDirectory(root.resolve("config"))
        val vault = root.resolve("a.keyrook")
        val folder = Files.createDirectory(root.resolve("backups"))
        fun json(path: Path) = path.toString().replace("\\", "\\\\")
        Files.writeString(config.resolve(SettingsStore.FILE_NAME), """{
            "version": 1,
            "theme": "DARK",
            "inactivityMinutes": 15,
            "clipboardSeconds": 60,
            "lastVaultPath": "${json(vault)}",
            "backups": {
                "${json(vault)}": {
                    "folder": "${json(folder)}",
                    "latest": 3,
                    "daily": 4,
                    "enabled": true
                }
            }
        }""")
        val loaded = SettingsStore(config).current()
        assertEquals(AppSettings(ThemeMode.DARK, 15, 60, vault, mapOf(vaultKey(vault) to StoredBackup(folder, BackupPolicy(3, 4), true))), loaded)
        assertEquals(AppLanguage.SYSTEM, loaded.language)
        assertEquals(WindowLockPolicy.MINIMIZE, loaded.windowLock)
    }

    @Test fun `window lock policy survives a restart and missing or unknown values fall back to minimize`() {
        val config = directory.toRealPath().resolve("config")
        val file = config.resolve(SettingsStore.FILE_NAME)
        val store = SettingsStore(config)
        assertEquals(WindowLockPolicy.MINIMIZE, store.current().windowLock)
        WindowLockPolicy.entries.forEach { policy ->
            assertTrue(store.update { it.copy(windowLock = policy, theme = ThemeMode.DARK) })
            assertTrue(Files.readString(file).contains("\"windowLock\": \"${policy.name}\""))
            assertEquals(policy, SettingsStore(config).current().windowLock)
        }
        Files.writeString(file, """{"version":1,"theme":"DARK","language":"ENGLISH","inactivityMinutes":10,"clipboardSeconds":30}""")
        assertEquals(AppSettings(theme = ThemeMode.DARK, inactivityMinutes = 10, clipboardSeconds = 30, language = AppLanguage.ENGLISH),
            SettingsStore(config).current())
        listOf("\"ALWAYS\"", "\"minimize\"", "\"\"", "null").forEach { value ->
            Files.writeString(file, """{"version":1,"theme":"DARK","windowLock":$value}""")
            val loaded = SettingsStore(config).current()
            assertEquals(WindowLockPolicy.MINIMIZE, loaded.windowLock, value)
            assertEquals(ThemeMode.DARK, loaded.theme, value)
        }
    }

    @Test fun `corrupt unknown and out of range files fall back to defaults`() {
        val config = Files.createDirectory(directory.toRealPath().resolve("config"))
        val file = config.resolve(SettingsStore.FILE_NAME)
        listOf("not json", "{\"version\":99,\"theme\":\"DARK\"}", "{\"theme\":\"DARK\",\"extra\":1}", "").forEach {
            Files.writeString(file, it)
            assertEquals(AppSettings(), SettingsStore(config).current(), it)
        }
        fun json(path: Path) = path.toString().replace("\\", "\\\\")
        val absolute = json(config.resolve("vault.keyrook"))
        val folder = json(config)
        val unnormalized = json(config.resolve("x")) + "/../vault.keyrook"
        Files.writeString(file, """{"version":1,"theme":"PURPLE","inactivityMinutes":7,"clipboardSeconds":3,
            "lastVaultPath":"relative.keyrook","backups":{
            "relative.keyrook":{"folder":"$folder","latest":5,"daily":5,"enabled":true},
            "$absolute":{"folder":"$folder","latest":0,"daily":5,"enabled":true},
            "$unnormalized":{"folder":"$folder","latest":5,"daily":5,"enabled":true}}}""")
        assertNotNull(app.keyrook.core.settings.SettingsCodec.decode(Files.readAllBytes(file)))
        assertEquals(AppSettings(), SettingsStore(config).current())
        Files.writeString(file, """{"version":1,"theme":"LIGHT","inactivityMinutes":30,"clipboardSeconds":120,
            "backups":{"$absolute":{"folder":"relative","latest":5,"daily":5,"enabled":true}}}""")
        assertEquals(AppSettings(ThemeMode.LIGHT, 30, 120), SettingsStore(config).current())
        Files.delete(file)
        val target = Files.writeString(config.resolve("elsewhere.json"), """{"version":1,"theme":"DARK"}""")
        try { Files.createSymbolicLink(file, target) } catch (_: Exception) { return }
        assertEquals(AppSettings(), SettingsStore(config).current())
    }

    @Test fun `backup configuration is restored after lock and restart for the matching vault only`() {
        val root = directory.toRealPath()
        val config = root.resolve("config")
        val vault = root.resolve("a.keyrook")
        val other = root.resolve("b.keyrook")
        val folder = Files.createDirectory(root.resolve("backups"))
        val settings = SettingsStore(config)
        VaultController().use { controller ->
            controller.unlock(other, "synthetic other password".toCharArray(), null, true, kdf).close()
            controller.lock()
            controller.unlock(vault, "synthetic settings password".toCharArray(), null, true, kdf).close()
            assertTrue(applyBackupConfiguration(controller, BackupConfiguration(folder, BackupPolicy(2, 0)), settings))
            controller.lock()
        }
        val restarted = SettingsStore(config)
        VaultController().use { controller ->
            controller.unlock(other, "synthetic other password".toCharArray(), null, false).close()
            assertNull(restoreRememberedBackups(controller, restarted))
            assertFalse(controller.session.backupStatus().configured)
            controller.lock()
            controller.unlock(vault, "synthetic settings password".toCharArray(), null, false).close()
            assertEquals(BackupNotice(UiText.text("settings.backupRestored", folder.toString(), 2, 0), false),
                restoreRememberedBackups(controller, restarted))
            assertTrue(controller.session.backupStatus().configured)
            repeat(3) { controller.session.snapshot().use { controller.session.save(it) } }
            assertEquals(2L, Files.list(folder).use { files -> files.filter { it.fileName.toString().endsWith(".keyrook.bak") }.count() })
            assertTrue(disableBackups(controller, true, restarted))
            controller.lock()
            controller.unlock(vault, "synthetic settings password".toCharArray(), null, false).close()
            assertEquals(BackupNotice(UiText.text("settings.backupDisabledNotice", folder.toString(), 2), false),
                restoreRememberedBackups(controller, SettingsStore(config)))
            assertFalse(controller.session.backupStatus().configured)
            assertEquals(StoredBackup(folder, BackupPolicy(2, 0), false), SettingsStore(config).current().backupFor(vault))
        }
    }

    @Test fun `missing remembered backup folder keeps the vault unlocked without backups`() {
        val root = directory.toRealPath()
        val vault = root.resolve("a.keyrook")
        val settings = SettingsStore(root.resolve("config"))
        settings.update { it.withBackup(vault, StoredBackup(root.resolve("missing"), BackupPolicy(), true)) }
        VaultController().use { controller ->
            controller.unlock(vault, "synthetic missing folder password".toCharArray(), null, true, kdf).close()
            assertEquals(BackupNotice(UiText.text("settings.backupRestoreFailed"), true), restoreRememberedBackups(controller, settings))
            assertFalse(controller.session.backupStatus().configured)
            controller.session.snapshot().use { assertEquals(0, it.entries.size) }
        }
    }

    @Test fun `settings file never contains passwords key material or vault content`() {
        val root = directory.toRealPath()
        val config = root.resolve("config")
        val vault = root.resolve("vault.keyrook")
        val key = root.resolve("KeyPath-SENTINEL-9973.key")
        val keyText = "KEYFILE-SENTINEL-0123456789ABCDE"
        Files.write(key, keyText.toByteArray(Charsets.US_ASCII))
        val folder = Files.createDirectory(root.resolve("backups"))
        val password = "Password-SENTINEL-4711"
        val sentinels = listOf(password, keyText, Base64.getEncoder().encodeToString(keyText.toByteArray()),
            keyText.toByteArray().joinToString("") { "%02x".format(it) }, "Customer-SENTINEL-3141", "Project-SENTINEL-2718",
            "Title-SENTINEL-1618", "Secret-SENTINEL-1414", "Notes-SENTINEL-1732", "Tag-SENTINEL-2236", "KeyPath-SENTINEL-9973")
        val settings = SettingsStore(config)
        VaultController().use { controller ->
            controller.unlock(vault, password.toCharArray(), key, true, kdf).close()
            rememberUnlockedPath(settings, vault)
            assertNull(restoreRememberedBackups(controller, settings))
            controller.addCustomer("Customer-SENTINEL-3141").use { snapshot ->
                controller.addProject("Project-SENTINEL-2718", snapshot.customers.single().id).close()
            }
            val data = blankData(EntryType.WEB)
            val entry = editedEntry(null, data, "Title-SENTINEL-1618", "Tag-SENTINEL-2236", "Notes-SENTINEL-1732", "",
                listOf("https://example.invalid", "user", "Secret-SENTINEL-1414", ""), listOf(false, false, true, true))
            data.fields().forEach { it.value.close() }
            Vault(entries = listOf(entry)).use { controller.save(entry).close() }
            assertTrue(applyBackupConfiguration(controller, BackupConfiguration(folder, BackupPolicy(4, 2)), settings))
            settings.update { it.copy(theme = ThemeMode.DARK, inactivityMinutes = 2, clipboardSeconds = 10) }
            controller.lock()
            controller.unlock(vault, password.toCharArray(), key, false).close()
            assertEquals(false, restoreRememberedBackups(controller, settings)?.warning)
            assertTrue(disableBackups(controller, true, settings))
            controller.lock()
        }
        val written = Files.readString(config.resolve(SettingsStore.FILE_NAME))
        sentinels.forEach { assertFalse(written.contains(it, ignoreCase = true), it) }
        assertTrue(written.contains(vault.toString().replace("\\", "\\\\")))
        assertEquals(vault, SettingsStore(config).current().lastVaultPath)
    }

    @Test fun `backup setting write failures are reported while backups stay active`() {
        val root = directory.toRealPath()
        val vault = root.resolve("a.keyrook")
        val folder = Files.createDirectory(root.resolve("backups"))
        val settings = SettingsStore(Files.writeString(root.resolve("not-a-directory"), "").resolve("keyrook"))
        var failures = 0
        VaultController().use { controller ->
            controller.unlock(vault, "synthetic write failure password".toCharArray(), null, true, kdf).close()
            assertTrue(applyBackupConfiguration(controller, BackupConfiguration(folder, BackupPolicy(2, 0)), settings) { failures++ })
            assertEquals(1, failures)
            assertTrue(controller.session.backupStatus().configured)
            assertTrue(disableBackups(controller, true, settings) { failures++ })
            assertEquals(2, failures)
            assertFalse(controller.session.backupStatus().configured)
        }
    }
}
