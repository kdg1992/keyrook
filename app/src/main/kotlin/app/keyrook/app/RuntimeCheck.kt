// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import app.keyrook.core.crypto.Credentials
import app.keyrook.core.crypto.KdfParameters
import app.keyrook.core.crypto.Secret
import app.keyrook.core.format.VaultCodec
import app.keyrook.core.model.Vault
import app.keyrook.core.ssh.SshKeyService
import app.keyrook.core.ssh.SshKeyType
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import javax.swing.SwingUtilities

internal const val RUNTIME_CHECK_SUCCESS = "Keyrook runtime check passed\n"

/** Explicit packaging diagnostic: synthetic in-memory data, no user vault, settings file or network access. */
internal fun runRuntimeCheck(args: Array<String>): Int {
    if (args.size != 2 || args[0] != "--self-test") return 2
    return try {
        val report = Path.of(args[1])
        require(!Files.exists(report))
        System.setProperty("java.awt.headless", "true")
        System.setProperty("skiko.renderApi", "SOFTWARE")
        verifyRuntime()
        // A fixed marker also works for GUI launchers without an attached console.
        Files.writeString(report, RUNTIME_CHECK_SUCCESS, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        0
    } catch (_: Throwable) {
        System.err.println("Keyrook runtime check failed")
        1
    }
}

@OptIn(ExperimentalComposeUiApi::class)
internal fun verifyRuntime() {
    Secret("synthetic runtime check password".toCharArray()).use { secret ->
        Credentials(secret).use { credentials ->
            Vault().use { original ->
                val codec = VaultCodec()
                val encrypted = codec.encrypt(original, credentials, KdfParameters(iterations = 1))
                try { codec.decrypt(encrypted, credentials).use { check(it.id == original.id) } }
                finally { encrypted.fill(0) }
            }
        }
        val ssh = SshKeyService()
        ssh.generate(SshKeyType.ED25519, secret).use { generated ->
            ssh.importKey(generated.privateKey, secret, secret).use { imported ->
                check(imported.publicKey == generated.publicKey)
            }
        }
    }
    SwingUtilities.invokeAndWait {
        val scene = ImageComposeScene(width = 1000, height = 800, content = { KeyrookApp(settings = remember { SettingsStore(null) }) })
        try {
            scene.render(0).close()
            scene.render(100_000_000).use { image ->
                check(image.width == 1000 && image.height == 800)
                image.encodeToData().use { check(it != null && it.size > 1000) }
            }
        } finally { scene.close() }
    }
}
