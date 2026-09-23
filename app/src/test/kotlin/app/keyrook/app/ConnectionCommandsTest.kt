// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConnectionCommandsTest {
    @Test fun `builds quoted SSH and SFTP connection commands`() {
        assertEquals("ssh -p 2222 -l 'service-user' -- 'host.example.invalid'",
            ConnectionCommands.ssh("host.example.invalid", 2222, "service-user"))
        assertEquals("sftp -P 2200 -- 'user_1@192.0.2.10'",
            ConnectionCommands.sftp("192.0.2.10", 2200, "user_1"))
        assertEquals("ssh -p 22 -l 'root' -- 'localhost'", ConnectionCommands.ssh("localhost", 22, "root"))
    }

    @Test fun `accepts IPv6 literals without DNS or interface resolution`() {
        assertEquals("ssh -p 22 -l 'user' -- '2001:db8::1'", ConnectionCommands.ssh("[2001:db8::1]", 22, "user"))
        assertEquals("sftp -P 22 -- 'user@[2001:db8::1]'", ConnectionCommands.sftp("2001:db8::1", 22, "user"))
        assertEquals("sftp -P 22 -- 'user@[::ffff:192.0.2.1]'", ConnectionCommands.sftp("::ffff:192.0.2.1", 22, "user"))
    }

    @Test fun `rejects shell and option injection in either operand`() {
        val attacks = listOf("-oProxyCommand=anything", "host;anything", "host&anything", "host|anything",
            "host\nanything", "host\ranything", "host\u0000anything", "host'anything", "host\"anything",
            "host`anything`", "host\$(anything)", "host anything", "user@host", "host/path", "host\\path")
        attacks.forEach { attack ->
            assertThrows(IllegalArgumentException::class.java) { ConnectionCommands.ssh(attack, 22, "user") }
            assertThrows(IllegalArgumentException::class.java) { ConnectionCommands.sftp("example.invalid", 22, attack) }
        }
    }

    @Test fun `rejects ambiguous or malformed addresses and invalid ports`() {
        listOf("", "999.1.1.1", "01.2.3.4", "1.2.3", "1.2.3.4.5", ".example.invalid", "example..invalid",
            "-example.invalid", "example-.invalid", "[::1", "1:2:3", "fe80::1%eth0", "https://example.invalid").forEach { host ->
            assertThrows(IllegalArgumentException::class.java) { ConnectionCommands.ssh(host, 22, "user") }
        }
        listOf(0, -1, 65536).forEach { port ->
            assertThrows(IllegalArgumentException::class.java) { ConnectionCommands.sftp("example.invalid", port, "user") }
        }
        assertThrows(IllegalArgumentException::class.java) { ConnectionCommands.ssh("example.invalid", 22, "u".repeat(65)) }
    }
}
