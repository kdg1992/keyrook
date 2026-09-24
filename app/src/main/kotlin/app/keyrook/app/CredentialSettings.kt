// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.crypto.KdfParameters
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.SecureRandom
import javax.swing.*

internal fun parseKdfParameters(memoryKiB: String, iterations: String, parallelism: String): KdfParameters? {
    val memory = memoryKiB.trim().toIntOrNull() ?: return null
    val rounds = iterations.trim().toIntOrNull() ?: return null
    val lanes = parallelism.trim().toIntOrNull() ?: return null
    return try { KdfParameters(memory, rounds, lanes).also { it.validate() } } catch (_: Exception) { null }
}

/** Only the selected new key file receives the random factor; the working buffer is always erased. */
internal fun generateKeyFile(target: Path) {
    ensureOperationCurrent()
    val path = target.toAbsolutePath().normalize()
    var part = path.root
    for (component in path) {
        part = part.resolve(component)
        require(!Files.isSymbolicLink(part)) { "Key file path must not contain symbolic links" }
    }
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

internal fun configureKdf(controller: VaultController) {
    ensureOperationCurrent()
    val current = controller.session.kdfParameters()
    var memory = current.memoryKiB.toString()
    var rounds = current.iterations.toString()
    var lanes = current.parallelism.toString()
    while (true) {
        val entered = credentialOnEdt {
            val memoryField = JTextField(memory, 12)
            val roundsField = JTextField(rounds, 12)
            val lanesField = JTextField(lanes, 12)
            val panel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(JLabel(UiText.text("credentials.memory"))); add(memoryField)
                add(JLabel(UiText.text("credentials.iterations"))); add(roundsField)
                add(JLabel(UiText.text("credentials.parallelism"))); add(lanesField)
                add(JLabel(UiText.text("credentials.kdfExplanation")))
            }
            if (JOptionPane.showConfirmDialog(null, panel, UiText.text("credentials.kdfTitle"),
                    JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
                listOf(memoryField.text, roundsField.text, lanesField.text) else null
        } ?: return
        memory = entered[0]; rounds = entered[1]; lanes = entered[2]
        val parameters = parseKdfParameters(memory, rounds, lanes)
        if (parameters == null) {
            credentialOnEdt { JOptionPane.showMessageDialog(null, UiText.text("credentials.kdfInvalid")) }
            continue
        }
        val confirmed = credentialOnEdt {
            JOptionPane.showConfirmDialog(null, UiText.text("credentials.kdfConfirm"), UiText.text("credentials.kdfTitle"),
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION
        }
        if (applyKdfParameters(controller, parameters, confirmed)) credentialOnEdt {
            JOptionPane.showMessageDialog(null, UiText.text("credentials.kdfChanged"))
        }
        return
    }
}

internal fun generateKeyFileDialog() {
    val target = chooseKeyFile(true) ?: return
    generateKeyFile(target)
    credentialOnEdt { JOptionPane.showMessageDialog(null, UiText.text("credentials.keyCreated")) }
}

internal fun chooseKeyFile(save: Boolean): Path? {
    if (save && !credentialOnEdt {
        JOptionPane.showConfirmDialog(null, UiText.text("credentials.keyCreateConfirm"), UiText.text("credentials.generateKey"),
            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION
    }) return null
    return credentialOnEdt { if (save) chooseNewFile(DialogFile.KEY, "keyrook.key") else chooseOpenFile(DialogFile.KEY) }
}

internal fun <T> credentialOnEdt(action: () -> T): T {
    val guard = capturedOperationGuard()
    val guarded = { guard(); action().also { guard() } }
    if (SwingUtilities.isEventDispatchThread()) return guarded()
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(guarded) }
    return result!!.getOrThrow()
}
