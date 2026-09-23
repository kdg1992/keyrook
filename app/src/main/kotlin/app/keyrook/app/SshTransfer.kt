// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.ssh.SshKeyMaterial
import app.keyrook.core.ssh.SshKeyService
import java.nio.file.Path

internal fun exportPublicSshKey(path: Path, publicKey: String, active: () -> Boolean) {
    check(active())
    val bytes = (SshKeyService().authorizedKey(publicKey) + "\n").toByteArray(Charsets.US_ASCII)
    try {
        check(active())
        writePrivateNew(path, bytes)
    } finally { bytes.fill(0) }
}

/** Takes ownership of the result and replacement passphrase, also when insertion fails or is cancelled. */
internal fun deliverImportedSshKey(
    key: SshKeyMaterial?, passphrase: CharArray, active: () -> Boolean,
    insert: (SshKeyMaterial, String) -> Unit,
) {
    try { key?.use { if (active()) insert(it, String(passphrase)) } }
    finally { passphrase.fill('\u0000') }
}
