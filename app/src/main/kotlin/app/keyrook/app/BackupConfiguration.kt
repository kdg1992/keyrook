// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.backup.BackupPolicy
import app.keyrook.core.backup.BackupFolderState
import app.keyrook.core.backup.BackupService
import app.keyrook.core.backup.IntegrityCheckCancelledException
import app.keyrook.core.backup.IntegrityFileKind
import app.keyrook.core.backup.IntegrityReport
import app.keyrook.core.backup.IntegrityState
import app.keyrook.core.backup.countManagedBackups
import java.io.IOException
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
    requireUserFacing(!Files.isSymbolicLink(folder), "error.backupFolder")
    requireUserFacing(Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS), "error.backupFolder")
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
 * What unlocking does with the remembered backup configuration. The settings file is unauthenticated plaintext,
 * so a restored configuration is always shown, and one whose retention would delete existing backups is only
 * applied after explicit confirmation.
 */
internal sealed interface RememberedBackup {
    /** Nothing remembered, or disabled without backups of this vault in the remembered folder. */
    data object None : RememberedBackup
    /** Enabled, but the remembered folder cannot be listed; backups stay unconfigured. */
    data object Unavailable : RememberedBackup
    /** Apply and show the folder and retention. */
    data class Restore(val configuration: BackupConfiguration, val present: Int) : RememberedBackup
    /** Retention keeps fewer backups than exist: the next save would delete some. Apply only after confirmation. */
    data class Confirm(val configuration: BackupConfiguration, val present: Int) : RememberedBackup
    /** Stored as disabled although backups of this vault exist in the remembered folder. */
    data class Disabled(val folder: Path, val present: Int) : RememberedBackup
}

/** [present] is the number of this vault's managed backups in the remembered folder, or null when it cannot be listed. */
internal fun decideRememberedBackup(stored: StoredBackup?, present: Int?): RememberedBackup = when {
    stored == null -> RememberedBackup.None
    !stored.enabled -> if (present != null && present > 0) RememberedBackup.Disabled(stored.folder, present)
        else RememberedBackup.None
    present == null -> RememberedBackup.Unavailable
    present > stored.policy.maximumKept -> RememberedBackup.Confirm(BackupConfiguration(stored.folder, stored.policy), present)
    else -> RememberedBackup.Restore(BackupConfiguration(stored.folder, stored.policy), present)
}

/** Text shown after unlocking; [warning] marks that backups stay unconfigured for this session. */
internal data class BackupNotice(val text: String, val warning: Boolean)

/**
 * Reapplies the remembered backup configuration of the unlocked vault file with the same checks as a manual
 * selection and returns the notice to show, or null when nothing is remembered. [confirm] asks the user before a
 * retention that would delete existing backups is applied; declining leaves backups unconfigured for this session.
 * The vault stays unlocked in every case.
 */
internal fun restoreRememberedBackups(controller: VaultController, settings: SettingsStore,
                                      confirm: (String) -> Boolean = { false }): BackupNotice? {
    val vault = controller.vaultPath ?: return null
    val stored = settings.current().backupFor(vault) ?: return null
    ensureOperationCurrent()
    val present = try { countManagedBackups(stored.folder, controller.read { it.id }) }
        catch (_: IOException) { null } catch (_: SecurityException) { null }
    val failed = BackupNotice(UiText.text("settings.backupRestoreFailed"), warning = true)
    fun restore(configuration: BackupConfiguration): BackupNotice =
        try {
            applyBackupConfiguration(controller, configuration)
            BackupNotice(UiText.text("settings.backupRestored", displayPath(configuration.folder),
                configuration.policy.latest, configuration.policy.daily), warning = false)
        } catch (_: Exception) { failed }
    return when (val decision = decideRememberedBackup(stored, present)) {
        RememberedBackup.None -> null
        RememberedBackup.Unavailable -> failed
        is RememberedBackup.Restore -> restore(decision.configuration)
        is RememberedBackup.Confirm -> {
            val policy = decision.configuration.policy
            if (confirm(UiText.text("settings.backupRetentionConfirm", displayPath(decision.configuration.folder),
                    policy.latest, policy.daily, decision.present))) restore(decision.configuration)
            else BackupNotice(UiText.text("settings.backupRetentionDeclined"), warning = true)
        }
        is RememberedBackup.Disabled ->
            BackupNotice(UiText.text("settings.backupDisabledNotice", displayPath(decision.folder), decision.present), warning = false)
    }
}

/** Paths from the settings file are untrusted text; control characters could forge additional lines. */
private fun displayPath(path: Path): String = path.toString().map { if (it.isISOControl()) ' ' else it }.joinToString("")

internal fun createManualBackup(controller: VaultController): Int {
    ensureOperationCurrent()
    return controller.session.backupNow().removed
}

/**
 * Runs on the vault worker after an operation. Returns the non-blocking notice for the latest backup whose rotation
 * left old backups in place, or null; the backup and any save after it succeeded. The text never contains paths.
 */
internal fun rotationNotice(controller: VaultController): String? {
    val result = controller.session.takeIncompleteRotation() ?: return null
    return if (result.notRemoved > 0) UiText.text("backup.rotationIncomplete", result.notRemoved)
        else UiText.text("backup.rotationUnchecked")
}

internal fun backupStatusText(controller: VaultController): String {
    ensureOperationCurrent()
    val status = controller.session.backupStatus()
    if (!status.configured) return UiText.text("backup.notConfigured")
    val saved = status.lastRevision?.let { UiText.text("backup.lastRevision", it) }
        ?: UiText.text("backup.noCopy")
    return UiText.text("backup.active", saved)
}

/**
 * Runs on the vault worker; the check is read-only and the text never contains folder paths or exception messages.
 * Locking invalidates the captured operation, which stops the check before its next file so the queued lock can
 * erase the session promptly. An expired operation then fails like any other; other cancellations get a neutral text.
 */
internal fun integrityReportText(controller: VaultController): String {
    ensureOperationCurrent()
    val guard = capturedOperationGuard()
    val report = try {
        controller.session.checkIntegrity(cancelled = { runCatching(guard).isFailure })
    } catch (_: IntegrityCheckCancelledException) {
        ensureOperationCurrent()
        return UiText.text("integrity.cancelled")
    }
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
