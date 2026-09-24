# Security properties and limitations

Keyrook provides a JVM core and a Compose desktop application. It remains a development build, not an independently audited password-manager release. Use synthetic data until platform-specific behavior and recovery tests have been reviewed.

## Cryptography and trust boundaries

Argon2id and AES-256-GCM use Bouncy Castle's established implementations. The complete binary header is authenticated. Passwords and optional 32-byte key-file contents enter Argon2 through separate standard inputs. The format and resource limits are specified in [FORMAT.md](FORMAT.md).

Secrets cannot be recovered when required credentials are lost. Keep the key file separate from vault backups; placing both together removes their separation. Short master passwords remain vulnerable to offline guessing. The current API enforces a length bound, not a strength policy. Desktop login delays only slow attempts through that running application, not attacks on copied files.

Successful decryption detects corruption and unauthorized modification. It does not detect substitution with an older authentic copy without an external trusted record. File names, size, file-system timestamps, cipher/KDF choices and key-file usage remain visible. Choose neutral file names if their names could disclose information.

## Memory ownership

`Secret` owns a copy of its input `CharArray`. `Credentials` likewise copies its password and key-file bytes. Callers must erase their own input buffers. `useChars` and `useUtf8` provide temporary copies and wipe them in `finally`; callbacks must not retain or make unmanaged copies. `close` is idempotent and prevents subsequent access. `toString` for secret and credential containers is redacted.

`Vault.close()` closes current field values, notes and historical field values. The public data classes have ordinary shallow Kotlin `copy` semantics: closing a shallow copy also closes shared secret objects. A `VaultSession` avoids this by taking independent, serialized copies when creating/saving and returning independent snapshots. Callers own and must close every snapshot; locking the session cannot erase snapshots still held elsewhere. Public collections and documents must not be mutated concurrently by callers.

During an unlocked session the password/key-file material remains in owned buffers so fresh salts can be used on every save. Derived key buffers and serialized plaintext bytes are cleared after use. The growing plaintext output buffer clears its old storage when resized. Failed decoding closes the secret containers created so far. Parser/validation exceptions are replaced by generic exceptions without causes containing payload excerpts.

The JVM cannot guarantee complete erasure. Serialization creates immutable strings and library-owned buffers; garbage collection, JIT optimizations and cryptographic internals can retain copies, including expanded cipher keys. Metadata strings are also not erasable. Swap, hibernation, crash dumps, debuggers and hostile processes are outside this guarantee. Full-disk encryption and a trusted operating system remain necessary. The library writes no logs or telemetry and makes no network connections.

## Storage and concurrency

`VaultStore` works in the destination directory. It creates an empty persistent `.<filename>.lock` sidecar and acquires an exclusive OS file lock to coordinate cooperating writers. The sidecar contains no vault state or secrets and must not be deleted while writers might use it. All actual vault data stays in one encrypted file.

A new encrypted temporary file is created exclusively beside the target. On POSIX it starts with mode 0600. On Windows an owner-only ACL is set before writing. The file is flushed with `FileChannel.force(true)`, read back, byte-compared, decrypted and validated before replacement. Both the initial check and the pre-replacement check compare the expected ciphertext digest. Creation refuses an existing target. Updates also require the next revision and the same vault ID.

The final operation uses `ATOMIC_MOVE`; unsupported atomic replacement is an error, with no non-atomic fallback. Temporary files are removed on normal exception paths. A process crash may leave an encrypted temporary file; the core does not automatically delete unknown files at startup. Such files are not plaintext recovery copies and must not be automatically promoted to the active vault.

After a successful replacement the code attempts to force the parent directory. `SaveResult.directoryDurability` distinguishes `FORCED` from `NOT_SUPPORTED`; callers must not interpret the latter as a failed save or retry blindly. Java/Windows commonly cannot force directory metadata. Successful file flushing and rename cannot guarantee survival of every power loss on every disk/controller/filesystem. Real process-kill and power-loss tests on all target platforms remain necessary.

Use a trusted local directory. The code refuses a symbolic-link vault leaf and canonicalizes its parent. File locks do not protect against non-cooperating programs, malicious directory manipulation, hard-link aliases or network filesystems with different locking semantics. A non-cooperating writer can still race between the final check and rename. NAS/cloud folders are therefore unsuitable as the live multi-writer store; later backup copies are the intended integration point. This is not a synchronization protocol; the boundaries a later one must respect are described in [ARCHITECTURE.md](ARCHITECTURE.md).

## Session behavior

`VaultSession` serializes operations. It preserves the current document and credentials when a write fails, enters `ERROR`, and permits a retry or lock. Changing a password adopts the new credentials only after a successful commit. Old backups still require the old password and may retain old secrets. Opening a vault is permitted only while locked; a failed open leaves the session locked.

Desktop Argon2 settings use the core's automatic resource limits. Changing them
uses the same atomic save and pre-save backup path, preserving the factors.
Generating a key file uses `SecureRandom`, exclusive creation and private file
permissions; the owned 32-byte buffer is erased on every exit path. Replacing or
removing the key-file factor requires explicit confirmation alongside password
replacement. No operation rewrites old backups with new factors or KDF settings.

The desktop controller runs vault operations on a serial worker, keeping Argon2 and storage off the event thread. UI snapshots are independent and closed on replacement/lock. Locking immediately removes the document and unsaved editors from presentation state, closes its snapshot and clears the owned clipboard, even while work is running. It invalidates the operation generation and closes open application dialogs. Pending confirmations check their generation before proceeding. Late results are closed instead of reopening the vault or changing the locked screen. Session cleanup is queued behind outstanding work; an atomic write already started is allowed to finish rather than being interrupted. Consequently locking can complete presentation cleanup before the worker has erased its credentials. Process termination, sleep suspension and power loss can still stop a worker at any point.

Inactivity locking defaults to five minutes and can be set to 1, 2, 5, 10, 15 or 30 minutes for the current application session. A monotonic timer observes keyboard and mouse activity in application windows. Minimizing or switching away from the application's windows also locks it; transitions to this JVM's own dialogs are exempt when the window system identifies the destination. AWT user-session deactivation, screen-sleep and system-sleep events trigger locking where the platform advertises support. These APIs do not provide a universal OS-lock notification: conservative window-deactivation locking and the inactivity deadline provide additional coverage. Behavior under each target desktop/window manager still requires end-to-end verification; no privileged native hooks are installed.

An input event arriving after the inactivity deadline requests locking before it
can reset the deadline. This also covers a delayed event loop whose periodic
timer has not yet processed the expiry.

Failed unlock attempts impose delays of 1, 2, 4, 8, 16, 32 and then at most 60 seconds. Delays use monotonic time, remain in force when locking or retrying, and reset after a successful unlock. Rejected retries do not derive a key and their submitted password arrays are still erased. This state is process-local and resets when the application restarts. It cannot defend against a modified application or offline password guessing.

Compose and Swing text controls retain immutable strings, including temporary passwords and edited fields. These cannot be reliably erased; owned input arrays and `Secret` instances are cleared. Explicit field copying clears the owned clipboard after 20 seconds by default and on lock. The expiry can be set to 5, 10, 20, 30, 60 or 120 seconds for this application session; changing it clears a currently owned value. Clipboard access can be delayed by another application; clearing retries. Later clipboard owners are not intentionally cleared. OS clipboard history and clipboard managers can retain copies that Keyrook cannot remove.

Clipboard ownership uses a per-copy JVM-local token and owner callback rather
than comparing secret text. Equal text copied by another owner does not identify
Keyrook's copy. Locally owned clipboard character arrays are erased when cleared
or ownership is lost, including while OS clearing must retry. AWT supplies no
atomic OS compare-and-clear operation: an ownership change between checking and
clearing remains a platform limitation. Closing the clipboard guard bounds its
retry period; permanently unavailable clipboard access cannot guarantee OS erasure.

## Local warning list

The warning list examines active entries only: expiry before today, expiry within
30 days, passwords shorter than 14 characters or containing fewer than four
distinct characters, and passwords reused across different entries. These are
limited heuristics, not an entropy estimate or a breached-password database.
Custom fields are recognized by the names password, passwort and passphrase.
Reuse comparisons use HMAC-SHA-256 with a fresh random key for each inspection.
The key and comparison buffers are cleared afterward; only entry IDs and warning
categories are returned. Nothing is persisted or sent over a network.
Provider-internal key copies remain subject to the JVM memory limits above.

## Backups and transfer

An optional backup folder preserves the authenticated ciphertext of the previous saved revision before each update or password change. Backup failure prevents the vault replacement. Rotation keeps the latest configured versions plus daily representatives; only files matching this vault's exact managed naming pattern are eligible. Backups are ciphertext, not plaintext exports. Their names reveal a random vault ID, revision and time. Choose a trusted, existing directory; symlink paths are rejected. Locking clears the active backup setting; the folder and retention are remembered per vault file in the settings file and reapplied, with the same checks, only after that vault file is unlocked again.

Manual backups use the same authenticated copy, expected file stamp and rotation checks as automatic backups. They preserve the saved vault bytes and revision. Status reports the last successfully copied revision for the current session configuration; it does not assert that a backup remains available after external changes. Disabling backups requires confirmation, clears the configuration and status, and leaves existing files intact.

A password change does not re-encrypt old backups: they still require their old password and key file. Backup preview authenticates contents before revealing entry counts; the displayed filesystem date is not authenticated. Restoration checks the preview digest again, refuses existing targets and creates a new vault file at revision zero. It never overwrites the open vault. Neither a valid backup nor its preview proves freshness against replay of an older authentic file.

Plaintext JSON/CSV export requires two separate UI confirmations. New export files receive private POSIX/Windows permissions before writing, refuse replacement and clear owned buffers on completion/failure. Filesystems without usable private permissions are refused. Files left after process termination, OS caches, backups and later copies cannot be reliably erased. Keyrook JSON/CSV preserves the full model and history; CSV wraps that JSON in a quoted record, not a password spreadsheet. Imported external formats are bounded and validated before an explicit merge confirmation; conflicting IDs reject the merge. XML rejects DTDs/external entities. Unsupported attachments, passkeys and protected plugin data are refused rather than silently dropped. See [DESKTOP.md](DESKTOP.md).

## Saved settings

Preferences are stored in plaintext as `settings.json` in the platform configuration directory (see [DESKTOP.md](DESKTOP.md#saved-settings)). The file contains only a format version, the appearance, language, inactivity deadline, clipboard expiry, the last opened vault path and, per vault path, the backup folder, retention counts and whether backups are enabled. It never contains passwords, key-file paths or contents, entry data, vault titles or customer and project names. It is still metadata: anyone who can read it learns where vaults and their backups are stored. Key-file paths are deliberately not saved so the file does not reveal which file is the second factor; the key file must be selected again after each start. The file and a newly created directory receive owner-only permissions where POSIX permissions are supported, and Windows restricts the file to its owner through an ACL. Writes go to a private temporary file that atomically replaces the previous file. Symbolic links, oversized, malformed, unknown-version and out-of-range values fall back to defaults; file contents are never logged or displayed. A tampered file can at most point the unlock form or backups to another path: restored backup folders pass the same symlink and existence checks as manual selection, and unlocking still requires the password and key file.

## SSH keys

Apache MINA SSHD writes encrypted OpenSSH keys with AES-256-CTR and bcrypt (64 rounds). Ed25519 and RSA-4096 use established providers; the application does not implement key algorithms or cryptographic formats. Imports verify that the public/private pair matches using a signature challenge. OpenSSH is a standard encrypted format with check values, not authenticated vault encryption; store private keys inside the authenticated vault. Generated private material and its passphrase are masked by default.

SSH inputs are limited to 64 KiB with bounded line lengths. OpenSSH bcrypt and supported encrypted-PKCS#8 PBKDF2 parameters are capped before derivation. Provider-owned private keys and immutable parser/passphrase strings cannot be fully erased. The application never logs parser exceptions and does not install a logging provider.

PPK 2/3 uses a bounded format adapter following the
[PuTTY specification](https://the.earth.li/~sgtatham/putty/0.85/htmldoc/AppendixC.html).
BC supplies SHA-1/SHA-256, HMAC, AES-CBC and Argon2; no cryptographic primitive is
implemented by the adapter. MAC comparison is constant-time and precedes private
key construction even for unencrypted files. Exact UTF-8 comment bytes participate
in the MAC. Public key framing and RSA operand sizes are bounded before provider
calls, and the existing signing check verifies the key pair before OpenSSH export.
Version 3 derivation allows at most 262144 KiB, 128 passes and 16 lanes, with the
additional bound `memoryKiB * passes <= 262144 * 5`, checked before derivation.
The product bound accommodates PuTTY's many-pass/small-memory settings without
exceeding the vault's automatic memory/work budget. PPK 1, unsupported ciphers,
other key types, duplicate/out-of-order headers and trailing records are rejected.
Owned byte buffers are erased on exit; provider state, immutable strings and RSA
integers remain subject to JVM garbage collection. Independent PuTTY Ed25519
vectors and separate JCE-generated RSA fixtures exercise interoperability and
tampering. These checks are not an external cryptographic audit.

Public SSH export parses the key using MINA, checks the supported type and RSA
bounds and compares its canonical blob to the input. Options, trailing binary
data and control characters are refused. Export creates a new private-permission
file, and canceled/disposed editors cannot initiate a later export. Imported
private material and replacement passphrase buffers are released even when an
insertion callback fails.

SSH/SFTP connection commands are copied as text only. Host and username operands use a strict character/length policy, ports are bounded, and operands are quoted for PowerShell/POSIX shells. IPv6 validation parses literals without DNS or interface resolution. Passwords, key material, directory paths and arbitrary remote commands are never appended. A copied command still invokes the user's own OpenSSH configuration if they execute it outside the application.

Background searches own an independent session snapshot and close it on completion or cancellation. No persistent plaintext index is built. Secret field scans operate on temporary character arrays that are erased after use; current notes are searched by default, while hidden field values require an explicit option. Query text itself is an immutable UI string and may contain sensitive user input. Search results display entry metadata rather than matching secret excerpts. A search already running when locking occurs may retain its snapshot until the next cancellation check and cleanup.

Before an imported RSA key is used for a signature challenge, its public and private moduli must match and have 4096 bits. Public exponents must match, be odd, and fit within 32 bits; private exponents and CRT operands must be positive and at most 4096 bits. These bounds limit work on attacker-controlled parameters before provider-backed signing and verification. They supplement the signature consistency check rather than replace it.

## Build and verification

Build dependencies are fetched by Gradle; this is separate from the offline core runtime. Versions are pinned in the version catalog and dependency lockfile. The wrapper verifies the distribution SHA-256. Original upstream wrapper notices are preserved. Dependency licenses and roles are listed in [THIRD-PARTY-NOTICES](../THIRD-PARTY-NOTICES).

The tests cover RFC 9106 Argon2id, NIST AES-256-GCM vectors, a frozen format fixture, all eight entry variants, optional key files, tampering, limits, version rejection, absence of sentinel plaintext, failed writes, conflicts, permissions, credential changes and explicit buffer lifecycle. These tests are evidence for specific behavior, not a cryptographic audit or proof of crash safety. A schema migration pipeline exists and is covered by the format migration test with a synthetic older schema; production registers no steps because v1 is the initial format.

## Format migrations

Migrations run only after successful authentication, on the decrypted JSON tree, and only for an older schema with a complete registered chain; newer, unknown or unbridged versions are rejected. Steps must keep the vault ID and revision, and the result is re-encoded and checked by the same JSON guard, decoder and schema validation as a current document. The migration path creates additional transient plaintext: the JSON tree holds immutable strings that cannot be erased, and the intermediate re-encoded bytes are wiped. Opening never rewrites the file; the next ordinary save writes the current format atomically, preceded by the configured automatic backup of the older ciphertext as for any save. Current-version files use the unchanged decoding path apart from a streaming read of `schemaVersion`.
