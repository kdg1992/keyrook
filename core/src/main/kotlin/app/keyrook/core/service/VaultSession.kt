// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.service

import app.keyrook.core.crypto.Credentials
import app.keyrook.core.backup.BackupService
import app.keyrook.core.backup.BackupResult
import app.keyrook.core.backup.IntegrityCheck
import app.keyrook.core.backup.IntegrityReport
import app.keyrook.core.crypto.KdfParameters
import app.keyrook.core.format.VaultCodec
import app.keyrook.core.model.Vault
import app.keyrook.core.storage.*
import java.nio.file.Path

enum class SessionState { LOCKED, UNLOCKED, SAVING, ERROR }

/** Session-only status; never exposes credentials or filesystem paths. */
data class BackupStatus(val configured: Boolean, val lastRevision: Long?)

/** Serializes access. Snapshots are independent and must be closed by their caller. */
class VaultSession(private val store: VaultStore = VaultStore(), private val codec: VaultCodec = VaultCodec()) : AutoCloseable {
    private var credentials: Credentials? = null
    private var document: Vault? = null
    private var path: Path? = null
    private var stamp: FileStamp? = null
    private var parameters = KdfParameters()
    private var currentState = SessionState.LOCKED
    private var backups: BackupService? = null
    private var lastBackupRevision: Long? = null
    private var incompleteRotation: BackupResult? = null
    val state: SessionState @Synchronized get() = currentState

    /** A configured backup must succeed before an existing vault is replaced. */
    @Synchronized fun configureBackups(service: BackupService?) {
        requireDocument()
        backups = service
        lastBackupRevision = null
        incompleteRotation = null
    }

    @Synchronized fun backupStatus(): BackupStatus = BackupStatus(backups != null, lastBackupRevision)

    /** Authenticates and copies the persisted revision without rewriting it or exporting credentials. */
    @Synchronized fun backupNow(allowExpensive: Boolean = false): BackupResult {
        requireDocument()
        val service = checkNotNull(backups) { "Backups are not configured" }
        return createBackup(service, allowExpensive)
    }

    /**
     * Read-only authentication of the persisted vault and every managed backup of this vault in the configured
     * folder, using the session credentials without exporting them. The live file must match the opened revision.
     * Every file costs a full key derivation while this session is held, so callers pass [cancelled] (for example
     * "a lock was requested"): it is consulted before each file and ends the check with
     * [app.keyrook.core.backup.IntegrityCheckCancelledException], releasing the session for [lock] after at most the
     * file already in progress.
     */
    @Synchronized fun checkIntegrity(allowExpensive: Boolean = false, cancelled: () -> Boolean = { false }): IntegrityReport {
        val current = requireDocument()
        return IntegrityCheck(codec).run(path!!, current.id, stamp!!.revision, backups?.directory,
            credentials!!, allowExpensive, cancelled)
    }

    /**
     * Returns and forgets the latest backup, automatic or manual, whose rotation could not remove every old backup,
     * or null. Such a backup and the save it preceded succeeded; callers show a non-blocking notice.
     */
    @Synchronized fun takeIncompleteRotation(): BackupResult? = incompleteRotation.also { incompleteRotation = null }

    private fun createBackup(service: BackupService, allowExpensive: Boolean): BackupResult =
        service.create(path!!, credentials!!, stamp!!, allowExpensive).also {
            lastBackupRevision = stamp!!.revision
            if (!it.rotationComplete) incompleteRotation = it
        }

    @Synchronized fun create(path: Path, vault: Vault, credentials: Credentials,
                             parameters: KdfParameters = KdfParameters(), allowExpensive: Boolean = false): SaveResult {
        check(currentState == SessionState.LOCKED)
        val owned = codec.duplicate(vault)
        val ownedCredentials = copyCredentials(credentials, owned)
        try {
            currentState = SessionState.SAVING
            val result = store.save(path, owned, ownedCredentials, parameters = parameters, allowExpensive = allowExpensive)
            install(path, owned, ownedCredentials, result.stamp, parameters)
            return result
        } catch (e: Exception) {
            owned.close(); ownedCredentials.close(); currentState = SessionState.LOCKED
            throw e
        }
    }

    @Synchronized fun open(path: Path, credentials: Credentials, allowExpensive: Boolean = false) {
        check(currentState == SessionState.LOCKED)
        val ownedCredentials = credentials.copy()
        try {
            val loaded = store.load(path, ownedCredentials, allowExpensive)
            install(path, loaded.vault, ownedCredentials, loaded.stamp, loaded.parameters)
        } catch (e: Exception) { ownedCredentials.close(); throw e }
    }

    @Synchronized fun snapshot(): Vault = codec.duplicate(requireDocument())

    @Synchronized fun kdfParameters(): KdfParameters { requireDocument(); return parameters }

    /** Re-encrypts atomically with existing factors and the same backup policy as an ordinary save. */
    @Synchronized fun changeKdf(parameters: KdfParameters): SaveResult {
        parameters.validate()
        return save(requireDocument(), parameters)
    }

    /** Candidate stays caller-owned; session only adopts its independent copy after successful commit. */
    @Synchronized fun save(candidate: Vault, parameters: KdfParameters = this.parameters,
                           allowExpensive: Boolean = false): SaveResult {
        val current = requireDocument()
        require(candidate.id == current.id && candidate.revision == current.revision) { "Stale or foreign document" }
        check(current.revision != Long.MAX_VALUE)
        return commit(codec.duplicate(candidate.copy(revision = current.revision + 1)), credentials!!, false, parameters, allowExpensive)
    }

    @Synchronized fun changePassword(newCredentials: Credentials, parameters: KdfParameters = this.parameters,
                                     allowExpensive: Boolean = false): SaveResult {
        val current = requireDocument()
        check(current.revision != Long.MAX_VALUE)
        val next = codec.duplicate(current.copy(revision = current.revision + 1))
        val nextCredentials = copyCredentials(newCredentials, next)
        return commit(next, nextCredentials, true, parameters, allowExpensive)
    }

    private fun commit(next: Vault, nextCredentials: Credentials, replaceCredentials: Boolean,
                       parameters: KdfParameters, allowExpensive: Boolean): SaveResult {
        currentState = SessionState.SAVING
        try {
            backups?.let { createBackup(it, allowExpensive) }
            val result = store.save(path!!, next, nextCredentials, stamp, parameters, allowExpensive)
            document!!.close()
            document = next
            if (replaceCredentials) { credentials!!.close(); credentials = nextCredentials }
            stamp = result.stamp
            this.parameters = parameters
            currentState = SessionState.UNLOCKED
            return result
        } catch (e: Exception) {
            next.close()
            if (replaceCredentials) nextCredentials.close()
            currentState = SessionState.ERROR
            throw e
        }
    }

    private fun install(path: Path, vault: Vault, credentials: Credentials, stamp: FileStamp, parameters: KdfParameters) {
        this.path = path.toAbsolutePath().normalize()
        document = vault
        this.credentials = credentials
        this.stamp = stamp
        this.parameters = parameters
        currentState = SessionState.UNLOCKED
    }
    private fun requireDocument(): Vault {
        check(currentState == SessionState.UNLOCKED || currentState == SessionState.ERROR) { "Vault is locked" }
        return document!!
    }
    private fun copyCredentials(source: Credentials, ownedDocument: Vault): Credentials =
        try { source.copy() } catch (e: Exception) { ownedDocument.close(); throw e }
    @Synchronized fun lock() {
        document?.close(); credentials?.close()
        document = null; credentials = null; path = null; stamp = null; backups = null
        lastBackupRevision = null
        incompleteRotation = null
        currentState = SessionState.LOCKED
    }
    override fun close() = lock()
}
