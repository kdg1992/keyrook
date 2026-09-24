// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.crypto.Credentials
import app.keyrook.core.crypto.KdfParameters
import app.keyrook.core.crypto.Secret
import app.keyrook.core.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

class OrganizationManagementTest {
    @TempDir lateinit var directory: Path
    private val first = Customer(UUID.randomUUID().toString(), "Erster Kunde")
    private val second = Customer(UUID.randomUUID().toString(), "Zweiter Kunde")
    private val project = Project(UUID.randomUUID().toString(), "Projekt", first.id)
    private val time = "2026-01-01T00:00:00Z"
    // Notes are secrets with identity equality; comparisons of snapshots replace them with this one instance.
    private val noNotes = Secret(charArrayOf())
    private fun customers(vault: Vault) = vault.customers.map { it.copy(notes = noNotes) }
    private fun projects(vault: Vault) = vault.projects.map { it.copy(notes = noNotes) }
    private val firstPlain get() = first.copy(notes = noNotes)
    private val secondPlain get() = second.copy(notes = noNotes)
    private val projectPlain get() = project.copy(notes = noNotes)

    private fun entry(deleted: Boolean = false, customerId: String? = first.id, projectId: String? = project.id) = Entry(
        UUID.randomUUID().toString(), "Synthetischer Eintrag",
        EntryData.Custom(mapOf("Passwort" to Field(Secret("synthetic-secret".toCharArray())))),
        time, time, customerId = customerId, projectId = projectId, deletedAt = if (deleted) time else null,
        history = listOf(HistoryItem(time, EntryData.Custom(mapOf("Passwort" to Field(Secret("synthetic-old".toCharArray())))))),
    )

    private fun withVault(entries: List<Entry> = emptyList(), action: (VaultController) -> Unit) {
        Vault(customers = listOf(first, second), projects = listOf(project), entries = entries).use { vault ->
            VaultController().use { controller ->
                Credentials(Secret("synthetic-master-passphrase".toCharArray())).use { credentials ->
                    controller.session.create(directory.resolve("organization.keyrook"), vault, credentials, KdfParameters(iterations = 1))
                }
                action(controller)
            }
        }
    }

    @Test fun `moving a project updates active and trashed entries and persists secrets and history`() {
        val active = entry()
        val trashed = entry(true, null)
        val independent = entry(projectId = null)
        withVault(listOf(active, trashed, independent)) { controller ->
            controller.updateProject(project.id, " Neues Projekt ", second.id).use { result ->
                result.validate()
                assertEquals("Neues Projekt", result.projects.single().name)
                assertEquals(second.id, result.projects.single().customerId)
                assertTrue(result.entries.filter { it.projectId == project.id }.all { it.customerId == second.id })
                assertEquals(first.id, result.entries.single { it.id == independent.id }.customerId)
                assertEquals(time, result.entries.single { it.id == trashed.id }.deletedAt)
            }
            controller.lock()
            controller.unlock(directory.resolve("organization.keyrook"), "synthetic-master-passphrase".toCharArray(), null, false).use { result ->
                result.validate()
                assertEquals(second.id, result.entries.first().customerId)
                result.entries.forEach {
                    assertEquals("synthetic-secret", it.data.fields().single().value.useChars { chars -> String(chars) })
                    assertEquals("synthetic-old", it.history.single().data.fields().single().value.useChars { chars -> String(chars) })
                }
            }
        }
    }

    @Test fun `rename and removing project customer preserve entry assignments`() {
        withVault(listOf(entry())) { controller ->
            controller.renameCustomer(first.id, " Neuer Name ").use {
                assertEquals("Neuer Name", it.customers.first().name)
                assertEquals(first.id, it.entries.single().customerId)
            }
            controller.updateProject(project.id, "Ohne Projektkunde", null).use {
                it.validate()
                assertNull(it.projects.single().customerId)
                assertEquals(first.id, it.entries.single().customerId)
                assertEquals(time, it.entries.single().modifiedAt)
            }
        }
    }

    @Test fun `in use removal rejects even trash references and leaves document unchanged`() {
        withVault(listOf(entry(true))) { controller ->
            controller.session.snapshot().use { before ->
                assertThrows(IllegalArgumentException::class.java) { controller.removeCustomer(first.id, true) }
                assertThrows(IllegalArgumentException::class.java) { controller.removeProject(project.id, true) }
                controller.session.snapshot().use { after ->
                    assertEquals(before.revision, after.revision)
                    assertEquals(customers(before), customers(after))
                    assertEquals(projects(before), projects(after))
                    assertEquals(before.entries.single().id, after.entries.single().id)
                }
            }
        }
    }

    @Test fun `unused removal requires confirmation and cannot remove missing objects`() {
        withVault { controller ->
            assertThrows(IllegalArgumentException::class.java) { controller.removeCustomer(second.id, false) }
            assertThrows(IllegalArgumentException::class.java) { controller.removeProject(project.id, false) }
            controller.removeCustomer(second.id, true).use { assertEquals(listOf(firstPlain), customers(it)) }
            controller.removeProject(project.id, true).use { assertTrue(it.projects.isEmpty()) }
            controller.removeCustomer(first.id, true).use { assertTrue(it.customers.isEmpty()) }
            assertThrows(IllegalArgumentException::class.java) { controller.removeCustomer(first.id, true) }
            assertThrows(IllegalArgumentException::class.java) { controller.removeProject(project.id, true) }
        }
    }

    @Test fun `invalid edits fail before saving`() {
        withVault { controller ->
            assertThrows(IllegalArgumentException::class.java) { controller.renameCustomer(first.id, " ") }
            assertThrows(IllegalArgumentException::class.java) { controller.renameCustomer(first.id, "x".repeat(4097)) }
            assertThrows(IllegalArgumentException::class.java) { controller.renameCustomer(UUID.randomUUID().toString(), "Name") }
            assertThrows(IllegalArgumentException::class.java) { controller.updateProject(project.id, "Name", UUID.randomUUID().toString()) }
            assertThrows(IllegalArgumentException::class.java) { controller.updateProject(project.id, " ", first.id) }
            controller.session.snapshot().use {
                assertEquals(0L, it.revision)
                assertEquals(listOf(projectPlain), projects(it))
                assertEquals(listOf(firstPlain, secondPlain), customers(it))
            }
        }
    }
}
