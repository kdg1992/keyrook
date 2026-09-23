// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.crypto.Credentials
import app.keyrook.core.crypto.Secret
import app.keyrook.core.model.Entry
import app.keyrook.core.model.Vault
import app.keyrook.core.service.VaultSession
import java.nio.file.Path

/** Called exclusively on one worker. Returned snapshots belong to the presentation layer. */
class VaultController(internal val session: VaultSession = VaultSession(),
                      private val backoff: UnlockBackoff = UnlockBackoff()) : AutoCloseable {
    internal val sessionEpoch = SessionEpoch()
    fun unlockDelayMillis(): Long = backoff.remainingMillis()
    fun unlock(path: Path, password: CharArray, keyFile: Path?, create: Boolean): Vault {
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
                    Secret(password).use { secret ->
                        Credentials(secret, key).use { credentials ->
                            if (create) Vault().use { session.create(path, it, credentials) }
                            else session.open(path, credentials)
                        }
                    }
                } finally { key?.fill(0) }
                return session.snapshot().also { backoff.succeeded() }
            } catch (failure: Exception) {
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
            val now = java.time.Instant.now().toString()
            session.save(current.copy(entries = current.entries.map {
                if (it.id == id) it.copy(deletedAt = if (restore) null else now, modifiedAt = now) else it
            }))
        }
        return session.snapshot()
    }

    fun duplicate(id: String): Vault {
        ensureOperationCurrent()
        session.snapshot().use { current ->
            val source = current.entries.single { it.id == id }
            val now = java.time.Instant.now().toString()
            val duplicate = source.copy(id = java.util.UUID.randomUUID().toString(), title = source.title + " (Kopie)",
                createdAt = now, modifiedAt = now, deletedAt = null, history = emptyList())
            session.save(current.copy(entries = current.entries + duplicate))
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

    fun lock() = session.lock()
    override fun close() = session.close()
}
