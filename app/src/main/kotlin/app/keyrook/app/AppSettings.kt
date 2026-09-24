// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.backup.BackupPolicy
import app.keyrook.core.settings.BackupSettingsDocument
import app.keyrook.core.settings.SettingsCodec
import app.keyrook.core.settings.SettingsDocument
import java.nio.file.*
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

internal enum class ThemeMode { SYSTEM, LIGHT, DARK }

internal val LOCK_MINUTE_CHOICES = listOf(1, 2, 5, 10, 15, 30)
internal val CLIPBOARD_SECOND_CHOICES = listOf(5L, 10L, 20L, 30L, 60L, 120L)

internal data class StoredBackup(val folder: Path, val policy: BackupPolicy, val enabled: Boolean)

/** Non-secret preferences only. Paths are metadata; passwords, key material and vault content never enter here. */
internal data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val inactivityMinutes: Int = 5,
    val clipboardSeconds: Long = 20,
    val lastVaultPath: Path? = null,
    val lastKeyFilePath: Path? = null,
    val backups: Map<String, StoredBackup> = emptyMap(),
) {
    fun backupFor(vault: Path): StoredBackup? = backups[vaultKey(vault)]

    /** The most recently configured vaults win when the bounded map is full. */
    fun withBackup(vault: Path, backup: StoredBackup): AppSettings {
        val key = vaultKey(vault)
        return copy(backups = ((backups - key).entries.map { it.toPair() } + (key to backup)).takeLast(MAX_BACKUP_VAULTS).toMap())
    }

    fun toDocument() = SettingsDocument(
        theme = theme.name, inactivityMinutes = inactivityMinutes, clipboardSeconds = clipboardSeconds,
        lastVaultPath = lastVaultPath?.toString(), lastKeyFilePath = lastKeyFilePath?.toString(),
        backups = backups.mapValues { (_, value) ->
            BackupSettingsDocument(value.folder.toString(), value.policy.latest, value.policy.daily, value.enabled)
        },
    )

    companion object {
        const val MAX_BACKUP_VAULTS = 64
        private const val MAX_PATH_LENGTH = 4096

        /** Every value is checked against the UI's choices; anything else falls back to its default. */
        fun fromDocument(document: SettingsDocument): AppSettings {
            val defaults = AppSettings()
            val backups = document.backups.entries.mapNotNull { (key, value) ->
                val vault = storedPath(key)?.takeIf { vaultKey(it) == key } ?: return@mapNotNull null
                val folder = storedPath(value.folder) ?: return@mapNotNull null
                val policy = try { BackupPolicy(value.latest, value.daily) } catch (_: IllegalArgumentException) { return@mapNotNull null }
                vaultKey(vault) to StoredBackup(folder, policy, value.enabled)
            }.takeLast(MAX_BACKUP_VAULTS).toMap()
            return AppSettings(
                theme = ThemeMode.entries.find { it.name == document.theme } ?: defaults.theme,
                inactivityMinutes = document.inactivityMinutes?.takeIf { it in LOCK_MINUTE_CHOICES } ?: defaults.inactivityMinutes,
                clipboardSeconds = document.clipboardSeconds?.takeIf { it in CLIPBOARD_SECOND_CHOICES } ?: defaults.clipboardSeconds,
                lastVaultPath = storedPath(document.lastVaultPath),
                lastKeyFilePath = storedPath(document.lastKeyFilePath),
                backups = backups,
            )
        }

        private fun storedPath(value: String?): Path? {
            if (value == null || value.isEmpty() || value.length > MAX_PATH_LENGTH || '\u0000' in value) return null
            val path = try { Path.of(value) } catch (_: InvalidPathException) { return null }
            return path.takeIf { it.isAbsolute }?.normalize()
        }
    }
}

internal fun vaultKey(path: Path): String = path.toAbsolutePath().normalize().toString()

/** Records only where the vault and optional key file live, never what they contain. */
internal fun rememberUnlockedPaths(settings: SettingsStore, vault: Path, keyFile: Path?): Boolean = settings.update {
    it.copy(lastVaultPath = vault.toAbsolutePath().normalize(), lastKeyFilePath = keyFile?.toAbsolutePath()?.normalize())
}

/**
 * Plaintext settings file with owner-only permissions, replaced atomically. A null directory keeps
 * preferences in memory only. Unreadable files are ignored without inspecting or reporting their content.
 */
internal class SettingsStore(private val directory: Path?) {
    private var current = load()

    @Synchronized fun current(): AppSettings = current

    /** Applies the change in memory; returns false when it could not be written to disk. */
    @Synchronized fun update(change: (AppSettings) -> AppSettings): Boolean {
        val next = AppSettings.fromDocument(change(current).toDocument())
        if (next == current) return true
        current = next
        return try { write(next); true } catch (_: Exception) { false }
    }

    private fun load(): AppSettings {
        val file = directory?.resolve(FILE_NAME) ?: return AppSettings()
        return try {
            val bytes = readTransfer(file, SettingsCodec.MAX_BYTES)
            SettingsCodec.decode(bytes)?.let(AppSettings::fromDocument) ?: AppSettings()
        } catch (_: Exception) { AppSettings() }
    }

    private fun write(settings: AppSettings) {
        val folder = directory ?: return
        val posix = Files.getFileAttributeView(folder, PosixFileAttributeView::class.java) != null
        if (!Files.isDirectory(folder)) {
            if (posix) Files.createDirectories(folder, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            else Files.createDirectories(folder)
        }
        val temporary = folder.resolve("$FILE_NAME.${UUID.randomUUID()}.tmp")
        try {
            writePrivateFile(temporary, SettingsCodec.encode(settings.toDocument()))
            Files.move(temporary, folder.resolve(FILE_NAME), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: Exception) {
            try { Files.deleteIfExists(temporary) } catch (deleteFailure: Exception) { failure.addSuppressed(deleteFailure) }
            throw failure
        }
    }

    companion object {
        const val FILE_NAME = "settings.json"

        fun platform(): SettingsStore = SettingsStore(
            platformSettingsDirectory(System.getProperty("os.name").orEmpty(), System::getenv, System.getProperty("user.home")))

        fun platformSettingsDirectory(osName: String, environment: (String) -> String?, home: String?): Path? {
            fun absolute(value: String?): Path? = value?.takeIf { it.isNotBlank() }
                ?.let { try { Path.of(it) } catch (_: InvalidPathException) { null } }?.takeIf { it.isAbsolute }
            val os = osName.lowercase()
            return when {
                os.startsWith("windows") -> absolute(environment("APPDATA"))?.resolve("Keyrook")
                os.startsWith("mac") -> absolute(home)?.resolve("Library")?.resolve("Application Support")?.resolve("Keyrook")
                else -> (absolute(environment("XDG_CONFIG_HOME")) ?: absolute(home)?.resolve(".config"))?.resolve("keyrook")
            }
        }
    }
}
