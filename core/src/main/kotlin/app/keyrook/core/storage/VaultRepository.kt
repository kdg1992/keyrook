// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.storage

import app.keyrook.core.crypto.Credentials
import app.keyrook.core.crypto.KdfParameters
import app.keyrook.core.model.Vault
import java.nio.file.Path

/**
 * Persistence contract for encrypted vault documents with optimistic concurrency.
 *
 * `load` authenticates and returns a caller-owned document together with an opaque [FileStamp].
 * `save` with `expected = null` creates a new vault at revision zero and never replaces an
 * existing one. An update must present the stamp of the currently stored version, keep the
 * vault ID and advance the revision by exactly one; anything else throws
 * [VaultConflictException] and leaves the stored version unchanged. Implementations persist
 * only encrypted bytes. See docs/ARCHITECTURE.md for the intended use by later extensions.
 */
interface VaultRepository {
    fun load(path: Path, credentials: Credentials, allowExpensive: Boolean = false): LoadedVault

    fun save(path: Path, vault: Vault, credentials: Credentials, expected: FileStamp? = null,
             parameters: KdfParameters = KdfParameters(), allowExpensive: Boolean = false): SaveResult
}
