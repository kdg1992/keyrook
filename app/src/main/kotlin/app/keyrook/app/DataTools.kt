// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.backup.BackupService
import app.keyrook.core.crypto.Credentials
import app.keyrook.core.crypto.Secret
import app.keyrook.core.format.VaultCodec
import app.keyrook.core.model.Vault
import app.keyrook.core.storage.PrivateFiles
import app.keyrook.core.storage.VaultStore
import app.keyrook.core.transfer.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.io.IOException
import javax.swing.SwingUtilities

/** A backup, transfer or account action of the data menu; [run] starts its vault operation. */
internal class DataAction(private val labelKey: String, val run: () -> Unit) {
    val label: String get() = UiText.text(labelKey)
}

/** The data menu's actions in groups (backups, transfer, account); each starts one guarded vault operation. */
internal fun dataActions(controller: VaultController, settings: SettingsStore, dialogs: Dialogs,
                         operation: (() -> Vault?) -> Unit, settingsFailed: () -> Unit): List<List<DataAction>> = listOf(
    listOf(
        DataAction("transfer.folder") {
            operation {
                onEdt { chooseFolder() }?.let { folder ->
                    val selection = askBackupConfiguration(dialogs, folder)
                    if (applyBackupConfiguration(controller, selection, settings, settingsFailed)) {
                        val policy = selection!!.policy
                        dialogs.inform(UiText.text("transfer.configured", policy.latest, policy.daily))
                    }
                }
                controller.session.snapshot()
            }
        },
        DataAction("transfer.status") { operation {
            dialogs.inform(backupStatusText(controller))
            controller.session.snapshot()
        } },
        DataAction("transfer.now") { operation {
            ensureOperationCurrent()
            if (!controller.session.backupStatus().configured) dialogs.inform(backupStatusText(controller))
            else {
                val removed = createManualBackup(controller)
                dialogs.inform(UiText.text("transfer.backedUp", removed))
            }
            controller.session.snapshot()
        } },
        DataAction("transfer.disable") { operation {
            ensureOperationCurrent()
            if (!controller.session.backupStatus().configured) dialogs.inform(backupStatusText(controller))
            else if (disableBackups(controller, dialogs.confirm(UiText.text("transfer.disableConfirm")), settings, settingsFailed)) {
                dialogs.inform(UiText.text("transfer.disabled"))
            }
            controller.session.snapshot()
        } },
        DataAction("integrity.action") { operation {
            dialogs.report(UiText.text("integrity.title"), integrityReportText(controller))
            controller.session.snapshot()
        } },
        DataAction("transfer.restore") { operation { restoreBackup(dialogs); controller.session.snapshot() } },
    ),
    listOf(
        DataAction("transfer.exportEncrypted") { operation {
            onEdt { chooseNewFile(DialogFile.VAULT, "keyrook-export.keyrook") }?.let { target ->
                askCredentials(dialogs, UiText.text("transfer.exportPassword"), confirm = true)?.use { credentials ->
                    controller.session.snapshot().use {
                        ensureOperationCurrent()
                        VaultStore().save(target, it.independentCopy(), credentials)
                    }
                    dialogs.inform(UiText.text("transfer.exported"))
                }
            }
            controller.session.snapshot()
        } },
        DataAction("transfer.import") { operation { importData(controller, dialogs); controller.session.snapshot() } },
        DataAction("transfer.exportPlain") { operation { exportPlaintext(controller, dialogs); controller.session.snapshot() } },
    ),
    listOf(
        DataAction("credentials.replaceTitle") { operation {
            askCredentials(dialogs, UiText.text("credentials.replaceTitle"), confirm = true, replacing = true)?.use {
                if (dialogs.confirm(UiText.text("credentials.replaceConfirm"))) {
                    ensureOperationCurrent()
                    controller.session.changePassword(it)
                }
            }
            controller.session.snapshot()
        } },
        DataAction("credentials.kdfTitle") { operation {
            configureKdf(controller, dialogs)
            controller.session.snapshot()
        } },
        DataAction("credentials.generateKey") { operation {
            generateKeyFileDialog(dialogs)
            controller.session.snapshot()
        } },
    ),
)

private fun askBackupConfiguration(dialogs: Dialogs, folder: Path): BackupConfiguration? {
    var latest = "30"
    var daily = "30"
    while (true) {
        val entered = dialogs.ask(FieldsRequest(UiText.text("transfer.retentionTitle"),
            listOf(UiText.text("transfer.folderPath", folder.toAbsolutePath().normalize())),
            listOf(InputField(UiText.text("transfer.latest"), latest), InputField(UiText.text("transfer.daily"), daily)),
            listOf(UiText.text("transfer.combined"), UiText.text("transfer.rotation"), UiText.text("transfer.session")))) ?: return null
        latest = entered[0]
        daily = entered[1]
        val policy = parseBackupPolicy(latest, daily)
        if (policy != null) return BackupConfiguration(folder, policy)
        dialogs.inform(UiText.text("transfer.retentionInvalid"))
    }
}

private fun restoreBackup(dialogs: Dialogs) {
    val source = onEdt { chooseOpenFile(DialogFile.BACKUP) } ?: return
    askCredentials(dialogs, UiText.text("transfer.backupPassword"))?.use { credentials ->
        val service = BackupService(source.toAbsolutePath().parent)
        val preview = service.preview(source, credentials)
        if (!dialogs.confirm(UiText.text("transfer.preview", preview.entries, preview.revision, preview.modifiedAt))) return
        val target = onEdt { chooseNewFile(DialogFile.VAULT, "keyrook-restored.keyrook") } ?: return
        ensureOperationCurrent()
        service.restoreToNew(source, target, credentials, preview)
        dialogs.inform(UiText.text("transfer.restored"))
    }
}

/** Import choices are identified by type, never by their translated label. */
internal enum class ImportFormat(private val labelKey: String, val fileType: DialogFile) {
    KEYROOK_JSON("transfer.format.keyrookJson", DialogFile.JSON), MAPPED_CSV("transfer.csvMapping", DialogFile.CSV),
    KEEPASS_CSV("transfer.format.keepassCsv", DialogFile.CSV), BITWARDEN_JSON("transfer.format.bitwardenJson", DialogFile.JSON),
    KEEPASS_XML("transfer.format.keepassXml", DialogFile.XML);
    val label: String get() = UiText.text(labelKey)
}

internal enum class PlaintextFormat(private val labelKey: String, val fileType: DialogFile) {
    JSON("transfer.format.json", DialogFile.JSON), CSV("transfer.format.csv", DialogFile.CSV);
    val label: String get() = UiText.text(labelKey)
}

/**
 * Returns null when the CSV mapping is canceled. KeePass CSV files with other columns raise [KeePassCsvHeaderException].
 * The mapped CSV path erases [bytes] early; the caller still erases them afterwards on every path.
 */
internal fun importTransfer(format: ImportFormat, bytes: ByteArray, selectMapping: (List<String>) -> CsvMapping?,
                            guard: () -> Unit = capturedOperationGuard(), transfer: VaultTransfer = VaultTransfer()): Vault? =
    when (format) {
        ImportFormat.KEYROOK_JSON -> transfer.importJson(bytes)
        ImportFormat.BITWARDEN_JSON -> transfer.importBitwarden(bytes)
        ImportFormat.KEEPASS_XML -> transfer.importKeePassXml(bytes)
        ImportFormat.KEEPASS_CSV -> transfer.importKeePassCsv(bytes)
        ImportFormat.MAPPED_CSV -> importMappedCsv(bytes, guard, selectMapping)
    }

internal fun exportTransfer(format: PlaintextFormat, vault: Vault, consent: PlaintextConsent,
                            transfer: VaultTransfer = VaultTransfer()): ByteArray = when (format) {
    PlaintextFormat.JSON -> transfer.exportJson(vault, consent)
    PlaintextFormat.CSV -> transfer.exportCsv(vault, consent)
}

private fun importData(controller: VaultController, dialogs: Dialogs) {
    val format = dialogs.choose(UiText.text("transfer.importFormat"), ImportFormat.entries, ImportFormat::label) ?: return
    val path = onEdt { chooseOpenFile(format.fileType) } ?: return
    val bytes = readTransfer(path)
    val imported = try {
        importTransfer(format, bytes, selectMapping = { columns -> askCsvMapping(dialogs, columns) }) ?: return
    } catch (_: KeePassCsvHeaderException) {
        dialogs.inform(UiText.text("csv.keepassMismatch"))
        return
    } finally { bytes.fill(0) }
    imported.use {
        if (!dialogs.confirm(UiText.text("transfer.importConfirm", it.entries.size))) return
        controller.session.snapshot().use { current ->
            val candidate = current.copy(customers = current.customers + it.customers,
                projects = current.projects + it.projects, entries = current.entries + it.entries)
            candidate.validate()
            ensureOperationCurrent()
            controller.session.save(candidate)
        }
    }
}

/** Two separate confirmations: before choosing the format and again after choosing the new target file. */
private fun exportPlaintext(controller: VaultController, dialogs: Dialogs) {
    if (!dialogs.confirm(UiText.text("transfer.plainWarning"))) return
    val format = dialogs.choose(UiText.text("transfer.plainFormat"), PlaintextFormat.entries, PlaintextFormat::label) ?: return
    val target = onEdt { chooseNewFile(format.fileType, "keyrook-export.${format.fileType.extension}") } ?: return
    if (!dialogs.confirm(UiText.text("transfer.plainConfirm"))) return
    controller.session.snapshot().use { vault ->
        val consent = PlaintextConsent(true, true)
        val bytes = exportTransfer(format, vault, consent)
        try { writePrivateNew(target, bytes) } finally { bytes.fill(0) }
    }
    dialogs.inform(UiText.text("transfer.plainDone"))
}

internal interface TransferIo {
    fun read(channel: FileChannel, buffer: ByteBuffer): Int = channel.read(buffer)
    fun write(channel: FileChannel, buffer: ByteBuffer) { while (buffer.hasRemaining()) channel.write(buffer) }
    fun force(channel: FileChannel) = channel.force(true)
}
private object FileTransferIo : TransferIo

internal fun readTransfer(path: Path, maximumBytes: Int = VaultCodec.MAX_FILE_BYTES,
                          operations: TransferIo = FileTransferIo): ByteArray {
    require(maximumBytes in 0..VaultCodec.MAX_FILE_BYTES)
    require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
    return PrivateFiles.readBounded(path, 0..maximumBytes.toLong(),
        { IllegalArgumentException("Import exceeds size limit") }, { IOException("Import changed while reading") },
        operations::read)
}

/** Writes only a new user-selected file; permissions are restricted before any plaintext is written. */
internal fun writePrivateNew(path: Path, bytes: ByteArray, operations: TransferIo = FileTransferIo) {
    ensureOperationCurrent()
    writePrivateFile(path, bytes, operations)
}

/** Creates a new owner-only file without consulting the current vault operation. */
internal fun writePrivateFile(path: Path, bytes: ByteArray, operations: TransferIo = FileTransferIo) {
    val target = path.toAbsolutePath().normalize()
    require(target.fileName != null && bytes.size <= VaultCodec.MAX_FILE_BYTES)
    val file = PrivateFiles.createNew(target)
    try {
        file.use {
            val buffer = ByteBuffer.wrap(bytes)
            operations.write(file, buffer)
            operations.force(file)
        }
    } catch (failure: Exception) {
        try { Files.deleteIfExists(target) } catch (deleteFailure: Exception) { failure.addSuppressed(deleteFailure) }
        throw failure
    }
}

/**
 * Runs on the vault worker. The entered passwords arrive as owned char arrays and are erased on every path, as is
 * the key material; an empty password, a mismatched repetition or an invalid key file fails the operation.
 */
internal fun askCredentials(dialogs: Dialogs, title: String, confirm: Boolean = false, replacing: Boolean = false): Credentials? {
    check(!SwingUtilities.isEventDispatchThread()) { "Credential processing requires a worker thread" }
    val input = dialogs.ask(CredentialRequest(title, confirm, replacing)) ?: return null
    var keyBytes: ByteArray? = null
    try {
        require(input.password.isNotEmpty() && (!confirm || input.password.contentEquals(input.repeated)))
        if (input.keyPath.isNotBlank()) {
            val material = readTransfer(Path.of(input.keyPath), 32)
            keyBytes = material
            require(material.size == 32)
        }
        return Secret(input.password).use { Credentials(it, keyBytes) }
    } finally { input.close(); keyBytes?.fill(0) }
}

/** Runs file choosers on the event thread for a worker, checking the worker's session before and after. */
private fun <T> onEdt(action: () -> T): T {
    val guard = capturedOperationGuard()
    val guarded = { guard(); action().also { guard() } }
    if (SwingUtilities.isEventDispatchThread()) return guarded()
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(guarded) }
    return result!!.getOrThrow()
}
