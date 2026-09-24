// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.crypto.AuthenticationException
import app.keyrook.core.crypto.Credentials
import app.keyrook.core.crypto.Secret
import app.keyrook.core.crypto.KdfParameters
import app.keyrook.core.model.Entry
import app.keyrook.core.model.Vault
import app.keyrook.core.model.tagEntries
import app.keyrook.core.model.trashEntries
import app.keyrook.core.service.VaultSession
import java.nio.file.Path

/** Called exclusively on one worker, except [read]. Returned snapshots belong to the presentation layer. */
class VaultController(internal val session: VaultSession = VaultSession(),
                      private val backoff: UnlockBackoff = UnlockBackoff()) : AutoCloseable {
    internal val sessionEpoch = SessionEpoch()
    /** Normalized path of the unlocked vault file; keys remembered per-vault settings. */
    internal var vaultPath: Path? = null
        private set
    /**
     * Read-only scans for background threads other than the worker: touches no controller state, only the
     * synchronized session, and hands [block] an erased-afterwards copy (see [VaultSession.read]). It may wait while
     * the worker holds the session, for example during an integrity check, so it must never run on the UI thread.
     */
    internal fun <R> read(block: (Vault) -> R): R = session.read(block)
    fun unlockDelayMillis(): Long = backoff.remainingMillis()
    fun unlock(path: Path, password: CharArray, keyFile: Path?, create: Boolean,
               parameters: KdfParameters = KdfParameters()): Vault {
        try {
            ensureOperationCurrent()
            backoff.requireReady()
            try {
                val key = keyFile?.let { file ->
                    readTransfer(file, 32).also { bytes ->
                        if (bytes.size != 32) { bytes.fill(0); error("Invalid key file") }
                    }
                }
                try {
                    vaultPath = null
                    Secret(password).use { secret ->
                        Credentials(secret, key).use { credentials ->
                            if (create) Vault().use { session.create(path, it, credentials, parameters) }
                            else session.open(path, credentials)
                        }
                    }
                } finally { key?.fill(0) }
                return session.snapshot().also {
                    backoff.succeeded()
                    vaultPath = path.toAbsolutePath().normalize()
                }
            } catch (failure: AuthenticationException) {
                // Only rejected credentials count; missing, unreadable, corrupt or busy files never delay a retry.
                backoff.failed()
                throw failure
            }
        } finally { password.fill('\u0000') }
    }

    fun save(entry: Entry): Vault {
        ensureOperationCurrent()
        session.snapshot().use { current ->
            val candidate = current.copy(entries = current.entries.filterNot { it.id == entry.id } + entry)
            candidate.validate()
            session.save(candidate)
        }
        return session.snapshot()
    }

    fun trash(id: String, restore: Boolean): Vault {
        ensureOperationCurrent()
        session.snapshot().use { current ->
            val now = java.time.Instant.now()
            session.save(current.copy(entries = current.entries.map {
                if (it.id != id) it else {
                    // A clock set back never moves the entry's change time before its last change or its history.
                    val stamp = maxOf(now, java.time.Instant.parse(it.modifiedAt)).toString()
                    it.copy(deletedAt = if (restore) null else stamp, modifiedAt = stamp)
                }
            }))
        }
        return session.snapshot()
    }

    /** Moves the entries [ids] to the trash, or restores them, as one save; on any failure nothing changes. */
    fun trashAll(ids: Set<String>, restore: Boolean): Vault = bulkChange { current ->
        current.trashEntries(ids, restore, java.time.Instant.now())
    }

    /**
     * Adds [tag] to, or removes it from, the entries [ids] as one save; on any failure nothing changes. When no entry
     * changes, nothing is saved.
     */
    fun tagAll(ids: Set<String>, tag: String, add: Boolean): Vault = bulkChange { current ->
        current.tagEntries(ids, tag.trim(), add, java.time.Instant.now())
    }

    /** [change] returns its argument unchanged to skip the save, otherwise a candidate saved as one revision. */
    private fun bulkChange(change: (Vault) -> Vault): Vault {
        ensureOperationCurrent()
        session.snapshot().use { current ->
            val candidate = change(current)
            if (candidate !== current) {
                candidate.validate()
                session.save(candidate)
            }
        }
        return session.snapshot()
    }

    fun duplicate(id: String): Vault {
        ensureOperationCurrent()
        session.snapshot().use { current ->
            val source = current.entries.single { it.id == id }
            val now = java.time.Instant.now().toString()
            val duplicate = source.copy(id = java.util.UUID.randomUUID().toString(), title = UiText.text("entry.duplicateTitle", source.title),
                createdAt = now, modifiedAt = now, deletedAt = null, history = emptyList())
            session.save(current.copy(entries = current.entries + duplicate))
        }
        return session.snapshot()
    }

    /** Irreversibly removes trashed entries with their history; only earlier backups still contain them. */
    fun purge(ids: Set<String>): Vault = purgeSelected { ids }

    fun emptyTrash(): Vault = purgeSelected { current -> current.entries.filter { it.deletedAt != null }.map { it.id }.toSet() }

    private fun purgeSelected(select: (Vault) -> Set<String>): Vault {
        ensureOperationCurrent()
        session.snapshot().use { current ->
            // Removed secrets are erased in this snapshot; the session erases its own copy after the commit.
            val candidate = current.purgeEntries(select(current))
            candidate.validate()
            session.save(candidate)
        }
        return session.snapshot()
    }

    fun addCustomer(name: String): Vault {
        ensureOperationCurrent()
        require(name.isNotBlank())
        session.snapshot().use { current ->
            session.save(current.copy(customers = current.customers + app.keyrook.core.model.Customer(java.util.UUID.randomUUID().toString(), name)))
        }
        return session.snapshot()
    }

    fun addProject(name: String, customerId: String?): Vault {
        ensureOperationCurrent()
        require(name.isNotBlank())
        session.snapshot().use { current ->
            session.save(current.copy(projects = current.projects + app.keyrook.core.model.Project(java.util.UUID.randomUUID().toString(), name, customerId)))
        }
        return session.snapshot()
    }

    fun renameCustomer(id: String, name: String): Vault = updateOrganization { current ->
        require(name.isNotBlank() && name.length <= 4096)
        require(current.customers.any { it.id == id })
        current.copy(customers = current.customers.map { if (it.id == id) it.copy(name = name.trim()) else it })
    }

    fun updateProject(id: String, name: String, customerId: String?): Vault = updateOrganization { current ->
        require(name.isNotBlank() && name.length <= 4096)
        val project = current.projects.single { it.id == id }
        require(customerId == null || current.customers.any { it.id == customerId })
        val now = java.time.Instant.now().toString()
        current.copy(
            projects = current.projects.map { if (it.id == id) it.copy(name = name.trim(), customerId = customerId) else it },
            // Moving a project carries its entries, including trash, to its new customer atomically.
            // Removing only the project's customer preserves existing entry assignments.
            entries = current.entries.map {
                if (project.customerId != customerId && customerId != null && it.projectId == id && it.customerId != customerId)
                    it.copy(customerId = customerId,
                        modifiedAt = maxOf(java.time.Instant.parse(now), java.time.Instant.parse(it.modifiedAt)).toString()) else it
            },
        )
    }

    fun removeCustomer(id: String, confirmed: Boolean): Vault = updateOrganization { current ->
        require(confirmed)
        require(current.customers.any { it.id == id })
        require(current.projects.none { it.customerId == id } && current.entries.none { it.customerId == id }) {
            "Customer is still in use"
        }
        current.copy(customers = current.customers.filterNot { it.id == id })
    }

    fun removeProject(id: String, confirmed: Boolean): Vault = updateOrganization { current ->
        require(confirmed)
        require(current.projects.any { it.id == id })
        require(current.entries.none { it.projectId == id }) { "Project is still in use" }
        current.copy(projects = current.projects.filterNot { it.id == id })
    }

    private fun updateOrganization(change: (Vault) -> Vault): Vault {
        ensureOperationCurrent()
        session.snapshot().use { current ->
            val candidate = change(current)
            candidate.validate()
            session.save(candidate)
        }
        return session.snapshot()
    }

    fun lock() { vaultPath = null; session.lock() }
    override fun close() { vaultPath = null; session.close() }
}
