# Security properties and limitations

Keyrook provides a JVM core and a Compose desktop application. It remains a development build, not an independently audited password-manager release. Use synthetic data until platform-specific behavior and recovery tests have been reviewed.

## Cryptography and trust boundaries

Argon2id and AES-256-GCM use Bouncy Castle's established implementations. The complete binary header is authenticated. Passwords and optional 32-byte key-file contents enter Argon2 through separate standard inputs. The format and resource limits are specified in [FORMAT.md](FORMAT.md).

Secrets cannot be recovered when required credentials are lost. Keep the key file separate from vault backups; placing both together removes their separation. Short master passwords remain vulnerable to offline guessing. The current API enforces a length bound, not a strength policy. Desktop login delays only slow attempts through that running application, not attacks on copied files.

Successful decryption detects corruption and unauthorized modification. It does not detect substitution with an older authentic copy without an external trusted record. File names, size, file-system timestamps, cipher/KDF choices and key-file usage remain visible. Choose neutral file names if their names could disclose information.

## Memory ownership

`Secret` owns a copy of its input `CharArray`. `Credentials` likewise copies its password and key-file bytes. Callers must erase their own input buffers. `useChars` and `useUtf8` provide temporary copies and wipe them in `finally`; callbacks must not retain or make unmanaged copies. `close` is idempotent and prevents subsequent access. `toString` for secret and credential containers is redacted, as it is for desktop state that holds a stored TOTP value or a shown TOTP code as text.

`Vault.close()` closes current field values, notes and historical field values. The public data classes have ordinary shallow Kotlin `copy` semantics: closing a shallow copy also closes shared secret objects. A `VaultSession` avoids this by taking independent, serialized copies when creating/saving and returning independent snapshots. Snapshots are built without serialization: every `Secret` is copied through a temporary character array that is erased afterwards, so taking a snapshot creates no immutable string of a secret value. `VaultSession.read` gives read-only scans such a copy for the duration of a callback and erases it when the callback returns; the copy is taken under the session lock, but the scan itself runs outside it, so it can neither observe nor erase the live document and never delays locking or saving. A scan waits while the session is busy, for example during an integrity check, which is why scans run on background threads and never on the event thread. Callers own and must close every snapshot; locking the session cannot erase snapshots still held elsewhere. Public collections and documents must not be mutated concurrently by callers.

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
permissions; the owned 32-byte buffer is erased on every exit path. Its path is
resolved like a vault file: a symbolic link among the parent directories is
accepted, while a key file path that is itself a symbolic link is refused and never
followed. Replacing or removing the key-file factor requires explicit confirmation
alongside password replacement. No operation rewrites old backups with new factors or KDF settings.

The desktop controller runs vault operations on a serial worker, keeping Argon2 and storage off the event thread. UI snapshots are independent and closed on replacement/lock. Locking immediately removes the document and unsaved editors from presentation state, closes its snapshot and clears the owned clipboard, even while work is running. It invalidates the operation generation and closes open application dialogs. Messages, questions and password prompts are drawn inside the main window; a worker waiting for an answer is released as if the user had canceled, and its generation is checked before a question is shown and again after it is answered, so an answer from before the lock is never used. A password confirmed too late is erased instead of delivered. Late results are closed instead of reopening the vault or changing the locked screen. Session cleanup is queued behind outstanding work; an atomic write already started is allowed to finish rather than being interrupted. Consequently locking can complete presentation cleanup before the worker has erased its credentials. Process termination, sleep suspension and power loss can still stop a worker at any point.

Inactivity locking defaults to five minutes and can be set to 1, 2, 5, 10, 15 or 30 minutes. The choice is saved in the settings file and applies again after a restart (see [Saved settings](#saved-settings)). A timer observes keyboard and mouse activity in application windows. Window-event locking follows the saved window lock choice, read at each event so a change applies immediately: `FOCUS_LOSS` locks when the application's windows lose focus or are minimized, `MINIMIZE` (the default) locks only when minimized, `NEVER` locks on neither. Minimizing is recognized from an iconify event or a window-state change into the iconified state. Under `FOCUS_LOSS`, transitions to this JVM's own dialogs are exempt when the window system identifies the destination. AWT user-session deactivation, screen-sleep and system-sleep events trigger locking under every choice where the platform advertises support, and the inactivity deadline always applies. These APIs do not provide a universal OS-lock notification. With `MINIMIZE` or `NEVER`, an unlocked vault therefore stays unlocked, and readable by anyone at the keyboard, while another window is in front, until the inactivity deadline, a delivered OS lock/sleep event or a manual lock. `FOCUS_LOSS` narrows that window and is recommended on shared or unattended machines. The choice does not affect clipboard expiry. Behavior under each target desktop/window manager still requires end-to-end verification; no privileged native hooks are installed.

An input event arriving after the inactivity deadline requests locking before it
can reset the deadline. This also covers a delayed event loop whose periodic
timer has not yet processed the expiry.

Inactivity is measured with both the monotonic clock and the wall clock, because
the monotonic clock can stop while the machine is suspended (on Linux in
particular, where the JDK usually delivers no sleep events). The deadline expires
when either clock has advanced by the chosen timeout since the last activity. Only
forward wall-clock steps count: a clock set backwards never extends the deadline,
and small forward adjustments (such as NTP corrections) shorten it by at most
their size. If the wall clock advances at least 30 seconds more than the monotonic
clock between two checks, this is treated as a suspend and the vault locks
immediately, regardless of the remaining time. The check runs on the first
half-second timer tick after resume and on the first input event, before that
input can reset the deadline. A forward wall-clock step of 30 seconds or more
while the machine is awake therefore also locks.

Failed unlock attempts impose delays of 1, 2, 4, 8, 16, 32 and then at most 60 seconds. Only an authentication failure of the vault, that is a wrong password or key file, counts; I/O errors, invalid or unsupported files, conflicts and malformed key files do not. Delays use monotonic time, remain in force when locking or retrying, and reset after a successful unlock. Rejected retries do not derive a key and their submitted password arrays are still erased. This state is process-local and resets when the application restarts. It cannot defend against a modified application or offline password guessing.

The read-only detail view renders masked fields and the notes as dots. A value is converted to text only while its **Anzeigen** toggle is on; the shown positions are held without values and are reset when another entry, vault or saved version is displayed, when the view leaves the screen (editor, narrow window, lock) and on every window deactivation or minimization, regardless of the window lock choice. The displayed text is not selectable, so copying goes through the owned, expiring clipboard. Shown text is still an immutable JVM string that cannot be erased, and it is visible to anyone who can see the screen or capture it. Background health checks read a session copy through `VaultSession.read` on a separate thread, which erases it afterwards, and publish only entry IDs and reasons; results from an older revision or session are discarded.

Compose and Swing text controls retain immutable strings, including temporary passwords and edited fields; this includes the password fields of the password prompts, whose text is dropped when the prompt closes and handed to the vault worker only as a char array that is erased after use. These cannot be reliably erased; owned input arrays and `Secret` instances are cleared. Explicit field copying clears the owned clipboard after 20 seconds by default and on lock. Expiry is checked every second with the same monotonic and wall-clock measurement as the inactivity deadline, so a value whose expiry passed during a suspend is cleared on the first check after resume. The expiry can be set to 5, 10, 20, 30, 60 or 120 seconds; the choice is saved in the settings file and applies again after a restart. Changing it clears a currently owned value. Clipboard access can be delayed by another application; clearing retries. Later clipboard owners are not intentionally cleared. OS clipboard history and clipboard managers can retain copies that Keyrook cannot remove.

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
distinct characters, passwords reused across different entries, passwords
unchanged for more than 365 days, and possible duplicate entries (same type, host
or URL and user name). Empty passwords are skipped. These are limited heuristics,
not an entropy estimate or a breached-password database; no breach lookup is made.
Custom fields are recognized by the names password, passwort and passphrase.
Reuse and duplicate comparisons use HMAC-SHA-256 with a fresh random key for each
inspection; host, URL and user name are normalized (lower case, without surrounding
blanks and trailing slashes) in temporary character arrays that are erased. Password
age compares the current and historical passwords through erased character copies
and uses only the stored timestamps (see [Warning list](DESKTOP.md#warning-list)
for how the date is chosen).
The key and comparison buffers are cleared afterward; only entry IDs and warning
categories are returned. Nothing is persisted or sent over a network.
Provider-internal key copies remain subject to the JVM memory limits above.

## TOTP codes

One-time codes implement the standard algorithms RFC 6238 (TOTP) on top of RFC 4226 (HOTP) over the JDK's established `javax.crypto.Mac` HMAC-SHA-1/SHA-256/SHA-512 implementations. This is not custom cryptography: no primitive is implemented, only the specified counter encoding and dynamic truncation. The tests use the RFC 4226 appendix D and RFC 6238 appendix B vectors for all three hash functions. The vault format is unchanged; the secret remains the stored web-login field.

The stored value is parsed strictly: RFC 4648 Base32 (case-insensitive, spaces and hyphens ignored, correct optional padding, zero trailing bits, 10 to 128 key bytes) or an `otpauth://totp/` URI with exactly one `secret` and at most one each of `algorithm` (SHA1, SHA256, SHA512), `digits` (6–8) and `period` (15–120 seconds). Parameters that cannot influence the code (`issuer`, `image`, `color`, provider-specific ones) are ignored but must still be well-formed `name=value` pairs. Other types (including counter-based `hotp`), repeated computation parameters, unsupported values, fragments, whitespace, malformed or non-ASCII escapes in the secret and inputs above 2048 characters are refused with one generic error that never repeats input. The label and ignored parameters do not influence the code. The editor blocks saving only a TOTP value entered or changed in that edit; an invalid value already stored (for example from an import) and left unchanged is flagged but kept, and it never produces a code. Parsing reads the secret through `Secret.useChars`; decoded key bytes, percent-decoded secret characters, the HMAC result and code digits live in owned arrays that are erased after one computation. No decoded key is kept between codes. The JDK's `SecretKeySpec` and HMAC provider state hold their own key copies, which, like other provider internals, remain subject to the JVM memory limits above.

A code is treated as a short-lived secret. The detail view computes and shows it only after an explicit **Anzeigen**; it is masked and its once-per-second refresh stops with every masking event listed under session behavior, including lock and window deactivation. The displayed code is an immutable UI string. Copy actions compute a fresh code and use the owned, expiring clipboard; the configured clipboard expiry applies even when it outlasts the code's period. Codes and the countdown rely on the local clock, which Keyrook does not verify. The editor validates the field with the same parser; the secret stays masked there unless the field's own hidden option is cleared. Anyone who can read the vault can generate codes, so a TOTP secret in the same vault as the password does not provide an independent second factor against compromise of that vault.

## Backups and transfer

An optional backup folder preserves the authenticated ciphertext of the previous saved revision before each update or password change. Backup failure prevents the vault replacement. Rotation keeps the latest configured versions plus daily representatives; only files matching this vault's exact managed naming pattern are eligible. Rotation runs only after the new backup was written and verified, and it is best effort: an old backup that cannot be deleted (for example because of folder permissions or a file held open by another program) stays in place and is counted, a folder that cannot be listed skips rotation, and neither fails the backup or the save that follows. The desktop shows a non-blocking notice with the number of backups left in place; the next backup retries rotation. Backup files are created exclusively with the same owner-only restriction as vault files: mode 0600 on POSIX and an ACL with a single owner entry on Windows, applied before any byte is written; a filesystem without either is refused. Vault files, backups, exports and the settings file share one implementation of this restriction and of the bounded file read. Backups are ciphertext, not plaintext exports. Their names reveal a random vault ID, revision and time. Choose a trusted, existing directory. Backup paths are resolved like the vault file: parent directories are canonicalized, so a symbolic link above the folder (for example macOS `/var`) is accepted, while a folder, vault file or backup file that is itself a symbolic link is refused and never followed. Locking clears the active backup setting; the folder and retention are remembered per vault file in the settings file and reapplied, with the same checks, only after that vault file is unlocked again. The restored folder and retention are always shown after unlocking (see [Saved settings](#saved-settings)).

Manual backups use the same authenticated copy, expected file stamp and rotation checks as automatic backups. They preserve the saved vault bytes and revision. Status reports the last successfully copied revision for the current session configuration; it does not assert that a backup remains available after external changes. Disabling backups requires confirmation, clears the active session configuration and status, and leaves existing files intact. The folder and retention stay in the settings file marked as disabled, so they are no longer applied after unlocking but remain visible to anyone who can read that file.

Moving an entry to the trash is reversible. Permanently deleting a trashed entry or emptying the trash is irreversible in the live vault: the next save removes the entry together with its history, clears references from other entries and erases the owned in-memory copies. Backups made before that save and any earlier plaintext exports still contain the purged entries, and backups keep their old credentials. To remove such data completely, delete or securely dispose of those files as well. Previously written file blocks, filesystem snapshots and OS caches may still hold older ciphertext; Keyrook does not overwrite storage in place.

**Integrity check** authenticates the saved vault file and every managed backup of the open vault in the configured folder with the current session credentials. It is read-only: files are opened for reading only and never locked, created, renamed, rewritten or deleted; each decrypted model is closed and each ciphertext buffer cleared after its file, and file names are shown without folder paths. Every file costs a full Argon2 derivation, and the check runs on the serial vault worker. Locking cancels it: the lock is checked before each file, so at most the file already in progress is finished before the check stops, discards its partial result and the queued lock erases the session. A backup written before a password or key-file change is reported as "cannot be authenticated with current credentials", because AES-GCM cannot distinguish a wrong key from modified or truncated ciphertext; such a file is never labelled intact or corrupt and can be checked with the restore preview and the credentials in use at that time. A successful check does not prove freshness against replay of an older authentic file.

A password change does not re-encrypt old backups: they still require their old password and key file. Backup preview authenticates contents before revealing entry counts; the displayed filesystem date is not authenticated. Restoration checks the preview digest again, refuses existing targets and creates a new vault file at revision zero with a new random vault ID. The restored file is therefore a separate vault: its backups use its own ID in their names, so rotation of the copy never deletes backups of the original in a shared folder and the integrity check never lists them as the original's. Settings are keyed by vault path, not by ID, so nothing else depends on the old ID. It never overwrites the open vault. Neither a valid backup nor its preview proves freshness against replay of an older authentic file.

Encrypted export writes the records as a separate vault with a new random vault ID at revision zero, for the same reason as restoration. Plaintext JSON/CSV export requires two separate UI confirmations. New export files receive private POSIX/Windows permissions before writing, refuse replacement and clear owned buffers on completion/failure. Filesystems without usable private permissions are refused. Files left after process termination, OS caches, backups and later copies cannot be reliably erased. Keyrook JSON/CSV preserves the full model and history; CSV wraps that JSON in a quoted record, not a password spreadsheet. Imported external formats are bounded and validated before an explicit merge confirmation; conflicting IDs reject the merge. XML rejects DTDs/external entities. Unsupported attachments, passkeys and protected plugin data are refused rather than silently dropped. See [DESKTOP.md](DESKTOP.md).

## Customer reports

Customer reports are built in core from a deep copy of the open vault that is erased afterwards. They read only
fields with a fixed, non-secret meaning (URLs, hosts, user names, e-mail addresses and mail servers, roles,
operating systems, SSH fingerprints, domain names and registrars), and only when that field is not marked hidden;
a hidden field is never read, not even to be masked. Passwords, TOTP secrets, private keys, passphrases, DNS
notes, entry notes, history and custom field values are never read, whatever their hidden option says. Entry
titles, tags, customer and project names, ports, protocols, key types and expiry dates are metadata and do
appear. The visible values that are read become immutable JVM strings, like the text shown in the detail view.
Trashed entries are left out. Tests close every hidden secret and the notes of a sample vault with marked values
for every entry type before building each report, so a report that read one would fail, and check that none of
the values appears. The **customer overview** is shown in a read-only plain-text dialog and is not written to disk.

The **handover sheet** is a self-contained HTML file for one customer. Every value, including titles, labels and
the language tag, is HTML-escaped; the page contains no links, scripts, forms or external references, and a
content security policy of `default-src 'none'` (inline styles only) keeps a browser from loading anything. Tests
mark every secret field of every entry type visible and check that none of its values appears, alongside the
hidden-field checks above. The file is written through the same exclusive, owner-only file creation as plaintext
exports (mode 0600 or an owner-only ACL, never replacing an existing file). It holds no secrets, but it does
reveal infrastructure: hosts, user names and domains of that customer. Share it only with the intended recipient.

## Saved settings

Preferences are stored in plaintext as `settings.json` in the platform configuration directory (see [DESKTOP.md](DESKTOP.md#saved-settings)). The file contains only a format version, the appearance, language, inactivity deadline, window lock choice, clipboard expiry, whether updates are checked on start, the main window's size, position and maximized state, the last opened vault path, per vault path the backup folder, retention counts and whether backups are enabled, and the last password generator choices (preset, length, character classes, ambiguity option, passphrase word count and separator, and the path of a chosen passphrase word list). It never contains passwords, generated values, word-list content, key-file paths or contents, entry data, vault titles or customer and project names. It is still metadata: anyone who can read it learns where vaults and their backups are stored. Key-file paths are deliberately not saved so the file does not reveal which file is the second factor; the key file must be selected again after each start. The file and a newly created directory receive owner-only permissions where POSIX permissions are supported, and Windows restricts the file to its owner through an ACL. Writes go to a private temporary file that atomically replaces the previous file. Symbolic links, oversized, malformed, unknown-version and out-of-range values fall back to defaults (window sizes are clamped to the allowed range, and a position on no connected screen is replaced by a centered one); file contents are never logged or displayed. The file is not authenticated. Anyone who can write it can point the unlock form or backups to another path, disable backups for a vault, or shrink retention (for example to one version and no daily representatives) so that rotation deletes older managed backups on the next save; they cannot read or change vault contents, because unlocking still requires the password and key file. They can also point the remembered passphrase word list to a list of their choice; it must still pass the same checks (1024–65536 distinct words, at least 60 bits of selection entropy), and its full path is shown next to the button that uses it. Generator presets and options only restrict the character set and length within the generator's fixed limits (at least 12 characters), and the word list is read with the same bounded, symbolic-link-refusing file read as imports. Mitigations: restored backup folders pass the same symbolic-link and existence checks as a manual selection, and after unlocking the restored folder and retention are always shown. If the restored retention keeps fewer backups than this vault currently has in that folder, it is not applied without explicit confirmation; declining leaves backups unconfigured for the session and says so. If the stored configuration is disabled while backups of this vault exist in its folder, a notice is shown. A folder that cannot be listed leaves backups unconfigured with a notice. These notices rely on the user noticing an unexpected folder or retention; they do not detect a redirected folder that holds no backups yet, and rotation can still delete backups that exceed the confirmed retention.

## Update check

Keyrook contacts the network itself only for the update check, and only when the user clicks **Nach Updates suchen** (**Check for updates**) in the About dialog or has enabled **Beim Start automatisch nach Updates suchen** (**Check for updates on start**) under **Sicherheit**. That option is off by default, missing or unknown stored values keep it off, and a change applies from the next start. Anyone who can write `settings.json` can enable it, which causes at most this request. Development builds without a release version send nothing.

Exactly what is sent, and to whom: one HTTPS `GET https://api.github.com/repos/kdg1992/keyrook/releases/latest` to GitHub, with only the headers `Accept: application/vnd.github+json` and `User-Agent: Keyrook/<version>` besides those HTTP itself requires (such as `Host`). There are no query parameters, cookies, credentials, installation or user identifiers, and nothing from the vault, the settings or the file system. The request uses Java's `java.net.http.HttpClient` with the system proxy settings (`ProxySelector.getDefault()`), 10-second connect and response timeouts, no cookie store, no authenticator and no redirects: any status other than 200 counts as a failure. GitHub, and any proxy in between, see the request, your IP address and the time of the check, as with any HTTPS connection; GitHub's own privacy terms apply to that.

The response is untrusted. At most 256 KiB are read; the body must be UTF-8 JSON whose `tag_name` is exactly `v<major>.<minor>.<patch>` (ASCII digits, no leading zeros) with `draft` and `prerelease` both literally `false`. Everything else is a generic "check failed" without error details. Versions are compared numerically with the running version. No address from the response is used: the release page link is built as `https://github.com/kdg1992/keyrook/releases/tag/v<X.Y.Z>` from the validated numbers and is opened, after the same checks as other browser links, only when the user clicks **Release-Seite öffnen**. Release notes are shown as plain text only: at most 4000 characters, control and invisible formatting characters (including bidirectional overrides) removed, with no HTML, Markdown or link interpretation. The check runs on its own background thread, independently of the vault state, and never reads vault data or credentials.

Nothing is downloaded or installed. Automatic installation is intentionally absent: the installers are unsigned, and the published checksums come from the same GitHub release as the installers, so an automatic updater could not establish more trust than the download itself. Updating remains a deliberate manual step (see [DESKTOP.md](DESKTOP.md#updates)).

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

Background searches scan a session copy through `VaultSession.read`, which is erased on completion or cancellation. Neither taking the copy nor scanning it converts secret values to strings. A changed query is scanned only after 200 ms without further typing, so a burst of keystrokes causes one scan. No persistent plaintext index is built. Secret field scans operate on temporary character arrays that are erased after use; current notes are searched by default, while hidden field values require an explicit option. Query text itself is an immutable UI string and may contain sensitive user input. Search results display entry metadata rather than matching secret excerpts. A search already running when locking occurs may retain its snapshot until the next cancellation check and cleanup.

Before an imported RSA key is used for a signature challenge, its public and private moduli must match and have 4096 bits. Public exponents must match, be odd, and fit within 32 bits; private exponents and CRT operands must be positive and at most 4096 bits. These bounds limit work on attacker-controlled parameters before provider-backed signing and verification. They supplement the signature consistency check rather than replace it.

## Build and verification

Build dependencies are fetched by Gradle; this is separate from the offline core runtime. Versions are pinned in the version catalog and dependency lockfile. The wrapper verifies the distribution SHA-256. Original upstream wrapper notices are preserved. Dependency licenses and roles are listed in [THIRD-PARTY-NOTICES](../THIRD-PARTY-NOTICES).

The tests cover RFC 9106 Argon2id, NIST AES-256-GCM vectors, RFC 4226 HOTP and RFC 6238 TOTP vectors, a frozen format fixture, all eight entry variants, optional key files, tampering, limits, version rejection, absence of sentinel plaintext, failed writes, conflicts, permissions, credential changes and explicit buffer lifecycle. These tests are evidence for specific behavior, not a cryptographic audit or proof of crash safety. A schema migration pipeline exists and is covered by the format migration test with a synthetic older schema; production registers no steps because v1 is the initial format.

## Format migrations

Migrations run only after successful authentication, on the decrypted JSON tree, and only for an older schema with a complete registered chain; newer, unknown or unbridged versions are rejected. Steps must keep the vault ID and revision, and the result is re-encoded and checked by the same JSON guard, decoder and schema validation as a current document. The migration path creates additional transient plaintext: the JSON tree holds immutable strings that cannot be erased, and the intermediate re-encoded bytes are wiped. Opening never rewrites the file; the next ordinary save writes the current format atomically, preceded by the configured automatic backup of the older ciphertext as for any save. Current-version files use the unchanged decoding path apart from a streaming read of `schemaVersion`.
