// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BrowserLinksTest {
    @Test fun `accepts explicit web destinations without resolving them`() {
        for (address in listOf("https://example.invalid/account?q=a%20b#section", "HTTP://localhost:8080/",
            "https://[2001:db8::1]:8443/", "https://xn--bcher-kva.example/")) {
            assertEquals(address, BrowserLinks.parse(address).toString())
        }
    }

    @Test fun `rejects schemes and ambiguous destinations unsuitable for browser opening`() {
        for (address in listOf("file:///etc/passwd", "javascript:alert(1)", "data:text/html,test", "ssh://example.invalid",
            "//example.invalid", "https:example.invalid", "https:///path", "https://example.invalid:0/",
            "https://example.invalid:65536/", "https://example.invalid\\@other.invalid/")) {
            assertThrows(Exception::class.java, { BrowserLinks.parse(address) }, address)
        }
    }

    @Test fun `rejects embedded credentials and control characters`() {
        for (address in listOf("https://user:synthetic-password@example.invalid/", "https://user@example.invalid/",
            "https://example.invalid/\n", "https://example.invalid/%0d%0aHeader:value", "https://example.invalid/%00",
            "https://example.invalid/%7F")) {
            assertThrows(Exception::class.java, { BrowserLinks.parse(address) }, address)
        }
    }
}
