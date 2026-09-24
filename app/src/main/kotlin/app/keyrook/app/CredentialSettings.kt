// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.backup.resolveWithoutFinalLink
import app.keyrook.core.crypto.KdfParameters
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.SecureRandom
import javax.swing.SwingUtilities

internal fun parseKdfParameters(memoryKiB: String, iterations: String, parallelism: String): KdfParameters? {
    val memory = memoryKiB.trim().toIntOrNull() ?: return null
    val rounds = iterations.trim().toIntOrNull() ?: return null
    val lanes = parallelism.trim().toIntOrNull() ?: return null
    return try { KdfParameters(memory, rounds, lanes).also { it.validate() } } catch (_: Exception) { null }
}

/** Only the selected new key file receives the random factor; the working buffer is always erased. */
internal fun generateKeyFile(target: Path) {
    ensureOperationCurrent()
    // Resolved like vault files: a linked parent directory is accepted, a link as the key file itself never.
    val path = resolveWithoutFinalLink(target)
    require(Files.isDirectory(path.parent, LinkOption.NOFOLLOW_LINKS))
    val bytes = ByteArray(32)
    try {
        SecureRandom().nextBytes(bytes)
        writePrivateNew(path, bytes)
    } finally { bytes.fill(0) }
}

internal fun applyKdfParameters(controller: VaultController, parameters: KdfParameters?, confirmed: Boolean): Boolean {
    if (parameters == null || !confirmed) return false
    ensureOperationCurrent()
    controller.session.changeKdf(parameters)
    return true
}

internal fun configureKdf(controller: VaultController, dialogs: Dialogs) {
    ensureOperationCurrent()
    val current = controller.session.kdfParameters()
    val title = UiText.text("credentials.kdfTitle")
    var memory = current.memoryKiB.toString()
    var rounds = current.iterations.toString()
    var lanes = current.parallelism.toString()
    while (true) {
        val entered = dialogs.ask(FieldsRequest(title, emptyList(),
            listOf(InputField(UiText.text("credentials.memory"), memory), InputField(UiText.text("credentials.iterations"), rounds),
                InputField(UiText.text("credentials.parallelism"), lanes)),
            listOf(UiText.text("credentials.kdfExplanation")))) ?: return
        memory = entered[0]; rounds = entered[1]; lanes = entered[2]
        val parameters = parseKdfParameters(memory, rounds, lanes)
        if (parameters == null) {
            dialogs.inform(UiText.text("credentials.kdfInvalid"), title)
            continue
        }
        val confirmed = dialogs.confirm(UiText.text("credentials.kdfConfirm"), title)
        if (applyKdfParameters(controller, parameters, confirmed)) dialogs.inform(UiText.text("credentials.kdfChanged"), title)
        return
    }
}

/** Runs on the vault worker: asks, lets the user pick a new file, creates the key file and reports it. */
internal fun generateKeyFileDialog(dialogs: Dialogs) {
    val target = chooseNewKeyFile(dialogs) ?: return
    generateKeyFile(target)
    dialogs.inform(UiText.text("credentials.keyCreated"), UiText.text("credentials.generateKey"))
}

/** Runs on the vault worker; the save dialog opens only after the user confirmed creating a key file. */
internal fun chooseNewKeyFile(dialogs: Dialogs): Path? {
    if (!dialogs.confirm(UiText.text("credentials.keyCreateConfirm"), UiText.text("credentials.generateKey"))) return null
    return credentialOnEdt { chooseNewFile(DialogFile.KEY, "keyrook.key") }
}

internal fun chooseKeyFile(): Path? = credentialOnEdt { chooseOpenFile(DialogFile.KEY) }

internal fun <T> credentialOnEdt(action: () -> T): T {
    val guard = capturedOperationGuard()
    val guarded = { guard(); action().also { guard() } }
    if (SwingUtilities.isEventDispatchThread()) return guarded()
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(guarded) }
    return result!!.getOrThrow()
}
