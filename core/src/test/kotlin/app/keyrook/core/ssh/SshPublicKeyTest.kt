// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.ssh

import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.HexFormat

class SshPublicKeyTest {
    private val service = SshKeyService()
    private fun blob(): ByteArray = ByteArrayOutputStream().use { out ->
        DataOutputStream(out).apply {
            val type = "ssh-ed25519".toByteArray()
            writeInt(type.size); write(type)
            writeInt(32)
            write(HexFormat.of().parseHex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"))
        }
        out.toByteArray()
    }
    private fun encoded(bytes: ByteArray = blob()) = "ssh-ed25519 " + Base64.getEncoder().encodeToString(bytes)

    @Test fun `authorized key preserves a valid key and printable comment`() {
        assertEquals(encoded() + " test account", service.authorizedKey("  " + encoded() + "  test account  "))
        assertEquals(encoded(), service.authorizedKey(encoded()))
    }

    @Test fun `rejects invalid blobs trailing bytes mismatched types options and line injection`() {
        val invalid = listOf("ssh-ed25519 not-base64", encoded(blob() + byteArrayOf(0)),
            encoded(blob().copyOf(20)), encoded().replace("ssh-ed25519 ", "ssh-rsa "),
            "command=\"unsafe\" " + encoded(), encoded() + "\n" + encoded(),
            encoded() + "\u0000comment", encoded() + " " + "x".repeat(257), "x".repeat(2049))
        for (text in invalid) {
            val failure = assertThrows(IllegalArgumentException::class.java) { service.authorizedKey(text) }
            assertEquals("Invalid or unsupported SSH public key", failure.message)
            assertNull(failure.cause)
        }
    }

    @Test fun `only RSA4096 public keys are exported`() {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val unsupported = PublicKeyEntry.toString(generator.generateKeyPair().public)
        assertThrows(IllegalArgumentException::class.java) { service.authorizedKey(unsupported) }
        generator.initialize(4096)
        val supported = PublicKeyEntry.toString(generator.generateKeyPair().public)
        assertEquals(supported, service.authorizedKey(supported))
    }
}
