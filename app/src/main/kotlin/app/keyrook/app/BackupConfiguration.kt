// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.backup.BackupPolicy
import app.keyrook.core.backup.BackupFolderState
import app.keyrook.core.backup.BackupService
import app.keyrook.core.backup.IntegrityFileKind
import app.keyrook.core.backup.IntegrityReport
import app.keyrook.core.backup.IntegrityState
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal data class BackupConfiguration(val folder: Path, val policy: BackupPolicy)

internal fun parseBackupPolicy(latest: String, daily: String): BackupPolicy? {
    val versions = latest.trim().toIntOrNull() ?: return null
    val days = daily.trim().toIntOrNull() ?: return null
    return try { BackupPolicy(versions, days) } catch (_: IllegalArgumentException) { null }
}

/**
 * A null selection represents cancellation and must not replace a previous configuration.
 * An accepted selection is remembered for the open vault file when settings are provided;
 * [settingsFailed] reports that it could not be written although backups are active.
 */
internal fun applyBackupConfiguration(controller: VaultController, selection: BackupConfiguration?,
                                      settings: SettingsStore? = null, settingsFailed: () -> Unit = {}): Boolean {
    if (selection == null) return false
    ensureOperationCurrent()
    val folder = selection.folder.toAbsolutePath().normalize()
    // Resolved like the vault file and the core backup service: linked parent directories are accepted,
    // a folder that is itself a symbolic link is refused.
    require(!Files.isSymbolicLink(folder)) { "Backup folder must not be a symbolic link" }
    require(Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) { "Backup folder must exist" }
    ensureOperationCurrent()
    controller.session.configureBackups(BackupService(folder, selection.policy))
    val vault = controller.vaultPath
    if (settings != null && vault != null &&
        !settings.update { it.withBackup(vault, StoredBackup(folder, selection.policy, true)) }) settingsFailed()
    return true
}

/** Keeps the remembered folder and retention but stops restoring them for this vault file. */
internal fun disableBackups(controller: VaultController, confirmed: Boolean, settings: SettingsStore? = null,
                            settingsFailed: () -> Unit = {}): Boolean {
    if (!confirmed) return false
    ensureOperationCurrent()
    controller.session.configureBackups(null)
    val vault = controller.vaultPath
    val stored = vault?.let { settings?.current()?.backupFor(it) }
    if (settings != null && vault != null && stored != null &&
        !settings.update { it.withBackup(vault, stored.copy(enabled = false)) }) settingsFailed()
    return true
}

/**
 * Reapplies the remembered backup configuration of the unlocked vault file with the same checks as a manual
 * selection. Returns false when a remembered folder is no longer acceptable; the vault stays unlocked.
 */
internal fun restoreRememberedBackups(controller: VaultController, settings: SettingsStore): Boolean {
    val vault = controller.vaultPath ?: return true
    val stored = settings.current().backupFor(vault)?.takeIf { it.enabled } ?: return true
    return try { applyBackupConfiguration(controller, BackupConfiguration(stored.folder, stored.policy)) }
    catch (_: Exception) { false }
}

internal fun createManualBackup(controller: VaultController): Int {
    ensureOperationCurrent()
    return controller.session.backupNow().removed
}

internal fun backupStatusText(controller: VaultController): String {
    ensureOperationCurrent()
    val status = controller.session.backupStatus()
    if (!status.configured) return UiText.text("backup.notConfigured")
    val saved = status.lastRevision?.let { UiText.text("backup.lastRevision", it) }
        ?: UiText.text("backup.noCopy")
    return UiText.text("backup.active", saved)
}

/** Runs on the vault worker; the check is read-only and the text never contains folder paths or exception messages. */
internal fun integrityReportText(controller: VaultController): String {
    ensureOperationCurrent()
    val report = controller.session.checkIntegrity()
    ensureOperationCurrent()
    return formatIntegrityReport(report)
}

internal fun formatIntegrityReport(report: IntegrityReport): String {
    val lines = mutableListOf(
        UiText.text("integrity.summary", report.count(IntegrityState.OK), report.files.size),
        UiText.text(if (report.intact) "integrity.intact" else "integrity.problems"),
    )
    when (report.backupFolder) {
        BackupFolderState.NOT_CONFIGURED -> { lines += UiText.text("integrity.notConfigured") }
        BackupFolderState.UNREADABLE -> { lines += UiText.text("integrity.folderUnreadable") }
        BackupFolderState.CHECKED -> {
            if (report.files.none { it.kind == IntegrityFileKind.BACKUP }) lines += UiText.text("integrity.noBackups")
        }
    }
    lines += ""
    for (file in report.files) {
        val state = when (file.state) {
            IntegrityState.OK -> UiText.text("integrity.ok", file.revision!!, file.entries!!, file.modifiedAt!!)
            IntegrityState.MISMATCH -> UiText.text("integrity.mismatch", file.revision!!)
            IntegrityState.AUTHENTICATION_FAILED -> UiText.text("integrity.authFailed")
            IntegrityState.CORRUPT -> UiText.text("integrity.corrupt")
            IntegrityState.UNREADABLE -> UiText.text("integrity.unreadable")
            IntegrityState.APPROVAL_REQUIRED -> UiText.text("integrity.approval")
        }
        val kind = UiText.text(if (file.kind == IntegrityFileKind.VAULT) "integrity.vault" else "integrity.backup")
        // File names are untrusted text; control characters could forge additional report lines.
        val name = file.fileName.map { if (it.isISOControl()) ' ' else it }.joinToString("")
        lines += UiText.text("integrity.line", kind, name, state)
    }
    if (report.files.any { it.state == IntegrityState.AUTHENTICATION_FAILED }) {
        lines += ""
        lines += UiText.text("integrity.authHint")
    }
    return lines.joinToString("\n")
}
