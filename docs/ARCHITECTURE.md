# Architecture

This document describes how Keyrook is structured today and where features that
are deliberately outside version 1 would attach. Paths below are relative to
`core/src/main/kotlin/app/keyrook/core/` (core) and
`app/src/main/kotlin/app/keyrook/app/` (app). Security limits are specified in
[SECURITY.md](SECURITY.md), the on-disk format in [FORMAT.md](FORMAT.md).

## Modules and dependencies

The Gradle build has two modules (`settings.gradle.kts`).

| Module | Contents | Depends on |
| --- | --- | --- |
| `core` | Model, cryptography, file format, storage, session, backups, transfer, SSH, generators, health checks | kotlinx-serialization, Bouncy Castle, Apache MINA SSHD common (`core/build.gradle.kts`) |
| `app` | Compose desktop UI, worker threads, locking, clipboard, dialogs | `project(":core")`, Compose desktop (`app/build.gradle.kts`) |

`core` has no UI, no logging provider, no network code and no dependency on
`app`. `app` depends on `core` only through its public API. The
`checkRuntimeDependencies` task keeps test tooling off the core runtime
classpath.

Core packages, from bottom to top:

| Package | Responsibility |
| --- | --- |
| `crypto` (`Secret.kt`, `VaultCrypto.kt`) | `Secret` and `Credentials` own erasable copies; Argon2id and AES-256-GCM via Bouncy Castle; `KdfParameters` bounds |
| `model` (`Vault.kt`) | Serializable `Vault`, `Entry`, eight `EntryData` variants, `validate()` and `close()` |
| `format` (`VaultCodec.kt`, `VaultHeader.kt`) | Header encoding, JSON limits, encrypt/decrypt of a complete document |
| `storage` (`VaultStore.kt`, `VaultRepository.kt`) | Atomic file replacement, sidecar lock, `FileStamp` concurrency tokens |
| `backup` (`BackupService.kt`, `MigrationBackup.kt`) | Ciphertext copies, rotation, authenticated preview, restore to a new file, the copy of an older-schema file kept before its first rewrite |
| `service` (`VaultSession.kt`) | Owns the unlocked document and credentials; serializes create/open/save/lock |
| `transfer`, `ssh`, `generator`, `security` | Import/export, SSH key handling, password generation, local warning list, network-free hashing and range matching of the breach check (`BreachCheck.kt`; the requests are made in `app`, `BreachChecks.kt`) |
| `otp` (`Totp.kt`) | RFC 6238 TOTP code generation from Base32 secrets and `otpauth://totp` URIs |
| `settings` (`SettingsCodec.kt`, `WindowGeometry.kt`) | Parsing and bounds of the non-secret preferences file, including window placement; the file itself is read and written in `app` |
| `update` (`ReleaseCheck.kt`) | Network-free validation of a release document and version comparison; the request itself is made in `app` (`UpdateCheck.kt`) |

In `app`, `VaultController.kt` wraps one `VaultSession` and implements the
document edits the UI offers (entries, trash, favorites, customers, projects,
templates). Some
screens call the session directly through `controller.session` (backups in
`BackupConfiguration.kt`, KDF settings in `CredentialSettings.kt`, transfer and
password change in `DataTools.kt`). Encrypted export in `DataTools.kt` uses a
separate `VaultStore` to create a new file at revision zero.

The main window is split into `Main.kt` (entry point and window setup),
`KeyrookApp.kt` (header, settings row and screen selection), `AppState.kt`
(window state, the vault worker, `operation`, `lockNow` and disposal),
`UnlockScreen.kt` (open and create form), `Workspace.kt` (toolbar, entry list
and detail pane), `EntryEditorScreen.kt` (entry editor) and `AppDialogs.kt`
(warnings, about, worker dialogs and the question before quitting).

## Data flow

**Open.** `VaultController.unlock` wraps the password in a `Secret` and the
optional 32-byte key file in `Credentials`, then calls `VaultSession.open`. The
session copies the credentials and calls `VaultStore.load`, which reads the
file with a size bound, `VaultCodec.decrypt` parses the header, derives the key,
authenticates and validates the JSON (migrating an older schema in memory, see
FORMAT.md), and returns `LoadedVault` with a `FileStamp` (SHA-256 of the
ciphertext, vault ID, revision), the stored KDF parameters and the stored schema
version. The session keeps the document, credentials, path and stamp; the
controller returns an independent `snapshot()` to the UI.

**Save.** Every edit takes a snapshot, builds a candidate `Vault` with ordinary
`copy`, validates it and calls `VaultSession.save`. The session requires the
candidate's ID and revision to match its current document, duplicates it with
`revision + 1` and commits: if the file still stores an older schema,
`preserveBeforeMigration` first keeps its unchanged ciphertext next to it (once
per session), a configured `BackupService` then copies the currently persisted
ciphertext, then `VaultStore.save` takes the sidecar lock,
checks the expected stamp, encrypts with a fresh salt and nonce, writes and
flushes a private temporary file, reads it back, compares and decrypts it,
checks the stamp again and replaces the target with `ATOMIC_MOVE`. Only after
success does the session adopt the new document and stamp. On failure it keeps
the previous document and enters `ERROR` (`SessionState`).

**Lock.** `VaultSession.lock` closes the document and credentials and clears
path, stamp and backup configuration. In the UI, `lockNow` in `AppState.kt`
invalidates the `SessionEpoch`, cancels the dialog host and disposes remaining dialogs, closes the presented
snapshot, clears the owned clipboard and queues `controller.lock()` on the
vault worker. Triggers are the lock shortcut, `DesktopLockMonitor.kt`
(inactivity, AWT session and sleep events, and minimizing or window
deactivation according to the saved window lock choice) and closing the
window.

**Backup.** Automatic backups run inside the session commit before replacement;
a backup failure aborts the save. `VaultSession.backupNow` performs a manual copy
of the current revision. `BackupService.create` authenticates the source, checks
it against the session's `FileStamp`, writes a `CREATE_NEW` copy under a
directory lock, verifies it and rotates only files matching the vault's naming
pattern. Restore (`restoreToNew`) re-checks the preview digest and creates a new
vault at revision zero through `VaultStore`; it never overwrites the open vault.

## Threading model

Core operations are synchronous. `VaultSession` methods are `@Synchronized`;
`Secret` and `Credentials` synchronize their own state. The desktop app runs
all session work on one daemon thread, `vault-worker`, created in
`AppState` (`AppState.kt`), so Argon2 and file I/O never run on the event thread.
`operation { ... }` captures the current `SessionEpoch` token; results are
delivered on the Swing thread only if the token is still current, otherwise the
returned snapshot is closed (`SessionSecurity.kt`). `OperationGuard.kt` lets
long operations and dialogs check the token mid-flight. Messages, questions
and input prompts of an operation go through `DialogHost` (`DialogHost.kt`,
`DialogBridge.kt`): the worker queues a request and blocks until the UI answers
it in a Compose dialog (`ComposeDialogs.kt`); locking cancels every request.

Other threads never hold the session: `vault-search` (`SearchResults.kt`) takes
its own snapshot and closes it, `clipboard-expiry` (`SecretClipboard.kt`) only
clears the clipboard, and short-lived `ssh-key-worker` and `passphrase-worker`
threads (`EntryEditorScreen.kt`, `GeneratorTools.kt`) generate material and hand it to the
editor on the Swing thread; it is persisted only when the user saves, through
the vault worker.

## Where secrets live and where they are wiped

| Material | Owner | Erased by |
| --- | --- | --- |
| Master password, key-file bytes | `Credentials` inside `VaultSession` | `VaultSession.lock`/`close`; input arrays are filled in `VaultController.unlock` |
| Derived AES key | Local variable in `VaultCodec` | `key.fill(0)` after each encrypt/decrypt |
| Serialized plaintext JSON | `VaultCodec` | `plaintext.fill(0)` in `finally` |
| Field values, entry notes, customer and project notes | `Secret` inside `Vault` | `Vault.close()`; every snapshot is closed by its holder |
| Presented snapshot | `vault` state in `AppState.kt` | Closed on replacement, lock and window disposal |
| Clipboard copies | `ClipboardGuard` | Expiry timer, lock, ownership loss |

Immutable strings created by JSON serialization, Compose text fields and
provider internals cannot be erased; see the memory section of
[SECURITY.md](SECURITY.md).

## Persistence contract

`storage/VaultRepository.kt` states the contract that `VaultStore` implements:
`load` returns an authenticated, caller-owned document with an opaque stamp;
`save` with no stamp creates revision zero and refuses to replace an existing
vault; an update must present the current stamp, keep the vault ID and advance
the revision by exactly one, otherwise `VaultConflictException` is thrown and
the stored version is unchanged. `VaultRepositoryContractTest` checks the file
store only through this interface.

The interface is intentionally still addressed by `Path` and `FileStamp`,
because those are the types the real code uses. `VaultSession` keeps its
concrete `VaultStore` dependency: its backup step reads the vault file by path
(`BackupService.create`), so accepting an arbitrary repository would suggest a
backend independence the session does not have. The revision and stamp rules
are the part later extensions are expected to reuse.

## Prepared extensions (not part of version 1)

None of the following is implemented. Version 1 opens no network listener,
starts no local server, registers no IPC endpoint or native messaging host and
makes no outbound connection apart from explicit browser links opened through
`Desktop.browse`, the opt-in update check (one HTTPS request to
`api.github.com`, see [SECURITY.md](SECURITY.md#update-check)) and the
user-confirmed breach check (hash-prefix requests to `api.pwnedpasswords.com`,
see [SECURITY.md](SECURITY.md#breach-check)). Each section names the intended integration point, the
boundary it must respect and what must not be done.

### Server synchronization and multi-user roles

*Integration point.* A synchronizing backend would implement the revision rules
of `VaultRepository`: the encrypted document is the unit of exchange, the
`revision` field in `Vault` (FORMAT.md) orders versions, and a stale write is a
conflict instead of a silent overwrite. A remote store needs a location and
token type that is not a file path or file digest; that generalization belongs
in `storage` together with the first real backend, and `VaultSession`'s backup
step would have to become backend-aware at the same time.

*Boundary.* The server stores ciphertext only; encryption and decryption stay in
`VaultCodec` on the client. Conflicts are surfaced to the user, never merged
automatically inside secret fields. Replay of an older authentic file is not
detected today (SECURITY.md), so a sync protocol must add its own trusted
revision record. Roles require per-user key material and therefore a new,
versioned format with an explicit migration (FORMAT.md, "Compatibility and
migrations"); one shared master password is not a role model.

*Must not.* No plaintext on the server, no server-side key derivation, no use of
NAS or cloud folders as a live multi-writer store (SECURITY.md, "Storage and
concurrency"), no weakening of the stamp and revision checks to make sync
easier, and no format change without a new version.

### Browser extension for autofill

*Integration point.* A read-only provider built on `VaultSession.snapshot()`: it
would look up entries by URL (`EntryData.Web.url`, `EntryData.Panel.url`), return
the requested fields for one entry and close the snapshot afterwards. It runs on
the vault worker and respects `SessionEpoch` so that locking invalidates
outstanding requests. No provider interface exists in `core` yet: every
concrete signature would fix policy that has not been designed (origin matching
rules, user confirmation per fill, which fields may leave the process), and an
unused public interface could not be tested against a real consumer.

*Boundary.* Only an unlocked session can answer, and only after user
confirmation. The channel to the browser must be authenticated and encrypted
end to end between the extension and the application, and must answer nothing
while locked. Stored URLs would be parsed with the same strictness as
`BrowserLinks.parse` (http/https only, a host, no embedded credentials) and
matched on exact origin; no fuzzy matching of credentials across domains.

*Must not.* No plaintext IPC (no unauthenticated local socket, pipe, file or
clipboard hand-off), no listening TCP port, no bulk export of the vault to the
extension, no caching of secrets in the extension beyond a single fill.

### SSH agent integration

*Integration point.* SSH keys already live in the vault as `EntryData.Ssh` with
an encrypted OpenSSH private key and its passphrase; `ssh/SshKeyService.kt`
parses and validates them with Apache MINA SSHD. An agent would expose public
keys from a snapshot and perform signatures inside the application process,
decrypting a private key only for the duration of one signing request.

*Boundary.* Private keys never leave the process; the agent returns signatures
only. Each signature requires an unlocked session and follows the same lock
triggers. The agent socket or named pipe must be restricted to the current user
(the same private-permission approach as `VaultStore` and export files).

*Must not.* No writing of decrypted keys to disk or to `ssh-agent` of the
operating system, no forwarding of keys to remote hosts by default, no agent
endpoint that keeps working after locking.

### Mobile apps

*Integration point.* `core` is JVM Kotlin without UI, so a separate mobile
module next to `app` would depend on it exactly as `app` does. Its libraries
(Bouncy Castle, Apache MINA SSHD, kotlinx-serialization) and the NIO file
attributes used by `VaultStore` must first be verified on the target platform. The file format (FORMAT.md) is the compatibility contract. Mobile
platforms would exchange encrypted vault files or use the synchronization
backend above, never a separate plaintext format.

*Boundary.* The Argon2 bounds in `KdfParameters` and the `allowExpensive`
approval apply unchanged; devices that cannot afford the stored parameters must
refuse rather than silently lower them. Platform keystores may protect a local
unlock convenience, but the vault itself stays encrypted with the documented
password and key-file inputs.

*Must not.* No mobile-only format variant, no reduced KDF cost for mobile
convenience and no background process that holds the vault unlocked outside the
platform's lock and inactivity rules.
