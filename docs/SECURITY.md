# Security properties and limitations

Keyrook currently provides a JVM core library. It is a development build, not a hardened password-manager release. Desktop UI, automatic backups, automatic/OS locking, clipboard handling and failed-attempt delays are not yet implemented. Use synthetic data until those protections and platform tests are complete.

## Cryptography and trust boundaries

Argon2id and AES-256-GCM use Bouncy Castle's established implementations. The complete binary header is authenticated. Passwords and optional 32-byte key-file contents enter Argon2 through separate standard inputs. The format and resource limits are specified in [FORMAT.md](FORMAT.md).

Secrets cannot be recovered when required credentials are lost. Keep the key file separate from vault backups; placing both together removes their separation. Short master passwords remain vulnerable to offline guessing. The current API enforces a length bound, not a strength policy. Future login delays can only slow attempts through the application, not attacks on copied files.

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

Use a trusted local directory. The code refuses a symbolic-link vault leaf and canonicalizes its parent. File locks do not protect against non-cooperating programs, malicious directory manipulation, hard-link aliases or network filesystems with different locking semantics. A non-cooperating writer can still race between the final check and rename. NAS/cloud folders are therefore unsuitable as the live multi-writer store; later backup copies are the intended integration point. This is not a synchronization protocol.

## Session behavior

`VaultSession` serializes operations. It preserves the current document and credentials when a write fails, enters `ERROR`, and permits a retry or lock. Changing a password adopts the new credentials only after a successful commit. Old backups still require the old password and may retain old secrets. Opening a vault is permitted only while locked; a failed open leaves the session locked.

Operations are synchronous and may be expensive. A desktop caller must invoke them off its UI thread. Automatic locking, cancellation policy for in-flight writes, unsaved edit handling and removal of UI-held snapshots belong to the application integration; manual session locking is already available.

## Build and verification

Build dependencies are fetched by Gradle; this is separate from the offline core runtime. Versions are pinned in the version catalog and dependency lockfile. The wrapper verifies the distribution SHA-256. Original upstream wrapper notices are preserved. Dependency licenses and roles are listed in [THIRD-PARTY-NOTICES](../THIRD-PARTY-NOTICES).

The tests cover RFC 9106 Argon2id, NIST AES-256-GCM vectors, a frozen format fixture, all eight entry variants, optional key files, tampering, limits, version rejection, absence of sentinel plaintext, failed writes, conflicts, permissions, credential changes and explicit buffer lifecycle. These tests are evidence for specific behavior, not a cryptographic audit or proof of crash safety. No migration from an older format exists because v1 is the initial format.
