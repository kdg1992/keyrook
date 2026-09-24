# Desktop usage

Run `./gradlew :app:run` with JDK 25. The interface is German and supports light/dark themes; by default it follows the operating system. Use test data while the application remains a development build.

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

**Schlüsseldatei erzeugen** writes 32 cryptographically random bytes to a new
file with private permissions. Keep a separate safe copy; losing this factor
makes the vault unrecoverable. Generating a file does not change an existing
vault. **Passwort / Schlüsseldatei ändern** explicitly confirms replacement of
the selected factors: select the existing key file to retain it, another file
to replace it, or leave the key field empty to remove it. Older backups still
need their original factors.

Argon2 memory, iterations and parallelism can be selected when creating a vault
and changed through **Argon2-Einstellungen** after unlocking. Desktop limits
are 65536–262144 KiB, 1–5 iterations and 1–16 lanes; memory must be divisible by
four times the lane count. Changes re-encrypt atomically and create the configured
backup first. Vaults with higher derivation costs still require explicit approval
through the core API and cannot currently be opened in the desktop interface.

Entries are saved immediately through authenticated, atomic vault storage. Fields can be masked independently. Web, transfer, email, hosting-panel, server, SSH, domain and custom records have their own editors. Customers/projects can be created and assigned. Entries can be duplicated, moved to the trash, restored and permanently deleted (see below). Editing retains up to 100 historical field snapshots. Search and filters narrow the visible list; history displays hidden fields masked. Canceling an edit discards that edit.

Under **Kunden und Projekte**, existing customers and projects can be renamed.
Changing a project's customer moves all its entries, including trash, to that
customer in one save. Clearing only the project's customer preserves the entries'
individual customer assignments. Removal requires confirmation and is available
only when no entries (including trash) or projects still reference the item.

Selecting a project with a customer also selects that customer for the entry.
Web records can gain or remove a TOTP-secret field after import; removal asks
for confirmation and the saved previous value remains in history. History shows
its version timestamp and offers individual copy buttons while hidden fields
remain masked. Clipboard expiry applies to those copies too.

Full-text search includes current titles, tags, notes, field names and visible values, customer/project names and expiry dates. Space-separated terms must all match the same record, without case sensitivity. **Verborgene Felder durchsuchen** explicitly includes hidden current values; matching values are never exposed in result rows. History is excluded. Searches run in the background over an independently owned snapshot, are canceled when replaced or locked, and do not create a persistent plaintext index. Queries are limited to 256 characters.

Filter the list by type, customer, project, tag and expiry. Expiry options separate past dates, today through the next 30 days (inclusive), and records without an expiry date. Sort by title, latest modification or earliest expiry; undated records appear last in expiry order. Customer selection limits compatible projects. **Filter zurücksetzen** restores the active list, title order and default filters, and clears the search and hidden-field search option.

The password generator supports 12–256 characters and selectable character classes. Passphrase generation accepts a user-supplied reviewed UTF-8 wordlist, optionally with a BOM, up to 6.5 MB. It must contain 1024–65536 distinct letter-only words of 2–32 characters, one per line; surrounding whitespace and blank lines are ignored. Choose hyphens or spaces between generated words. The chosen word count must provide at least 60 bits of selection entropy: at least six words for lists below 4096 words, otherwise at least five. Invalid lists and insufficient word counts have separate messages. No small demonstration wordlist is bundled.

SSH generation supports Ed25519 and RSA-4096 with a passphrase of at least 12 characters. Import accepts pasted OpenSSH, supported PEM, and PuTTY PPK version 2/3 private keys and re-encrypts them under the replacement passphrase. PPK import verifies its MAC for both encrypted and unencrypted files before constructing the private key, then checks the public/private pair. Public keys export as a validated, canonical `authorized_keys` line to a new file; malformed key blobs, unsupported key sizes, options and additional lines are rejected. No SSH connection is opened.

PPK import supports `none` and `aes256-cbc`, UTF-8 comments, and bounded Argon2d/i/id derivation for version 3. Other key types, PPK version 1 and excessive derivation costs are refused. For files outside these limits, an alternative is a separately installed, trusted PuTTYgen used interactively:

1. Open PuTTYgen yourself and select **Load** to read the PPK, entering its passphrase when requested. Stop if loading or integrity verification fails.
2. Check the displayed key type and SHA-256 fingerprint against your known key. Keep a nonempty passphrase in both passphrase fields.
3. Choose **Conversions → Export OpenSSH key (force new file format)** and save to a new private location. This exports the private key; **Save public key** is a different operation.
4. Paste the exported encrypted OpenSSH text into Keyrook's SSH import, supply its passphrase and a replacement passphrase of at least 12 characters, then import. Compare the resulting fingerprint with PuTTYgen before saving the entry.

The [PuTTYgen manual](https://the.earth.li/~sgtatham/putty/0.85/htmldoc/Chapter8.html#puttygen-conversions) documents loading, passphrase handling and conversion. Keyrook does not install or launch PuTTYgen, send it secrets, or create conversion files. The exported key remains outside the vault under your control.

Server editors can copy an SSH connection command; SFTP records can copy an SFTP command. These are text for PowerShell or POSIX shells, not Windows Command Prompt. Only the host, port and username are included: no password, private key, start directory or remote command. The application never executes the command. Hostnames must be ASCII DNS names (use punycode for international names) or unscoped IPv4/IPv6 literals; usernames accept up to 64 ASCII letters/digits, underscores, periods and hyphens, with a letter, digit or underscore first. Unsupported forms are refused rather than inserted into shell text. Review the copied destination and your OpenSSH configuration before running it separately. Syntax follows the [OpenSSH SSH](https://man.openbsd.org/ssh.1) and [SFTP](https://man.openbsd.org/sftp.1) manuals.

Saved links open only after an explicit click. Only HTTP/HTTPS addresses with a
host are accepted; embedded usernames/passwords, control characters and invalid
ports are refused. Use ASCII/punycode hostnames. Opening a link hands it to the
system browser and may cause network requests there; Keyrook itself does not
resolve or fetch the address while validating it.

## Trash and quick actions

**In Papierkorb** asks for confirmation first. In that dialog, Enter confirms
and Escape cancels; with the focus on **Abbrechen**, Enter cancels. Trashed
entries can be restored at any time.

In the trash, **Endgültig löschen** removes a single entry and **Papierkorb
leeren** removes every trashed entry, including those hidden by the current
filters. Both show a confirmation stating that this cannot be undone. These
dialogs start with the focus on **Abbrechen**, so Enter alone never deletes;
Escape cancels. Permanent deletion removes the entry together with its complete
history from the vault document in one save, and erases the application-owned
decrypted copies of its values (see [SECURITY.md](SECURITY.md) for JVM limits). SSH server assignments and registrar-login links of
other entries that point to a deleted entry are cleared. Backups created before
the deletion still contain the entry and remain the only way to recover it;
backup retention can eventually remove those older backups.

Active list rows offer quick actions without opening the editor: copy the
username, copy the password (the passphrase for SSH keys, the first hidden field
for custom records) and open the URL of web and hosting-panel records (the first
URL field for custom records). Buttons appear only when the field has a value.
Copies use the same clipboard handling as the editor, including ownership
checks and the configured expiry. Links pass the same validation as in the
editor before the system browser receives them. Values are never shown in the
list.

## Backups and encrypted export

Select an existing **Backup-Ordner** after unlocking, then confirm how many recent versions (1–1000) and additional daily representatives (0–3660) to retain. The dialog starts with 30 versions and 30 daily representatives; zero disables daily retention. Both retention rules apply together. Canceling leaves the current backup configuration unchanged. The confirmed settings enable automatic backups and are remembered for this vault file; future backups can remove older managed backups outside those limits. Each backup preserves the previous saved revision before it is replaced. Locking clears the active configuration; after the same vault file is unlocked again, including after a restart, the remembered folder and retention are reapplied with the same folder checks. If the folder is no longer usable, the vault still opens without backups and a notice asks you to configure them again. Disabling backups stops this restoration for that vault file. A failed backup prevents the update; fix the folder access before retrying.

**Sicherung jetzt** authenticates and copies the currently saved vault to the configured backup folder, applying the same retention rules without changing the vault revision. **Backupstatus** shows whether backups are enabled and the last successfully backed-up revision for the current configuration. **Backups deaktivieren** requires confirmation and clears the session configuration without deleting existing backup files. Canceling preserves the configuration; locking clears it and its status.

**Backup wiederherstellen** requests the backup's own credentials and previews its authenticated entry count and revision. After confirmation, choose a new destination. The open vault is not overwritten; lock and open the restored file separately. Older backups retain older credentials after password changes.

**Integrität prüfen** authenticates the saved vault file and every managed backup of the open vault in the configured folder (the same `<vault-id>_<time>_<revision>_<uuid>.keyrook.bak` names that rotation manages) with the current session credentials, newest backup first. It is strictly read-only: no file is created, locked, rewritten, renamed or deleted. Symbolic links are never followed, file sizes use the same bounds as backups, and each decrypted model is wiped after its file. Other files in the folder, including backups of other vaults, are ignored. Each file is reported as:

- *in Ordnung*: authenticated, with revision, entry count (including trash) and file date, as in the restore preview.
- *kann mit den aktuellen Zugangsdaten nicht authentifiziert werden*: the AES-GCM tag does not verify. This is expected for backups written before a password or key-file change, but it is also what a tampered or truncated file produces. Authenticated encryption cannot distinguish a wrong key from modified ciphertext, so Keyrook does not guess and never labels such a file intact or corrupt. Check old backups with **Backup wiederherstellen** and the credentials in use at the time.
- *beschädigt*: invalid header, size outside the bounds or invalid authenticated content.
- *authentisch, passt aber nicht*: the content authenticates but its vault ID or revision differs from the file name, or the vault file differs from the opened revision (renamed, replaced or changed outside this session).
- *nicht lesbar*: not a regular file, a symbolic link or a read error. A missing or unreadable backup folder is reported separately.

Without a configured backup folder only the vault file is checked. The report shows file names only, never folder paths or error details.

**Verschlüsselt exportieren** creates a new `.keyrook` file with explicitly chosen credentials, preserving the records and resetting its revision to zero. It is the preferred transfer format.

## Import and plaintext export

Import parses and validates first, then asks for confirmation showing the entry count. It adds records to the current vault; duplicate IDs fail rather than overwrite records. Enable backups before importing into a valuable vault. Supported inputs:

- Keyrook JSON: all entry types, references, metadata and history.
- CSV: select the actual header names from dropdowns for title, URL, username, password and notes. The title column is required; optional fields can remain unassigned. Common German and English column names are suggested. Quoted commas, escaped quotes and multiline values are supported. Headers must be unique and nonblank, with at most 100 columns and 512 characters per name. A UTF-8 BOM is accepted. Keyrook's own CSV format is recognized automatically without a mapping dialog.
- KeePass CSV: a fixed mapping without a dialog, using the same CSV parser and limits. The header is checked before any row is read and must contain exactly one of these column sets, in any order; otherwise nothing is imported and a message names the expected columns:

  | KeePass CSV 1.x (KeePass 2.x *Export → KeePass CSV (1.x)*) | KeePass 2.x field names | Keyrook field |
  | --- | --- | --- |
  | Account | Title | Title |
  | Login Name | UserName | Username (hidden) |
  | Password | Password | Password (hidden) |
  | Web Site | URL | URL |
  | Comments | Notes | Notes |

  The KeePass CSV 1.x format quotes every field and escapes quotes as `\"` and backslashes as `\\`; these escapes are decoded, and any other backslash sequence is refused. The field-name variant uses ordinary CSV quoting (doubled quotes). Additional columns such as groups, TOTP or timestamps are refused rather than silently dropped; use **CSV mit Feldzuordnung** for such files. Each row becomes a web login entry.
- Bitwarden unencrypted JSON: login and secure-note items, custom fields, folders, multiple URLs, dates and password history. Cards, identities, organization records, attachments, passkeys and password-reprompt restrictions are not imported.
- KeePass XML: exported plaintext strings, group paths, tags, ISO timestamps, expiry and history. Entries in the identified recycle bin, including nested groups, remain deleted; their last-modified time supplies the deletion timestamp because the export has no separate deletion date. KDBX, binary/attached data, protected values, custom plugin data and binary timestamps are not supported. DTDs, external entities and ambiguous recycle-bin/expiry metadata are refused.

The source export's immutable parser strings cannot be reliably wiped from JVM memory. Imported Bitwarden/KeePass fields are stored as custom records to preserve additional values. Unsupported structures produce a generic failure instead of exposing data in errors.

The CSV mapping dialog shows column names only, rendered as plain text. It does
not preview row values. Canceling or locking while choosing a mapping aborts the
import and clears the owned input buffer. Unassigned source columns are omitted;
check the mapping before confirming the import.

**Klartext exportieren** warns twice before writing. JSON is the full Keyrook model. Keyrook CSV uses a `keyrook-json` header and one quoted JSON record so every type, reference and history roundtrips without flattening losses. It is not intended for spreadsheet editing. Both include secrets and deleted/history records. Keep them private and use encrypted export when possible.

## Warning list

**Warnliste** checks active entries for expiry within 30 days, expired dates,
short or repetitive passwords and reuse across entries. It displays entry titles
and reasons without exposing passwords. The checks run locally and are limited
heuristics; a password without a warning is not guaranteed strong.

## Locking and current boundaries

Use **Sicherheit** to choose the appearance (follow system, light or dark), the inactivity deadline (default five minutes) and clipboard expiry (default 20 seconds). These preferences are saved and survive restarts. Switching to another application or minimizing Keyrook also locks it. Supported operating-system session/sleep notifications trigger locking; notification coverage varies by platform. The application additionally uses its own inactivity timer. See [SECURITY.md](SECURITY.md) for the limits of OS-event detection.

**Sperren** remains available during vault operations. Locking discards unsaved edits, clears the displayed snapshot and owned clipboard, and closes open application dialogs. Already-started atomic writes finish before the worker clears session credentials. Results from before the lock cannot reopen the display. Reopen the vault to check the saved state if locking happened during a save.

Failed unlock attempts produce increasing waiting periods, capped at 60 seconds. A countdown shows when the next attempt is available. Locking does not reset that delay; a successful unlock or application restart does. This is not protection against attacks on a copied vault file.

Do not treat the clipboard timer as protection against OS clipboard history. Native packaging and platform-specific end-to-end verification are separate from the local offscreen UI test.

## Saved settings

Keyrook stores non-secret preferences in `settings.json` in the platform configuration directory: `%APPDATA%\Keyrook` on Windows, `~/Library/Application Support/Keyrook` on macOS and `$XDG_CONFIG_HOME/keyrook` (or `~/.config/keyrook`) on Linux. The file contains the appearance, inactivity deadline, clipboard expiry, the last opened vault path and per-vault backup settings (folder, retention, enabled). The unlock form is pre-filled with the last vault path. Passwords, key-file paths, key-file contents and vault contents are never saved there, so an optional key file must be selected again. If the file cannot be written, a notice appears and the changed preference applies until the application exits. A damaged, unknown or out-of-range file or value falls back to defaults; delete the file to reset all preferences. See [SECURITY.md](SECURITY.md#saved-settings).

Desktop text uses German and English resource catalogs. German remains the
product language; a language selector is not yet exposed. User-supplied names,
custom field identifiers and persisted data are not translated.
