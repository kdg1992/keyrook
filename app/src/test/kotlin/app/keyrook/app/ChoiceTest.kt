// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ChoiceTest {
    @Test fun `choice search keeps options containing every word ignoring case`() {
        val options = listOf("1" to "Müller GmbH", "2" to "Schmidt AG", "3" to "Müller & Söhne")
        assertEquals(options, filterChoices(options, "  "))
        assertEquals(listOf("1", "3"), filterChoices(options, "MÜLLER").map { it.first })
        assertEquals(listOf("3"), filterChoices(options, "söhne müller").map { it.first })
        assertTrue(filterChoices(options, "meier").isEmpty())
    }
}
