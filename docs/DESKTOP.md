# Desktop usage

Run `./gradlew :app:run` with JDK 25. The interface is available in German and English and supports light/dark themes; by default both follow the operating system (German for a German system language, English otherwise). Use test data while the application remains a development build.

## Keyboard navigation

Use **Ctrl** on Windows/Linux or **Command (⌘)** on macOS. The same list is
available in the application under **Tastenkürzel** next to the search field and
in the **Info** dialog.

| Shortcut | Action |
| --- | --- |
| Ctrl/⌘ + L | Lock immediately, including while vault work is running. |
| Ctrl/⌘ + N | Create an entry from the active entry list; unavailable in the trash, while editing or during work. |
| Ctrl/⌘ + F | Focus full-text search from the entry list. |
| ↓ (in the search field) | Move the focus from the search field into the entry list. |
| ↑ / ↓ | Select the previous/next entry (stops at the first and last entry). |
| Home / End (Pos1 / Ende) | Select the first/last entry. |
| Enter | Open the selected entry in the editor. |
| Ctrl/⌘ + E | Edit the selected entry. |
| Ctrl/⌘ + C | Copy the selected entry's password. |
| Ctrl/⌘ + B | Copy the selected entry's username. |
| Ctrl/⌘ + U | Open the selected entry's URL. |
| Delete (Entf); on macOS also ⌫ | Move the selected entry to the trash after the usual **In den Papierkorb verschieben?** confirmation. |
| Ctrl/⌘ + S | Validate and save the current editor through the same action as **Speichern**. |
| Escape | Request cancellation in an idle editor. Unsaved changes require **Verwerfen** confirmation. |
| Tab / Shift+Tab | Move between focusable controls. |

Editors initially focus the title. Repeating Escape in the discard confirmation closes that confirmation and preserves the draft. Save/cancel shortcuts are unavailable during generation or storage operations; new/search shortcuts never replace an open editor. Security locking still discards unsaved input immediately, as described below.

### Entry selection

The entry list always has a selected entry while it shows any: it is marked by a
colored border, a tint and a raised card, and is reported as selected to
accessibility tools. The border is thicker while the list has keyboard focus.
Clicking a card or moving the focus into one of its buttons selects it. The list
receives the focus when it is shown, so ↑/↓ work right after unlocking or
leaving the editor.

Typing a search or changing a filter selects the first result. When the vault
changes under the same search (for example after an entry was moved to the
trash), the selection stays on its entry or, if it left the list, moves to the
next remaining entry, otherwise to the previous one. The list scrolls to keep
the selection visible. A typical flow: Ctrl/⌘ + F, type part of the title,
↓, then Ctrl/⌘ + C.

**Text-field rule:** entry shortcuts (arrows, Home/End, Enter, Delete/⌫ and
Ctrl/⌘ + C/B/U/E) act only while the keyboard focus is in the entry list, that
is on the list itself or on a button of one of its cards. While any text field
has the focus, including the search field, every key keeps its normal editing
meaning; Ctrl/⌘ + C copies selected text as usual. The only exception is ↓ in
the single-line search field, which has no editing meaning there and moves into
the list. With the focus on a card button, Enter activates that button. Entry
shortcuts are ignored while work is running, a dialog is open or an editor is
shown, and edit/copy/open/trash are not available in the trash view (only
selection moves are). Global shortcuts (Ctrl/⌘ + L/N/F/S, Escape) behave as
before, regardless of the list focus.

The search text, the filters, the hidden-field search option and the selection
are kept while an entry is edited: after saving or canceling, the list shows
the same results with the same entry selected (or its neighbor, as described
above). Locking discards them together with the displayed vault.

## Layout and entry details

After unlocking, the header shows the warning summary (see
[Warning list](#warning-list)). Above the entry list, **Daten** (**Data**)
opens a menu with every backup, transfer and account action, grouped as
backups (**Backup-Ordner**, **Backupstatus**, **Sicherung jetzt**, **Backups
deaktivieren**, **Integrität prüfen**, **Backup wiederherstellen**), transfer
(**Verschlüsselt exportieren**, **Importieren**, **Klartext exportieren**) and
account (**Passwort / Schlüsseldatei ändern**, **Argon2-Einstellungen**,
**Schlüsseldatei erzeugen**). The actions behave as described in the sections
below. The menu is keyboard accessible: move the focus to **Daten** with Tab,
open it with Enter, move between actions with ↑/↓, run one with Enter or close
the menu with Escape. **Kunden und Projekte** next to it shows the customers and
projects section above the list until it is closed again.

When the content area is at least 900 dp wide, the entry list and the details
of the selected entry are shown side by side; narrower windows show the list
alone, as before, and entries are read through **Bearbeiten**. In the side-by-side
layout, the list's cards place their buttons below the text.

The detail view is read-only. It shows title, type, customer and project, tags,
creation and modification time, the expiry badge, password warnings, ports,
protocols and encryption of connection records, SSH key type and assigned
servers, the registrar login of domains, the number of saved history versions
and every field with its label, followed by the notes:

- Fields that are not masked are shown as text. Masked fields and the notes are
  shown as dots. **Anzeigen** (**Show**) displays one value in plain text until
  **Verbergen** (**Hide**) is pressed. Shown values are masked again as soon as
  another entry is selected, the entry is saved in a new version, the editor
  opens, the window switches to the side-by-side layout or back, the window
  loses the focus or is minimized (under every choice of
  [Sperren, wenn das Fenster…](#locking-and-current-boundaries)), and on lock.
  Selecting the entry again shows it masked.
- **Kopieren** (**Copy**) copies a value without showing it, with the same
  clipboard handling and expiry as the quick actions. **Öffnen** opens URL
  fields after the same link validation.
- Server records offer the SSH command and SFTP transfer records the SFTP
  command, built exactly as in the editor; the password is never part of it.
- **Bearbeiten** (**Edit**) opens the editor for the entry, which then uses the
  full window. Enter and Ctrl/⌘ + E in the list do the same.

Trashed entries show their metadata and unmasked fields only; restore them to
show or copy masked values.

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

**Kunden und Projekte** above the entry list opens the section for adding
customers and projects; **Kunden und Projekte ausblenden** or **Schließen**
closes it. There, existing customers and projects can be renamed.
Changing a project's customer moves all its entries, including trash, to that
customer in one save. Clearing only the project's customer preserves the entries'
individual customer assignments. Removal requires confirmation and is available
only when no entries (including trash) or projects still reference the item.

Selecting a project with a customer also selects that customer for the entry.
Web records can gain or remove a TOTP-secret field after import; removal asks
for confirmation and the saved previous value remains in history. History shows
its version timestamp and offers individual copy buttons while hidden fields
remain masked. Clipboard expiry applies to those copies too.

The editor checks inputs while typing and marks each affected field with its
own message: a missing or too long title (at most 4096 characters), more than
100 tags or a tag longer than 256 characters, field values or notes above the
field limit, an invalid expiry date, an empty or invalid port, and a duplicate
or too long custom field name. **Speichern** with open problems saves nothing
and shows one summary line above the buttons.

The expiry date accepts `2026-12-31`, `31.12.2026`, `1.2.2026` and `31.12.26`.
Two-digit years mean 2000–2099. Impossible dates such as `29.02.2027` are
refused. Below the field, a valid date is shown in the stored ISO form.
**+1 Jahr** sets the date one year after the entered date, or after today if no
valid date is entered (29 February becomes 28 February). **Löschen** removes the
expiry date.

Ports can be cleared and edited freely; only whole numbers from 1 to 65535 are
accepted. A port is required for transfer, server and each enabled mail
endpoint, since there is no implicit protocol default. The copy buttons for
SSH/SFTP commands are unavailable while the port is invalid, and an invalid
port draft counts as an unsaved change.

File dialogs offer a filter for the expected type and keep **All files**
available: vaults and encrypted exports (`*.keyrook`), backups
(`*.keyrook.bak`), key files (`*.key`), imports by chosen format (`*.json`,
`*.csv` or `*.xml`), plaintext exports (`*.json` or `*.csv`), SSH public keys
(`*.pub`) and passphrase wordlists (`*.txt`). Save dialogs suggest a file name
and add the filter's extension when it is missing; with **All files** selected
the name is used exactly as typed. If the resulting file already exists, the
dialog says so and stays open. Independently of this check, every export,
restore, key-file and vault creation writes only new files and never replaces
an existing one.

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
editor before the system browser receives them. Secret values are never shown in the
list. The copy and open shortcuts above use exactly these actions; when the
selected entry has no such field, a notice says so and nothing is copied.

Cards help to tell same-named entries apart: besides title, type and tags they
show the customer (direct or inherited from the project) and project, the
username, and the address where the type has one (URL for web and hosting-panel
records, host for transfer and server records, the first IMAP/POP3/SMTP host for
email, the domain name, and the first URL field of custom records). Only fields
that are not masked are shown; a masked username or host stays hidden in the
list. Long values are shortened. The expiry date is shown with a marker: expired
dates, and dates from today through the next 30 days (the same window as the
vault health check), are highlighted. Entries with a short or repetitive or a
reused password carry a marker naming that reason (see
[Warning list](#warning-list)).

## Backups and encrypted export

The actions in this and the next section, as well as **Passwort /
Schlüsseldatei ändern**, **Argon2-Einstellungen** and **Schlüsseldatei
erzeugen** after unlocking, are in the **Daten** menu above the entry list.

Select an existing **Backup-Ordner** after unlocking, then confirm how many recent versions (1–1000) and additional daily representatives (0–3660) to retain. The dialog starts with 30 versions and 30 daily representatives; zero disables daily retention. Both retention rules apply together. Canceling leaves the current backup configuration unchanged. The confirmed settings enable automatic backups and are remembered for this vault file; future backups can remove older managed backups outside those limits. Each backup preserves the previous saved revision before it is replaced. Locking clears the active configuration; after the same vault file is unlocked again, including after a restart, the remembered folder and retention are reapplied with the same folder checks. A notice then shows the restored folder and retention. If that retention would keep fewer backups than this vault already has in the folder, Keyrook asks before applying it; declining keeps backups off for the session. If backups are stored as disabled although backups of this vault exist in the folder, a notice says so. If the folder is no longer usable, the vault still opens without backups and a notice asks you to configure them again. Disabling backups stops this restoration for that vault file. A failed backup prevents the update; fix the folder access before retrying.

**Sicherung jetzt** authenticates and copies the currently saved vault to the configured backup folder, applying the same retention rules without changing the vault revision. **Backupstatus** shows whether backups are enabled and the last successfully backed-up revision for the current configuration. **Backups deaktivieren** requires confirmation and clears the session configuration without deleting existing backup files. Canceling preserves the configuration; locking clears it and its status.

**Backup wiederherstellen** requests the backup's own credentials and previews its authenticated entry count and revision. After confirmation, choose a new destination. The open vault is not overwritten; lock and open the restored file separately. Older backups retain older credentials after password changes.

**Integrität prüfen** authenticates the saved vault file and every managed backup of the open vault in the configured folder (the same `<vault-id>_<time>_<revision>_<uuid>.keyrook.bak` names that rotation manages) with the current session credentials, newest backup first. It is strictly read-only: no file is created, locked, rewritten, renamed or deleted. Paths are resolved like the vault file: linked parent directories are accepted, but a vault file, backup folder or backup file that is itself a symbolic link is never followed. File sizes use the same bounds as backups, and each decrypted model is wiped after its file. Each file needs a full key derivation, so the check can take a while; locking cancels it before the next file and no partial report is shown. Other files in the folder, including backups of other vaults, are ignored. Each file is reported as:

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

The vault health check looks at active entries for expiry within 30 days, expired
dates, short or repetitive passwords and reuse across entries. The checks run
locally and are limited heuristics; a password without a warning is not
guaranteed strong.

The check runs by itself after unlocking, after every change of the vault and
once a day, in the background on a copy of the vault that is erased afterwards;
the window stays responsive. Its result appears in three places:

- **Warnliste** (**Warnings**) in the header shows how many entries are
  affected per reason, for example *2 abgelaufen*, *1 laufen ab*, *3 schwach*,
  *2 mehrfach* (or *keine*; *wird geprüft* while the check runs). Expired entries
  are marked in the error color.
- List cards and the detail view mark entries with a short or repetitive or a
  reused password. The expiry badge on the cards covers expired and expiring
  entries. Markers name the reason only; no password or part of one is shown.
- Clicking **Warnliste** opens the list with a summary, the affected entry
  titles and their reasons. Clicking a title (or focusing it and pressing Enter)
  closes the list and selects that entry. If the current search or filters hide
  it, they are reset to all active entries (keeping the sort order) first.
  While an editor is open or work is running, titles cannot be selected.

## Locking and current boundaries

Use **Sicherheit** (**Security** in English) to choose the appearance (follow system, light or dark), the language (follow system, German or English), the inactivity deadline (default five minutes), when the window locks, clipboard expiry (default 20 seconds) and whether to check for updates on start (default off; see [Updates](#updates)). These preferences are saved and survive restarts.

**Sperren, wenn das Fenster…** (**Lock when the window…**) controls locking on window events and applies immediately:

| Choice | Locks on |
| --- | --- |
| **den Fokus verliert oder minimiert wird** (*loses focus or is minimized*) | Switching to another application and minimizing. Moving between Keyrook's own dialogs does not lock. |
| **minimiert wird** (*is minimized*, default) | Minimizing only. Copying a password, switching to the browser and coming back keeps the vault open. |
| **nie** (*never*) | Neither focus loss nor minimizing. |

Under every choice, supported operating-system session/sleep notifications (screen lock, user switch, sleep) trigger locking and the inactivity timer keeps running; notification coverage varies by platform. A suspend is also recognized from the system clock, so an unlocked vault locks right after resume even when no sleep notification arrives. The trade-off: with **minimiert wird** or **nie**, an unlocked vault stays unlocked behind other windows until the inactivity deadline, an OS lock or sleep event, or **Sperren**. Choose **den Fokus verliert oder minimiert wird** on shared or unattended machines. The window choice does not change clipboard expiry: copied values are still cleared after the configured seconds and on lock. See [SECURITY.md](SECURITY.md) for the limits of OS-event detection.

**Sperren** remains available during vault operations. Locking discards unsaved edits, clears the displayed snapshot and owned clipboard, and closes open application dialogs. Already-started atomic writes finish before the worker clears session credentials. Results from before the lock cannot reopen the display. Reopen the vault to check the saved state if locking happened during a save.

Failed unlock attempts produce increasing waiting periods, capped at 60 seconds. A countdown shows when the next attempt is available. Locking does not reset that delay; a successful unlock or application restart does. This is not protection against attacks on a copied vault file.

Do not treat the clipboard timer as protection against OS clipboard history. Native packaging and platform-specific end-to-end verification are separate from the local offscreen UI test.

## Updates

Keyrook never downloads or installs updates. To update manually, back up your
vault, download the installer for your platform from the
[release page](https://github.com/kdg1992/keyrook/releases), compare its SHA-256
with `SHA256SUMS.txt` and install it over the existing version as described in
[PACKAGING.md](PACKAGING.md#unsigned-installers). Vaults and `settings.json`
are separate files and remain in place.

**Nach Updates suchen** (**Check for updates**) in the **Info** (**About**)
dialog asks `api.github.com` once for the latest published release and reports
whether this version is up to date, whether a newer version is available (with
its release notes as plain text and a **Release-Seite öffnen** button) or that
the check failed. Development builds report that the check was skipped and
send nothing. The release page opens in the system browser only after that
click.

Under **Sicherheit**, **Beim Start automatisch nach Updates suchen** (**Check
for updates on start**) is off by default. When enabled, the same request is
sent once from the next start on, in the background and independently of
whether a vault is open; a notice appears only if a newer version exists and can
be dismissed. Without a click or this option Keyrook makes no network request.
The exact request and the reasons why automatic installation is intentionally
absent are described in [SECURITY.md](SECURITY.md#update-check).

## Saved settings

Keyrook stores non-secret preferences in `settings.json` in the platform configuration directory: `%APPDATA%\Keyrook` on Windows, `~/Library/Application Support/Keyrook` on macOS and `$XDG_CONFIG_HOME/keyrook` (or `~/.config/keyrook`) on Linux. The file contains the appearance, language, inactivity deadline, window lock choice, clipboard expiry, whether updates are checked on start, the main window's size, position and maximized state, the last opened vault path and per-vault backup settings (folder, retention, enabled). The unlock form is pre-filled with the last vault path. Passwords, key-file paths, key-file contents and vault contents are never saved there, so an optional key file must be selected again. If the file cannot be written, a notice appears and the changed preference applies until the application exits. A damaged, unknown or out-of-range file or value falls back to defaults; delete the file to reset all preferences. See [SECURITY.md](SECURITY.md#saved-settings).

### Window size and position

The main window reopens with the size, position and maximized state it had when it was last moved, resized or closed. Changes are written once the window has stayed unchanged for a moment, and again on closing; minimized and full-screen states are not recorded, and while the window is maximized the previous normal size and position are kept for restoring. At start the stored values are checked: sizes are kept between 720 × 520 and 16384 × 16384 dp and reduced to fit the screen, and a window whose title bar would not be visible on any currently connected screen (for example after disconnecting a monitor) opens centered on the main screen instead. The window cannot be made smaller than 720 × 520 dp. The first start, or a missing or damaged entry, uses 1100 × 760 dp centered on the main screen.

Desktop text uses German and English resource catalogs. The language choice
applies immediately without restarting or locking; messages already shown stay
in the language they were created in. User-supplied names, custom field
identifiers and persisted data are not translated, and protocol and file-format
names such as SSH, SFTP, IMAP, JSON or KeePass CSV stay as they are. The buttons
of standard system dialogs (file chooser, confirmation and input dialogs) are
provided by the Java runtime and follow its default locale (normally the
operating-system language), not the Keyrook language choice.

Settings files written before the language preference existed load unchanged
and use the system language. Files written before the update-check option
existed load with automatic update checks disabled. A settings file that contains the language is not
readable by earlier builds, which then fall back to their defaults.
