// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.backup.BackupPolicy
import app.keyrook.core.backup.BackupService
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal data class BackupConfiguration(val folder: Path, val policy: BackupPolicy)

internal fun parseBackupPolicy(latest: String, daily: String): BackupPolicy? {
    val versions = latest.trim().toIntOrNull() ?: return null
    val days = daily.trim().toIntOrNull() ?: return null
    return try { BackupPolicy(versions, days) } catch (_: IllegalArgumentException) { null }
}

/** A null selection represents cancellation and must not replace a previous configuration. */
internal fun applyBackupConfiguration(controller: VaultController, selection: BackupConfiguration?): Boolean {
    if (selection == null) return false
    ensureOperationCurrent()
    val folder = selection.folder.toAbsolutePath().normalize()
    var current = folder.root
    for (part in folder) {
        current = current.resolve(part)
        require(!Files.isSymbolicLink(current)) { "Backup folder must not contain symbolic links" }
    }
    require(Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) { "Backup folder must exist" }
    ensureOperationCurrent()
    controller.session.configureBackups(BackupService(folder, selection.policy))
    return true
}

internal fun disableBackups(controller: VaultController, confirmed: Boolean): Boolean {
    if (!confirmed) return false
    ensureOperationCurrent()
    controller.session.configureBackups(null)
    return true
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
