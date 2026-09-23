// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.keyrook.core.backup.BackupService
import app.keyrook.core.crypto.Credentials
import app.keyrook.core.crypto.Secret
import app.keyrook.core.format.VaultCodec
import app.keyrook.core.model.Vault
import app.keyrook.core.storage.VaultStore
import app.keyrook.core.transfer.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.*
import java.io.IOException
import javax.swing.*

@Composable
internal fun DataTools(controller: VaultController, busy: Boolean, operation: (() -> Vault?) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        TextButton(enabled = !busy, onClick = {
            operation {
                selectPath(directory = true)?.let { folder ->
                    val selection = askBackupConfiguration(folder)
                    if (applyBackupConfiguration(controller, selection)) {
                        val policy = selection!!.policy
                        inform(UiText.text("transfer.configured", policy.latest, policy.daily))
                    }
                }
                controller.session.snapshot()
            }
        }) { Text(UiText.text("transfer.folder")) }
        TextButton(enabled = !busy, onClick = { operation {
            inform(backupStatusText(controller))
            controller.session.snapshot()
        } }) { Text(UiText.text("transfer.status")) }
        TextButton(enabled = !busy, onClick = { operation {
            ensureOperationCurrent()
            if (!controller.session.backupStatus().configured) inform(backupStatusText(controller))
            else {
                val removed = createManualBackup(controller)
                inform(UiText.text("transfer.backedUp", removed))
            }
            controller.session.snapshot()
        } }) { Text(UiText.text("transfer.now")) }
        TextButton(enabled = !busy, onClick = { operation {
            ensureOperationCurrent()
            if (!controller.session.backupStatus().configured) inform(backupStatusText(controller))
            else if (disableBackups(controller, confirm(UiText.text("transfer.disableConfirm")))) {
                inform(UiText.text("transfer.disabled"))
            }
            controller.session.snapshot()
        } }) { Text(UiText.text("transfer.disable")) }
        TextButton(enabled = !busy, onClick = { operation { restoreBackup(); controller.session.snapshot() } }) { Text(UiText.text("transfer.restore")) }
        TextButton(enabled = !busy, onClick = { operation {
            selectPath(save = true)?.let { target ->
                askCredentials(UiText.text("transfer.exportPassword"), confirm = true)?.use { credentials ->
                    controller.session.snapshot().use {
                        ensureOperationCurrent()
                        VaultStore().save(target, it.copy(revision = 0), credentials)
                    }
                    inform(UiText.text("transfer.exported"))
                }
            }
            controller.session.snapshot()
        } }) { Text(UiText.text("transfer.exportEncrypted")) }
        TextButton(enabled = !busy, onClick = { operation { importData(controller); controller.session.snapshot() } }) { Text(UiText.text("transfer.import")) }
        TextButton(enabled = !busy, onClick = { operation { exportPlaintext(controller); controller.session.snapshot() } }) { Text(UiText.text("transfer.exportPlain")) }
        TextButton(enabled = !busy, onClick = { operation {
            askCredentials(UiText.text("credentials.replaceTitle"), confirm = true, replacing = true)?.use {
                if (confirm(UiText.text("credentials.replaceConfirm"))) {
                    ensureOperationCurrent()
                    controller.session.changePassword(it)
                }
            }
            controller.session.snapshot()
        } }) { Text(UiText.text("credentials.replaceTitle")) }
        TextButton(enabled = !busy, onClick = { operation {
            configureKdf(controller)
            controller.session.snapshot()
        } }) { Text(UiText.text("credentials.kdfTitle")) }
        TextButton(enabled = !busy, onClick = { operation {
            generateKeyFileDialog()
            controller.session.snapshot()
        } }) { Text(UiText.text("credentials.generateKey")) }
    }
}

private fun askBackupConfiguration(folder: Path): BackupConfiguration? {
    var latest = "30"
    var daily = "30"
    while (true) {
        val entered = onEdt {
            val versions = JTextField(latest, 8)
            val days = JTextField(daily, 8)
            val panel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(JLabel(UiText.text("transfer.folderPath", folder.toAbsolutePath().normalize())))
                add(JLabel(UiText.text("transfer.latest"))); add(versions)
                add(JLabel(UiText.text("transfer.daily"))); add(days)
                add(JLabel(UiText.text("transfer.combined")))
                add(JLabel(UiText.text("transfer.rotation")))
                add(JLabel(UiText.text("transfer.session")))
            }
            if (JOptionPane.showConfirmDialog(null, panel, UiText.text("transfer.retentionTitle"),
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION)
                versions.text to days.text else null
        } ?: return null
        latest = entered.first
        daily = entered.second
        val policy = parseBackupPolicy(latest, daily)
        if (policy != null) return BackupConfiguration(folder, policy)
        inform(UiText.text("transfer.retentionInvalid"))
    }
}

private fun restoreBackup() {
    val source = selectPath() ?: return
    askCredentials(UiText.text("transfer.backupPassword"))?.use { credentials ->
        val service = BackupService(source.toAbsolutePath().parent)
        val preview = service.preview(source, credentials)
        if (!confirm(UiText.text("transfer.preview", preview.entries, preview.revision, preview.modifiedAt))) return
        val target = selectPath(save = true) ?: return
        ensureOperationCurrent()
        service.restoreToNew(source, target, credentials, preview)
        inform(UiText.text("transfer.restored"))
    }
}

private fun importData(controller: VaultController) {
    val format = choose(UiText.text("transfer.importFormat"), arrayOf("Keyrook JSON", UiText.text("transfer.csvMapping"), "Bitwarden JSON", "KeePass XML")) ?: return
    val path = selectPath() ?: return
    val transfer = VaultTransfer()
    val bytes = readTransfer(path)
    val imported = try {
        when (format) {
            "Keyrook JSON" -> transfer.importJson(bytes)
            "Bitwarden JSON" -> transfer.importBitwarden(bytes)
            "KeePass XML" -> transfer.importKeePassXml(bytes)
            else -> {
                importMappedCsv(bytes, selectMapping = { columns -> onEdt { askCsvMapping(columns) } }) ?: return
            }
        }
    } finally { bytes.fill(0) }
    imported.use {
        if (!confirm(UiText.text("transfer.importConfirm", it.entries.size))) return
        controller.session.snapshot().use { current ->
            val candidate = current.copy(customers = current.customers + it.customers,
                projects = current.projects + it.projects, entries = current.entries + it.entries)
            candidate.validate()
            ensureOperationCurrent()
            controller.session.save(candidate)
        }
    }
}

private fun exportPlaintext(controller: VaultController) {
    if (!confirm(UiText.text("transfer.plainWarning"))) return
    val format = choose(UiText.text("transfer.plainFormat"), arrayOf("JSON", "CSV")) ?: return
    val target = selectPath(save = true) ?: return
    if (!confirm(UiText.text("transfer.plainConfirm"))) return
    controller.session.snapshot().use { vault ->
        val transfer = VaultTransfer()
        val consent = PlaintextConsent(true, true)
        val bytes = if (format == "JSON") transfer.exportJson(vault, consent) else transfer.exportCsv(vault, consent)
        try { writePrivateNew(target, bytes) } finally { bytes.fill(0) }
    }
    inform(UiText.text("transfer.plainDone"))
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
    var owned: ByteArray? = null
    try {
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            val size = input.size()
            require(size in 0..maximumBytes.toLong()) { "Import exceeds size limit" }
            val bytes = ByteArray(size.toInt()).also { owned = it }
            val extra = ByteBuffer.allocate(1)
            try {
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) if (operations.read(input, buffer) < 0) throw IOException("Import changed while reading")
                if (operations.read(input, extra) != -1) throw IOException("Import changed while reading")
                return bytes
            } finally { extra.array().fill(0) }
        }
    } catch (failure: Exception) { owned?.fill(0); throw failure }
}

/** Writes only a new user-selected file; permissions are restricted before any plaintext is written. */
internal fun writePrivateNew(path: Path, bytes: ByteArray, operations: TransferIo = FileTransferIo) {
    ensureOperationCurrent()
    val target = path.toAbsolutePath().normalize()
    require(target.fileName != null && bytes.size <= VaultCodec.MAX_FILE_BYTES)
    val posix = Files.getFileAttributeView(target.parent, PosixFileAttributeView::class.java) != null
    val attributes = if (posix)
        arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) else emptyArray()
    val file = FileChannel.open(target, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS), *attributes)
    try {
        file.use {
            val acl = Files.getFileAttributeView(target, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            if (acl != null) {
                acl.acl = listOf(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.owner)
                    .setPermissions(AclEntryPermission.entries.toSet()).build())
            } else if (!posix) throw IOException("Private file permissions are unavailable")
            val buffer = ByteBuffer.wrap(bytes)
            operations.write(file, buffer)
            operations.force(file)
        }
    } catch (failure: Exception) {
        try { Files.deleteIfExists(target) } catch (deleteFailure: Exception) { failure.addSuppressed(deleteFailure) }
        throw failure
    }
}

private fun askCredentials(title: String, confirm: Boolean = false, replacing: Boolean = false): Credentials? {
    check(!SwingUtilities.isEventDispatchThread()) { "Credential processing requires a worker thread" }
    var chars = charArrayOf()
    var repeated = charArrayOf()
    var keyBytes: ByteArray? = null
    try {
        val keyPath = onEdt {
            val password = JPasswordField(24)
            val repeat = JPasswordField(24)
            val key = JTextField(24)
            val panel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(JLabel(title)); add(password)
                if (confirm) { add(JLabel(UiText.text("credentials.repeatPassword"))); add(repeat) }
                add(JLabel(UiText.text("credentials.optionalKey"))); add(key)
                if (replacing) add(JLabel(UiText.text("credentials.replaceKeyHelp")))
                add(JButton(UiText.text("credentials.selectKey")).apply {
                    addActionListener {
                        chooseKeyFile(false)?.let { key.text = it.toString() }
                    }
                })
            }
            try {
                if (JOptionPane.showConfirmDialog(null, panel, "Keyrook", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return@onEdt null
                chars = password.password; repeated = repeat.password
                key.text
            } finally { password.text = ""; repeat.text = ""; key.text = "" }
        } ?: return null
        require(chars.isNotEmpty() && (!confirm || chars.contentEquals(repeated)))
        if (keyPath.isNotBlank()) {
            val material = readTransfer(Path.of(keyPath), 32)
            keyBytes = material
            require(material.size == 32)
        }
        return Secret(chars).use { Credentials(it, keyBytes) }
    } finally { chars.fill('\u0000'); repeated.fill('\u0000'); keyBytes?.fill(0) }
}

private fun selectPath(save: Boolean = false, directory: Boolean = false): Path? = onEdt {
    val picker = JFileChooser().apply { if (directory) fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
    val result = if (save) picker.showSaveDialog(null) else picker.showOpenDialog(null)
    if (result == JFileChooser.APPROVE_OPTION) picker.selectedFile.toPath() else null
}
private fun confirm(message: String): Boolean = onEdt {
    JOptionPane.showConfirmDialog(null, message, "Keyrook", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION
}
private fun inform(message: String) = onEdt { JOptionPane.showMessageDialog(null, message, "Keyrook", JOptionPane.INFORMATION_MESSAGE) }
private fun choose(title: String, values: Array<String>): String? = onEdt {
    JOptionPane.showInputDialog(null, title, "Keyrook", JOptionPane.QUESTION_MESSAGE, null, values, values[0]) as? String
}
private fun <T> onEdt(action: () -> T): T {
    val guard = capturedOperationGuard()
    val guarded = { guard(); action().also { guard() } }
    if (SwingUtilities.isEventDispatchThread()) return guarded()
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(guarded) }
    return result!!.getOrThrow()
}
