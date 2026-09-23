# Desktop usage

Run `./gradlew :app:run` with JDK 25. The interface is German and supports light/dark themes. Use test data while the application remains a development build.

## Keyboard navigation

Use **Ctrl** on Windows/Linux or **Command (⌘)** on macOS:

| Shortcut | Action |
| --- | --- |
| Ctrl/⌘ + L | Lock immediately, including while vault work is running. |
| Ctrl/⌘ + N | Create an entry from the active entry list; unavailable in the trash, while editing or during work. |
| Ctrl/⌘ + F | Focus full-text search from the entry list. |
| Ctrl/⌘ + S | Validate and save the current editor through the same action as **Speichern**. |
| Escape | Request cancellation in an idle editor. Unsaved changes require **Verwerfen** confirmation. |
| Tab / Shift+Tab | Move between focusable controls. |

Editors initially focus the title. Repeating Escape in the discard confirmation closes that confirmation and preserves the draft. Save/cancel shortcuts are unavailable during generation or storage operations; new/search shortcuts never replace an open editor. Security locking still discards unsaved input immediately, as described below.

## Vaults and entries

Choose an existing `.keyrook` file to open, or a new file to create. Creation requires the master password twice. The optional key file must contain exactly 32 bytes and must be available again when unlocking. Existing files are never replaced during creation.

Entries are saved immediately through authenticated, atomic vault storage. Fields can be masked independently. Web, transfer, email, hosting-panel, server, SSH, domain and custom records have their own editors. Customers/projects can be created and assigned. Entries can be duplicated, moved to the trash and restored. Editing retains up to 100 historical field snapshots. Search and filters narrow the visible list; history displays hidden fields masked. Canceling an edit discards that edit.

Full-text search includes current titles, tags, notes, field names and visible values, customer/project names and expiry dates. Space-separated terms must all match the same record, without case sensitivity. **Verborgene Felder durchsuchen** explicitly includes hidden current values; matching values are never exposed in result rows. History is excluded. Searches run in the background over an independently owned snapshot, are canceled when replaced or locked, and do not create a persistent plaintext index. Queries are limited to 256 characters.

The password generator supports 12–256 characters and selectable character classes. Passphrase generation accepts a user-supplied reviewed wordlist with at least 1024 distinct letter-only words; the chosen word count must provide at least 60 bits of selection entropy. No small demonstration wordlist is bundled.

SSH generation supports Ed25519 and RSA-4096 with a passphrase of at least 12 characters. Import accepts pasted OpenSSH or supported PEM private keys and re-encrypts them under the replacement passphrase. Public keys export as an `authorized_keys` line to a new file. PPK is not accepted; convert it to OpenSSH with a trusted tool first. No SSH connection is opened.

Server editors can copy an SSH connection command; SFTP records can copy an SFTP command. These are text for PowerShell or POSIX shells, not Windows Command Prompt. Only the host, port and username are included: no password, private key, start directory or remote command. The application never executes the command. Hostnames must be ASCII DNS names (use punycode for international names) or unscoped IPv4/IPv6 literals; usernames accept up to 64 ASCII letters/digits, underscores, periods and hyphens, with a letter, digit or underscore first. Unsupported forms are refused rather than inserted into shell text. Review the copied destination and your OpenSSH configuration before running it separately. Syntax follows the [OpenSSH SSH](https://man.openbsd.org/ssh.1) and [SFTP](https://man.openbsd.org/sftp.1) manuals.

Saved links open only after an explicit click. Only HTTP/HTTPS addresses with a
host are accepted; embedded usernames/passwords, control characters and invalid
ports are refused. Use ASCII/punycode hostnames. Opening a link hands it to the
system browser and may cause network requests there; Keyrook itself does not
resolve or fetch the address while validating it.

## Backups and encrypted export

Select an existing **Backup-Ordner** after unlocking, then confirm how many recent versions (1–1000) and additional daily representatives (0–3660) to retain. The dialog starts with 30 versions and 30 daily representatives; zero disables daily retention. Both retention rules apply together. Canceling leaves the current backup configuration unchanged. The confirmed settings enable automatic backups for this session; future backups can remove older managed backups outside those limits. Each backup preserves the previous saved revision before it is replaced. Locking clears the configuration. A failed backup prevents the update; fix the folder access before retrying.

**Backup wiederherstellen** requests the backup's own credentials and previews its authenticated entry count and revision. After confirmation, choose a new destination. The open vault is not overwritten; lock and open the restored file separately. Older backups retain older credentials after password changes.

**Verschlüsselt exportieren** creates a new `.keyrook` file with explicitly chosen credentials, preserving the records and resetting its revision to zero. It is the preferred transfer format.

## Import and plaintext export

Import parses and validates first, then asks for confirmation showing the entry count. It adds records to the current vault; duplicate IDs fail rather than overwrite records. Enable backups before importing into a valuable vault. Supported inputs:

- Keyrook JSON: all entry types, references, metadata and history.
- CSV: a header row and a user-selected mapping for title, URL, username, password and notes; empty optional mappings are allowed. KeePass-style column defaults are provided. Quoted commas, escaped quotes and multiline values are supported.
- Bitwarden unencrypted JSON: login and secure-note items, custom fields, folders, multiple URLs, dates and password history. Cards, identities, organization records, attachments, passkeys and password-reprompt restrictions are not imported.
- KeePass XML: exported plaintext strings, group paths, tags, ISO timestamps, expiry and history. Entries in the identified recycle bin, including nested groups, remain deleted; their last-modified time supplies the deletion timestamp because the export has no separate deletion date. KDBX, binary/attached data, protected values, custom plugin data and binary timestamps are not supported. DTDs, external entities and ambiguous recycle-bin/expiry metadata are refused.

The source export's immutable parser strings cannot be reliably wiped from JVM memory. Imported Bitwarden/KeePass fields are stored as custom records to preserve additional values. Unsupported structures produce a generic failure instead of exposing data in errors.

**Klartext exportieren** warns twice before writing. JSON is the full Keyrook model. Keyrook CSV uses a `keyrook-json` header and one quoted JSON record so every type, reference and history roundtrips without flattening losses. It is not intended for spreadsheet editing. Both include secrets and deleted/history records. Keep them private and use encrypted export when possible.

## Warning list

**Warnliste** checks active entries for expiry within 30 days, expired dates,
short or repetitive passwords and reuse across entries. It displays entry titles
and reasons without exposing passwords. The checks run locally and are limited
heuristics; a password without a warning is not guaranteed strong.

## Locking and current boundaries

Use **Sicherheit** to choose the inactivity deadline (default five minutes) and clipboard expiry (default 20 seconds). These preferences apply to the current application session. Switching to another application or minimizing Keyrook also locks it. Supported operating-system session/sleep notifications trigger locking; notification coverage varies by platform. The application additionally uses its own inactivity timer. See [SECURITY.md](SECURITY.md) for the limits of OS-event detection.

**Sperren** remains available during vault operations. Locking discards unsaved edits, clears the displayed snapshot and owned clipboard, and closes open application dialogs. Already-started atomic writes finish before the worker clears session credentials. Results from before the lock cannot reopen the display. Reopen the vault to check the saved state if locking happened during a save.

Failed unlock attempts produce increasing waiting periods, capped at 60 seconds. A countdown shows when the next attempt is available. Locking does not reset that delay; a successful unlock or application restart does. This is not protection against attacks on a copied vault file.

Backup configuration is session-local and cleared on lock. Do not treat the clipboard timer as protection against OS clipboard history. Native packaging and platform-specific end-to-end verification are separate from the local offscreen UI test.
