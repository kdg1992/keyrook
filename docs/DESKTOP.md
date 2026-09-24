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
| Ctrl/⌘ + T | Copy the current TOTP code of the selected web login (see [TOTP codes](#totp-codes)). |
| Ctrl/⌘ + U | Open the selected entry's URL. |
| Delete (Entf); on macOS also ⌫ | Move the marked entries, or without marks the selected entry, to the trash after the usual **In den Papierkorb verschieben?** confirmation. |
| Space (Leertaste) | Mark or unmark the selected entry for a bulk action (see [Bulk actions](#bulk-actions)); also in the trash. |
| Ctrl/⌘ + A | Mark every listed entry; pressed again while all are marked, it clears the marks. |
| Ctrl/⌘ + S | Validate and save the current editor through the same action as **Speichern**. |
| Escape | Request cancellation in an idle editor. Unsaved changes require **Verwerfen** confirmation. |
| Tab / Shift+Tab | Move between focusable controls. |

Editors initially focus the title. The discard confirmation starts with the focus on **Weiter bearbeiten**, so Enter alone keeps the draft; repeating Escape closes that confirmation and preserves the draft. Save/cancel shortcuts are unavailable during generation or storage operations; new/search shortcuts never replace an open editor. Security locking still discards unsaved input immediately, as described below.

In the unlock form and in dialogs with text fields, Enter in a field submits the
form through the same checks as its main button; a disabled button means Enter
does nothing. Dialogs focus their first field, and a repeated password that does
not match is pointed out below the field.

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

**Text-field rule:** entry shortcuts (arrows, Home/End, Enter, Delete/⌫, Space and
Ctrl/⌘ + C/B/T/U/E/A) act only while the keyboard focus is in the entry list, that
is on the list itself or on a button of one of its cards. While any text field
has the focus, including the search field, every key keeps its normal editing
meaning; Ctrl/⌘ + C copies selected text as usual. The only exception is ↓ in
the single-line search field, which has no editing meaning there and moves into
the list. With the focus on a card button, Enter activates that button. Entry
shortcuts are ignored while work is running, a dialog is open or an editor is
shown, and edit/copy/open/trash are not available in the trash view (only
selection moves and marking are). Global shortcuts (Ctrl/⌘ + L/N/F/S, Escape) behave as
before, regardless of the list focus.

The search text, the filters, the hidden-field search option and the selection
are kept while an entry is edited: after saving or canceling, the list shows
the same results with the same entry selected (or its neighbor, as described
above). Locking discards them together with the displayed vault.

## Layout and entry details

After unlocking, the header shows the warning summary (see
[Warning list](#warning-list)). Above the entry list, **Daten** (**Data**)
opens a menu with every backup, transfer and account action, grouped as
backups (**Backup-Ordner**, **Backup-Status**, **Jetzt sichern**, **Backups
deaktivieren**, **Integrität prüfen**, **Backup wiederherstellen**), transfer
(**Verschlüsselt exportieren**, **Importieren**, **Klartext exportieren**) and
account (**Passwort / Schlüsseldatei ändern**, **Argon2-Einstellungen**,
**Schlüsseldatei erzeugen**). The actions behave as described in the sections
below. The menu is keyboard accessible: move the focus to **Daten** with Tab,
open it with Enter, move between actions with ↑/↓, run one with Enter or close
the menu with Escape. **Kunden, Projekte und Vorlagen** next to it shows the customers,
projects and templates section above the list until it is closed again.

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

### TOTP codes

Web logins with a TOTP secret produce time-based one-time codes (RFC 6238).
The secret field accepts either a Base32 secret, as shown by most services
under "enter the key manually" (upper or lower case, spaces and hyphens are
ignored, `=` padding optional; SHA-1, 6 digits, 30 seconds), or an
`otpauth://totp/…` URI from a QR code with `secret` and optionally
`algorithm` (SHA1, SHA256, SHA512), `digits` (6–8) and `period` (15–120
seconds). Other parameters that do not affect the code, such as `issuer`,
`image`, `color` or provider-specific ones kept by Bitwarden or KeePass
imports, are ignored, as is the label. Other URI types, a missing or invalid
secret, a repeated `secret`, `algorithm`, `digits` or `period`, unsupported
values and anything else are refused; the editor shows **Ungültiges
TOTP-Secret** below the field together with a hint on these formats. A value
entered or changed in the current edit must be corrected, emptied or removed
before the entry can be saved. A value that was already stored (for example
from an import) and is left unchanged only shows the message: the entry stays
saveable, and the detail view and quick action keep reporting the secret as
invalid until it is fixed.

- The detail view shows **TOTP-Code** masked. **Anzeigen** shows the current
  code with the remaining seconds and a bar; it is refreshed every second and
  replaced at the end of each period while shown. It is masked again under the
  same rules as other values (selection, saved version, editor, narrow window,
  window deactivation or minimization, lock), which also stops the refresh.
- **Kopieren** next to it, the list's **TOTP-Code kopieren** quick action and
  Ctrl/⌘ + T copy a freshly computed code, never the secret, through the
  owned, expiring clipboard; the notice names how many seconds the copied code
  stays valid. The quick action appears only for a valid secret; Ctrl/⌘ + T on
  an entry with an invalid one names the problem instead.
- Codes depend on the computer's clock. If a service rejects them, check the
  system time and time zone synchronization.

## Vaults and entries

Choose an existing `.keyrook` file to open, or a new file to create. Creation requires the master password twice. The optional key file must contain exactly 32 bytes and must be available again when unlocking. Existing files are never replaced during creation.

**Schlüsseldatei erzeugen** writes 32 cryptographically random bytes to a new
file with private permissions. Like a vault file, it may be placed in a folder reached
through a symbolic link, but the chosen file name must not be a link itself. Keep a separate safe copy; losing this factor
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

**Kunden, Projekte und Vorlagen** above the entry list opens the section for
adding customers and projects; **Kunden, Projekte und Vorlagen ausblenden** or
**Schließen** closes it. The new-customer field has the focus, and Enter in a
name field adds the customer or project. Names are trimmed and have at most
4,096 characters. There, existing customers and projects can be edited:

- A customer has a name and optional **Ansprechpartner** (*Contact person*),
  **E-Mail**, **Telefon** (*Phone*) and **Website**. Values are trimmed and an
  empty value is stored as unset. The checks are lenient but bounded: an e-mail
  address needs one `@` and a domain with a dot (at most 254 characters), a phone
  number consists of digits, spaces and `+ ( ) . / -` (at most 64), a website has
  no spaces and, if it names a scheme, uses `http://` or `https://` (at most
  2,048); the contact person has at most 256 characters. Invalid fields are
  marked and **Speichern** stays disabled.
- A project has a name, a customer and an optional **Beschreibung**
  (*Description*, at most 4,096 characters, may span several lines).
- Customers and projects both have **Notizen (vertraulich)** (*Notes
  (confidential)*). Notes are encrypted and erased like entry notes and never
  appear in the customer overview, the handover sheet or the expiry export. They
  are shown as plain text only in the edit dialog while it is open.

Contact details and project descriptions are plain metadata and appear in the
[customer overview](#customer-reports).
Changing a project's customer moves all its entries, including trash, and the
templates that preset that project, to that customer in one save. Clearing only
the project's customer preserves the entries' individual customer assignments.
Deleting (**Löschen …**) asks in a confirmation that starts with the focus on
**Abbrechen** and is available only when no entries (including trash) or
projects still reference the item; templates that preset the deleted customer
or project keep their other settings and lose only that preset. The edit and
confirmation dialogs close only after the change was saved; when it is refused,
they stay open with their input and the reason is shown (for example an empty
name or an item that is still in use).

Selecting a project with a customer also selects that customer for the entry.
Web records can gain or remove a TOTP-secret field after import; removal asks
for confirmation and the saved previous value remains in history. History shows
its version timestamp and offers individual copy buttons while hidden fields
remain masked. Clipboard expiry applies to those copies too.

The editor checks inputs while typing and marks each affected field with its
own message: a missing or too long title (at most 4096 characters), more than
100 tags or a tag longer than 256 characters, field values or notes above the
field limit, an invalid expiry date, an empty or invalid port, a changed TOTP
secret the code generator does not accept (an unchanged stored one only warns;
see [TOTP codes](#totp-codes)), and a
duplicate or too long custom field name. **Speichern** with open problems saves nothing
and shows one summary line above the buttons. A second **Speichern** while a save is
still running is ignored, and the values of that second attempt are erased at once.

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

Filter the list by type, customer, project, tag and expiry, with **Nur Favoriten** (**Favorites only**) to [favorites](#favorites), and with **Zuletzt verwendet (diese Sitzung)** (**Recently used (this session)**) to [recently used entries](#recently-used-entries). Expiry options separate past dates, today through the next 30 days (inclusive), and records without an expiry date. Sort by title, latest modification or earliest expiry; undated records appear last in expiry order. Customer selection limits compatible projects. **Filter zurücksetzen** restores the active list, title order and default filters, and clears the search and hidden-field search option.

The password generator supports 12–256 characters and selectable character classes. It uses `SecureRandom` with rejection sampling, so each selected class occurs at least once and every such password is equally likely. Three presets are offered: **Standard** (all symbols `!@#$%^&*()-_=+[]{}:,.?`, 12–256 characters), **Shell/FTP-sicher** (**Shell/FTP-safe**; symbols limited to `-`, `_` and `.`, so quotes, backslash, `$`, backtick, `!`, `&`, `;`, `|`, `<`, `>`, brackets and braces, `*`, `?`, `~`, `#`, `%`, space, `=`, `+`, `/`, `:`, `@`, `,` and `^` never occur and the password can be pasted into shells, URLs, FTP/SFTP clients and configuration files without quoting) and **Max. 16 Zeichen** (**Max 16 characters**; all symbols, 12–16 characters, for legacy panels that truncate or reject longer passwords). Switching to a preset whose maximum is below the entered length sets the length to that maximum; longer lengths are refused rather than cut. **Keine verwechselbaren Zeichen** (**No ambiguous characters**) combines with every preset and leaves out `0 O o 1 l I | 5 S 2 Z 8 B`, which reduces the character set and therefore the strength per character; choose a longer length where the target allows it. The last successfully used preset, length, character classes and ambiguity choice are remembered in the settings file. Passphrase generation accepts a user-supplied reviewed UTF-8 wordlist, optionally with a BOM, up to 6.5 MB. It must contain 1024–65536 distinct letter-only words of 2–32 characters, one per line; surrounding whitespace and blank lines are ignored. Choose hyphens or spaces between generated words. The chosen word count must provide at least 60 bits of selection entropy: at least six words for lists below 4096 words, otherwise at least five. Invalid lists and insufficient word counts have separate messages. No small demonstration wordlist is bundled. After a passphrase was generated from a chosen list, its path, the word count and the separator are remembered in the settings file, and **Passphrase mit gespeicherter Wortliste erzeugen** (**Generate passphrase with remembered word list**) reads and checks the list again with the same rules and limits; the full path is shown above it. The list content is never stored. If the remembered file is missing, a symbolic link or larger than 6.5 MB at start, the generator silently offers the normal file selection instead; if it fails the checks when used, a short notice asks for a word list and the path is forgotten. **Wortliste vergessen** (**Forget word list**) removes the path.

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

### Templates

A template presets a new entry: its type, the field layout (which fields a web
login or mailbox has, the names of custom fields, which fields are masked and
which custom fields are links), default tags, and optionally a customer and a
project. It never holds a value, password, note, port or reference to another
entry.

- **Als Vorlage speichern** (*Save as template*) on an active list card or in
  the detail view asks for a name (the entry title is suggested) and saves the
  entry's layout as a new template. The entry is not changed.
- **Aus Vorlage …** (*From template …*) next to **Neuer Eintrag** appears when
  the vault has templates. It lists them by name with their type; choosing one
  opens the editor for a new entry titled *Neuer Eintrag aus Vorlage „…“* with
  the template's type, fields, tags, customer and project. All values start
  empty; ports, protocol and key type take the usual defaults. Choosing another
  type in the editor starts that type blank. Canceling an unchanged new entry
  asks nothing.
- Templates are listed at the end of the **Kunden, Projekte und Vorlagen** section as
  **Vorlage löschen: …** (*Delete template: …*); deleting asks for confirmation
  and does not change entries created from the template.

Templates are stored in the encrypted vault, count toward its revisions and are
included in encrypted and Keyrook JSON/CSV exports and imports.

## Trash and quick actions

**In den Papierkorb** asks for confirmation first. In that dialog, Enter confirms
and Escape cancels; with the focus on **Abbrechen**, Enter cancels. Trashed
entries can be restored at any time. Moving to the trash and restoring record the
current time as the entry's change time, but never an earlier time than its last
change, so a computer clock that was set back cannot make a change appear older.

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

### Bulk actions

Every card has a checkbox that marks the entry for a bulk action, independently
of the selected entry. Space marks or unmarks the selected entry and Ctrl/⌘ + A
marks every listed entry. While the list shows entries, a row above it offers
**Alle auswählen** (**Select all**) or **Auswahl aufheben** (**Clear
selection**), and with marks the number of marked entries and these actions:

- **Ausgewählte in den Papierkorb** (**Move selected to trash**), in the active list:
  the **In den Papierkorb verschieben?** confirmation names the number of
  entries. Delete (⌫ on macOS) does the same while entries are marked.
- **Ausgewählte wiederherstellen** (**Restore selected**), in the trash.
- **Tag hinzufügen** (**Add tag**) asks for one tag and adds it to every marked
  entry that does not have it yet. The tag is trimmed; it must not be empty,
  longer than 256 characters or contain a comma, which separates tags in the
  editor.
- **Tag entfernen** (**Remove tag**) offers the tags of the marked entries and
  removes the chosen one from each of them.
- **Als Favoriten markieren** / **Favoritenmarkierung aufheben** (**Mark as
  favorites** / **Unmark as favorites**), in the active list, set or clear the
  [favorite](#favorites) mark of every marked entry.

Each bulk action is saved as one change of the vault (one revision) and
applies to all marked entries or, if any of them cannot be changed (for
example because an entry would exceed 100 tags), to none. When no entry
changes, for example because every marked entry already has the tag, nothing
is saved. Changed entries record the current time as their change time, never
earlier than their last change, as for a single entry. Tag changes do not add
history versions, which hold field values only.

Marks belong to the current search and filters: a new search or filter clears
them, and entries that leave the list (for example after moving to the trash)
lose their mark, so an action never reaches an entry that is not shown. Locking
discards them. Values are never read for marking.

### Favorites

The star (☆/★) before the title of an active list card and in the detail view
marks an entry as a favorite or removes the mark; screen readers announce it as
**Als Favorit markieren** or **Favorit entfernen**. **Nur Favoriten** above the
list shows only favorites, also in the trash, where the star is not offered.
Several entries can be marked at once with the [bulk actions](#bulk-actions).
Changing the mark is saved like any other change: it takes one vault revision
and records the change time, so it moves the entry in the **Zuletzt geändert**
order and masks values shown in the detail view.

A favorite is stored in the entry's own `pinned` field (document schema 2, see
[FORMAT.md](FORMAT.md)); it is not a tag and does not count toward the 100 tags
per entry. Earlier releases stored it as the tag `keyrook:favorite`; opening such
a vault converts every such tag into the mark, and the next save writes the new
format. Schema 2 reserves that tag name (compared exactly, case-sensitive;
ordinary tags such as `favorite` or `Favorit` are unaffected): a typed
`keyrook:favorite` is dropped in the editor and refused by **Tag hinzufügen**,
and the full-text search does not match favorites by that name.

The favorite mark is kept by saves, backups, **Verschlüsselt exportieren**, and
the plaintext Keyrook JSON and Keyrook CSV exports (as `"pinned": true`) and
their imports. Importing a Keyrook JSON or CSV export of an earlier release, or
any import whose tags contain `keyrook:favorite` (KeePass XML tags, the tag
column of a mapped CSV), marks the entry as a favorite instead of keeping the
tag. Bitwarden favorites become Keyrook favorites. A mapped CSV can also assign a
favorite column (`true`/`false`, `1`/`0`, `yes`/`no`, `ja`/`nein`, `x`/empty;
other values reject the import). KeePass CSV imports no favorites.

### Recently used entries

**Zuletzt verwendet (diese Sitzung)** (**Recently used (this session)**) above
the list shows the up to ten entries most recently used in this session, the
latest first, instead of the chosen sort order. An entry counts as used when it
is opened in the editor (**Bearbeiten**, Enter, Ctrl/⌘ + E), when one of its
values is copied from the list, the detail view or a shortcut (including the
TOTP code), and when its link is opened. Using an entry again moves it to the
front without listing it twice. The other filters and the search still apply,
so the trash view shows recently used trashed entries.

The list is kept in memory only: it is never saved, locking empties it, and it
is not carried over to another vault or a restart. See
[SECURITY.md](SECURITY.md#session-behavior).

Cards help to tell same-named entries apart: besides title, type and tags they
show the customer (direct or inherited from the project) and project, the
username, and the address where the type has one (URL for web and hosting-panel
records, host for transfer and server records, the first IMAP/POP3/SMTP host for
email, the domain name, and the first URL field of custom records). Only fields
that are not masked are shown; a masked username or host stays hidden in the
list. Long values are shortened. The expiry date is shown with a marker: expired
dates, and dates from today through the next 30 days (the same window as the
vault health check), are highlighted. Entries with a short or repetitive, a
reused or an old password, or one found by a breach check, carry a marker naming
that reason (see [Warning list](#warning-list)).

## Backups and encrypted export

The actions in this and the next section, as well as **Passwort /
Schlüsseldatei ändern**, **Argon2-Einstellungen** and **Schlüsseldatei
erzeugen** after unlocking, are in the **Daten** menu above the entry list.

Select an existing **Backup-Ordner** after unlocking, then confirm how many recent versions (1–1000) and additional daily representatives (0–3660) to retain. The dialog starts with 30 versions and 30 daily representatives; zero disables daily retention. Both retention rules apply together. Canceling leaves the current backup configuration unchanged. The confirmed settings enable automatic backups and are remembered for this vault file; future backups can remove older managed backups outside those limits. Each backup preserves the previous saved revision before it is replaced. Locking clears the active configuration; after the same vault file is unlocked again, including after a restart, the remembered folder and retention are reapplied with the same folder checks. A notice then shows the restored folder and retention. If that retention would keep fewer backups than this vault already has in the folder, Keyrook asks before applying it; declining keeps backups off for the session. If backups are stored as disabled although backups of this vault exist in the folder, a notice says so. If the folder is no longer usable, the vault still opens without backups and a notice asks you to configure them again. Disabling backups stops this restoration for that vault file. A failed backup prevents the update; fix the folder access before retrying. Removing old backups is different: it happens only after the new backup was written and checked, and if some old backups cannot be deleted (for example because of folder permissions or another program holding them open), the save still succeeds and a notice says how many were left in place. The next backup tries again. Backup files are readable only by your user account: mode 0600 on macOS and Linux and an owner-only ACL on Windows, as for vault files and exports.

**Jetzt sichern** authenticates and copies the currently saved vault to the configured backup folder, applying the same retention rules without changing the vault revision. **Backup-Status** shows whether backups are enabled and the last successfully backed-up revision for the current configuration. **Backups deaktivieren** requires confirmation and clears the session configuration without deleting existing backup files. Canceling preserves the configuration; locking clears it and its status.

**Backup wiederherstellen** requests the backup's own credentials and previews its authenticated entry count and revision. After confirmation, choose a new destination. The open vault is not overwritten; lock and open the restored file separately. The restored file is a separate vault with its own new vault ID and revision zero, so its backups have their own names even in the same backup folder: retention for the copy never removes the original's backups, and **Integrität prüfen** lists only the open vault's own backups. Older backups retain older credentials after password changes.

**Integrität prüfen** authenticates the saved vault file and every managed backup of the open vault in the configured folder (the same `<vault-id>_<time>_<revision>_<uuid>.keyrook.bak` names that rotation manages) with the current session credentials, newest backup first. It is strictly read-only: no file is created, locked, rewritten, renamed or deleted. Paths are resolved like the vault file: linked parent directories are accepted, but a vault file, backup folder or backup file that is itself a symbolic link is never followed. File sizes use the same bounds as backups, and each decrypted model is wiped after its file. Each file needs a full key derivation, so the check can take a while; locking cancels it before the next file and no partial report is shown. Other files in the folder, including backups of other vaults, are ignored. Each file is reported as:

- *in Ordnung*: authenticated, with revision, entry count (including trash) and file date, as in the restore preview.
- *kann mit den aktuellen Zugangsdaten nicht authentifiziert werden*: the AES-GCM tag does not verify. This is expected for backups written before a password or key-file change, but it is also what a tampered or truncated file produces. Authenticated encryption cannot distinguish a wrong key from modified ciphertext, so Keyrook does not guess and never labels such a file intact or corrupt. Check old backups with **Backup wiederherstellen** and the credentials in use at the time.
- *beschädigt*: invalid header, size outside the bounds or invalid authenticated content.
- *authentisch, passt aber nicht*: the content authenticates but its vault ID or revision differs from the file name, or the vault file differs from the opened revision (renamed, replaced or changed outside this session).
- *nicht lesbar*: not a regular file, a symbolic link or a read error. A missing or unreadable backup folder is reported separately.

Without a configured backup folder only the vault file is checked. The report shows file names only, never folder paths or error details.

**Verschlüsselt exportieren** creates a new `.keyrook` file with explicitly chosen credentials, preserving the records and resetting its revision to zero. Like a restored backup it is a separate vault with a new vault ID, so its backups never mix with the original's. It is the preferred transfer format.

### Vaults of earlier releases

Keyrook up to 0.7.x writes document schema 1; 0.8.0 and later write schema 2
(with favorites as a field, customer details and templates). Vaults saved by
0.7.x or earlier open normally; their favorite tags become
favorites in memory, and a notice above the list says that the next save
converts the vault. Opening changes nothing on disk. The first save afterwards
keeps the unchanged old file next to the vault as
`<vault file>.schema-v1-r<revision>.keyrook.bak` (shortened for very long file
names, see [FORMAT.md](FORMAT.md#compatibility-and-migrations)), whether or not a
backup folder is configured, and then writes the new format. If that copy
cannot be written, nothing is saved and a message says so. The copy is
encrypted with the same password and key file, is
never rotated or deleted by Keyrook, and can be opened by the earlier release
(rename it to `.keyrook` first) or restored with **Backup wiederherstellen**.
Delete it once the converted vault works as expected. Earlier releases cannot
open a converted vault; they report it as invalid or unsupported and leave it
unchanged. See [FORMAT.md](FORMAT.md#compatibility-and-migrations).

A vault of an earlier release close to the 64 MiB size limit still opens, but
because the new format adds fields, saving may report that the vault is too
large until entries are deleted permanently or large notes are shortened.

## Import and plaintext export

Import parses and validates first, then asks for confirmation showing the entry count. It adds records to the current vault under new IDs, with the references between imported customers, projects, entries and templates updated, so importing a vault's own export or the same file twice adds copies and never overwrites records. Enable backups before importing into a valuable vault. An import that could not be saved because the vault would exceed the 64 MiB size limit of the file format is refused with a message that says so, and nothing changes. Supported inputs:

- Keyrook JSON: all entry types, references, metadata and history.
- CSV: select the actual header names from dropdowns for title, URL, username, password, notes, tags and favorite. Tags are separated by commas or semicolons. The title column is required; optional fields can remain unassigned. Common German and English column names are suggested. Quoted commas, escaped quotes and multiline values are supported. Headers must be unique and nonblank, with at most 100 columns and 512 characters per name; otherwise nothing is imported and a message states these rules. A UTF-8 BOM is accepted. Keyrook's own CSV format is recognized automatically without a mapping dialog.
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

**Klartext exportieren** warns twice before writing, in two separate questions: before the format is chosen and again after the new target file is selected. Questions start with the focus on **Nein**, so Enter alone never confirms; Escape cancels. JSON is the full Keyrook model. Keyrook CSV uses a `keyrook-json` header and one quoted JSON record so every type, reference and history roundtrips without flattening losses. It is not intended for spreadsheet editing. Both include secrets and deleted/history records. Keep them private and use encrypted export when possible.

## Customer reports

The reports in the **Daten** menu group entries by the customer assignment that
**Kunden, Projekte und Vorlagen** already manages: an entry belongs to its own customer or,
without one, to the customer of its project (the same rule as the customer filter
of the entry list). Tags play no role. Trashed entries are never included. Reports
never show passwords, private keys, passphrases, TOTP secrets, notes (of entries,
customers or projects), history or custom field values, and they never show a
field whose hidden option is set.

**Kundenübersicht** lists every customer sorted by name, followed by *Ohne Kunde*
for entries without a customer: the customer's contact person, e-mail, phone and
website when set, its projects with their descriptions, the number of active
entries per type, the domain entries with their domain and expiry date, and the
server and file-transfer entries with host and port. A hidden domain or host field appears as *Verborgen*.
The overview is shown as read-only plain text and is not saved.

**Übergabeblatt exportieren** asks for a customer and a new `.html` file and
writes a handover sheet for that customer's active entries, grouped by type:
title, type, project, tags, expiry date, ports, protocols, SSH key type and the
titles of linked servers and registrar logins, plus these fields when they are
not marked hidden: URLs, hosts, user names, e-mail addresses and mail servers,
start directories, roles, operating systems, SSH fingerprints, domain names and
registrars. Custom entries show title and metadata only; SSH public keys are
left out. The page is a single self-contained file without links, scripts or
external resources; every value is HTML-escaped, and it can be printed from a
browser. Like other exports, the file is created new (never replacing an existing
file) and readable only by your user account. Check it before handing it over:
titles, tags and visible fields appear as entered.

**Ablaufdaten exportieren** writes the expiry dates of all active entries to a
new file. Keyrook has one expiry date per entry (the editor's expiry field), so
domain, certificate, contract and other dates are all exported the same way.
Each row or event holds the date, title, type, customer, project and, for domain
entries, the domain name when that field is not hidden.

- *Kalender (ICS)*: an RFC 5545 calendar with one all-day event per entry. Keyrook
  asks for a reminder in days before the date (0–365, 0 for none; the default is
  30). Each event's UID is derived from the entry ID, so importing a newer export
  into the same calendar updates the events instead of duplicating them where the
  calendar application supports this.
- *CSV*: UTF-8, comma-separated, every field quoted as in RFC 4180, with a header
  row. Cells starting with `=`, `+`, `-`, `@`, a tab or a carriage return get a
  leading `'` so spreadsheet applications do not run them as formulas.

## Warning list

The vault health check looks at active entries for expiry within 30 days, expired
dates, short or repetitive passwords, reuse across entries, passwords unchanged
for more than 365 days and possible duplicate entries. Empty passwords are not
rated. The checks run locally and are limited heuristics; a password without a
warning is not guaranteed strong.

- **Old password**: the age counts from the last time the password itself
  changed, taken from the entry history, so editing the title or notes does not
  reset it. If the history holds no password change, the age counts from the
  creation of the entry (or from the oldest kept history item when all 100 are
  used). Entries without any history, such as some imports, count from their
  last change of any kind. Imported KeePass history records when a version was
  created rather than replaced, so ages of such entries can come out too high.
- **Possible duplicate**: another active entry of the same type has the same
  host or URL and the same user name, ignoring upper/lower case, surrounding
  blanks and trailing slashes (`https://Example.org/` matches
  `https://example.org`). The port and passwords are not compared. Entries
  without a host/URL or user name, SSH keys and domains are not compared. Custom
  records use their first URL field (`url`, `url1`, `uri`, `host`) and their user
  name field (`username`, `user`, `benutzername`).

The check runs by itself after unlocking, after every change of the vault and
once a day, in the background on a copy of the vault that is erased afterwards;
the window stays responsive. Its result appears in three places:

- **Warnliste** (**Warnings**) in the header shows how many entries are
  affected per reason, for example *2 abgelaufen*, *1 laufen ab*, *3 schwach*,
  *2 mehrfach*, *4 alt*, *2 doppelt*, *1 geleakt* (or *keine*; *wird geprüft* while the check runs). Expired entries
  and passwords found in known breaches are marked in the error color.
- List cards and the detail view mark entries with a short or repetitive, a
  reused or an old password, or one found in known breaches. Possible duplicates appear in the warning list only. The expiry badge on the cards covers expired and expiring
  entries. Markers name the reason only; no password or part of one is shown.
- Clicking **Warnliste** opens the list with a summary, the affected entry
  titles and their reasons. Clicking a title (or focusing it and pressing Enter)
  closes the list and selects that entry. If the current search or filters hide
  it, they are reset to all active entries (keeping the sort order) first.
  While an editor is open or work is running, titles cannot be selected.

### Breach check

The local checks do not know which passwords have appeared in data breaches.
**Passwörter mit bekannten Datenlecks abgleichen …** (**Check passwords against
known breaches …**) at the top of the warning list compares them with the
[Pwned Passwords](https://haveibeenpwned.com/Passwords) service of Have I Been
Pwned. This is optional and never happens on its own:

1. After the click, Keyrook hashes the non-empty passwords of active entries
   locally (password fields, SSH key passphrases and custom fields named
   `password`, `passwort` or `passphrase`; not the history or trash). Nothing
   is sent yet.
2. A question explains what will be sent and asks for consent, every time:
   only the first 5 characters of each password's SHA-1 hash go to
   `api.pwnedpasswords.com`, it names how many passwords and requests are
   involved, that the service sees your IP address and that nothing is stored.
   **Abbrechen** (**Cancel**) has the focus; Enter alone does not start the
   check.
3. After **… Anfragen senden** (**Send … requests**) the list shows the
   progress and an **Abbrechen** (**Cancel**) button. Canceling, a failed
   request or an invalid answer ends the run without results.
4. Affected entries get the reason *Passwort in bekannten Datenlecks*
   (*password in known breaches*) with the number of times the service has
   seen that password, a *geleakt* (*breached*) count in the header and a
   marker on their card and in the detail view.

The results are kept in memory only and disappear when the vault is locked or
the window closes; an edited or trashed entry loses its marker until the next
check. A password without this marker may still be weak or known to attackers.
What exactly is sent and what the service can learn is described in
[SECURITY.md](SECURITY.md#breach-check).

## Locking and current boundaries

Use **Sicherheit** (**Security** in English) to choose the appearance (follow system, light or dark), the contrast and interface scale (see [Accessibility](#accessibility)), the language (follow system, German or English), the inactivity deadline (default five minutes), when the window locks, clipboard expiry (default 20 seconds) and whether to check for updates on start (default off; see [Updates](#updates)). These preferences are saved and survive restarts.

**Sperren, wenn das Fenster…** (**Lock when the window…**) controls locking on window events and applies immediately:

| Choice | Locks on |
| --- | --- |
| **den Fokus verliert oder minimiert wird** (*loses focus or is minimized*) | Switching to another application and minimizing. Moving between Keyrook's own dialogs does not lock. |
| **minimiert wird** (*is minimized*, default) | Minimizing only. Copying a password, switching to the browser and coming back keeps the vault open. |
| **nie** (*never*) | Neither focus loss nor minimizing. |

Under every choice, supported operating-system session/sleep notifications (screen lock, user switch, sleep) trigger locking and the inactivity timer keeps running; notification coverage varies by platform. A suspend is also recognized from the system clock, so an unlocked vault locks right after resume even when no sleep notification arrives. The trade-off: with **minimiert wird** or **nie**, an unlocked vault stays unlocked behind other windows until the inactivity deadline, an OS lock or sleep event, or **Sperren**. Choose **den Fokus verliert oder minimiert wird** on shared or unattended machines. The window choice does not change clipboard expiry: copied values are still cleared after the configured seconds and on lock. See [SECURITY.md](SECURITY.md) for the limits of OS-event detection.

**Sperren** remains available during vault operations. Locking discards unsaved edits, clears the displayed snapshot and owned clipboard, and closes open application dialogs; an open question or password prompt counts as canceled. Already-started atomic writes finish before the worker clears session credentials. Results from before the lock cannot reopen the display. Reopen the vault to check the saved state if locking happened during a save.

Closing the window while an operation such as a save, backup, integrity check or export is still running asks first, because quitting stops that operation; **Trotzdem beenden** quits anyway and **Abbrechen** keeps Keyrook open. If the operation ends before you answer, the question closes and the window stays open so its result can be read. Closing without a running operation quits immediately.

Failed unlock attempts produce increasing waiting periods, capped at 60 seconds. Only a rejected password or key file counts as a failed attempt; a missing, unreadable, damaged or busy vault file, or a key file of the wrong size, is reported without a delay. A countdown shows when the next attempt is available. Locking does not reset that delay; a successful unlock or application restart does. This is not protection against attacks on a copied vault file.

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
be dismissed. Without a click or this option, and apart from the
[breach check](#breach-check) that also needs a confirmation every time,
Keyrook makes no network request.
The exact request and the reasons why automatic installation is intentionally
absent are described in [SECURITY.md](SECURITY.md#update-check).

## Saved settings

Keyrook stores non-secret preferences in `settings.json` in the platform configuration directory: `%APPDATA%\Keyrook` on Windows, `~/Library/Application Support/Keyrook` on macOS and `$XDG_CONFIG_HOME/keyrook` (or `~/.config/keyrook`) on Linux. The file contains the appearance, contrast, interface scale, language, inactivity deadline, window lock choice, clipboard expiry, whether updates are checked on start, the main window's size, position and maximized state, the last opened vault path, per-vault backup settings (folder, retention, enabled) and the last password generator choices (preset, length, character classes, ambiguity option, passphrase word count and separator, and the path of a chosen passphrase word list, never its content or any generated value). The unlock form is pre-filled with the last vault path. Passwords, key-file paths, key-file contents and vault contents are never saved there, so an optional key file must be selected again. If the file cannot be written, a notice appears and the changed preference applies until the application exits. A damaged, unknown or out-of-range file or value falls back to defaults; delete the file to reset all preferences. See [SECURITY.md](SECURITY.md#saved-settings).

### Window size and position

The main window reopens with the size, position and maximized state it had when it was last moved, resized or closed. Changes are written once the window has stayed unchanged for a moment, and again on closing; minimized and full-screen states are not recorded, and while the window is maximized the previous normal size and position are kept for restoring. At start the stored values are checked: sizes are kept between 720 × 520 and 16384 × 16384 dp and reduced to fit the screen, and a window whose title bar would not be visible on any currently connected screen (for example after disconnecting a monitor) opens centered on the main screen instead. The window cannot be made smaller than 720 × 520 dp. The first start, or a missing or damaged entry, uses 1100 × 760 dp centered on the main screen.

A larger interface scale raises both limits by the same factor, so the same content stays visible: at 150 % the window cannot be made smaller than 1080 × 780 dp, and the first start uses 1650 × 1140 dp. Both are reduced to the usable area of the main screen, never below 720 × 520 dp. When the scale is raised, a smaller window grows to the new minimum at once; a saved window size is kept as it is otherwise.

Desktop text uses German and English resource catalogs. The language choice
applies immediately without restarting or locking; messages already shown stay
in the language they were created in. User-supplied names, custom field
identifiers and persisted data are not translated, and protocol and file-format
names such as SSH, SFTP, IMAP, JSON or KeePass CSV stay as they are. Messages,
questions and input dialogs are part of the main window: they follow the
selected appearance and language, including their buttons, and cannot open
behind the window or on another screen. Only the system file chooser is
provided by the Java runtime; its buttons follow the runtime's default locale
(normally the operating-system language).

Settings files written before the language preference existed load unchanged
and use the system language. Files written before the update-check option
existed load with automatic update checks disabled. Files written before the generator choices existed load with the **Standard** preset, the previous defaults and no remembered word list. Files written before the
contrast and interface-scale options existed load with standard contrast and
100 %; an unknown scale or contrast value falls back to these defaults. A settings file that contains the language is not
readable by earlier builds, which then fall back to their defaults.

## Accessibility

Under **Sicherheit** (**Security**), two display preferences apply immediately
to the main window and every dialog drawn in it, and are saved like the other
settings:

- **Skalierung** (**Interface scale**): 90 %, 100 % (default), 115 %, 130 % or
  150 %. All sizes and text are enlarged together, on top of the scaling the
  operating system already applies. The window's minimum and first-start size
  grow with it (see [Window size and position](#window-size-and-position)). The
  system file chooser and the window title bar are drawn by the operating
  system and follow its own scaling.
- **Kontrast** (**Contrast**): **Standard** (default) or **Hoher Kontrast**
  (**High contrast**). High contrast replaces the colours of the selected light
  or dark appearance with black on white or white on black, a dark-blue or
  yellow accent and dark red or light red for errors. Every text colour
  reaches at least 7:1 against its background (WCAG AAA), which a unit test
  checks from the palette. Keyboard focus and pressed controls get a stronger
  tint than in the standard colours, and focused text fields keep their thick
  accent-coloured border. Disabled controls are drawn faded in both modes.

Screen readers read the Compose accessibility tree through the Java
accessibility bridge: Narrator or NVDA on Windows (the Java Access Bridge
must be enabled, for example with `jabswitch -enable`), VoiceOver on macOS
and Orca on Linux. What they announce:

- Buttons that repeat for every value name the value they act on, for example
  **Passwort anzeigen**, **Passwort kopieren** and **URL öffnen** (*Show
  password*, *Copy password*, *Open URL*) in the detail view, the editor and
  for the TOTP code, instead of only **Anzeigen**, **Kopieren** or
  **Öffnen**.
- Show/hide buttons also announce whether the value is currently **verborgen**
  or **angezeigt** (*hidden* or *shown*), and a masked value is read as
  *Passwort: verborgen* rather than as bullet characters. Revealed values are
  read like any other text, so reveal only what may be heard.
- Check boxes and option buttons are one control together with their label:
  clicking the label toggles them and the label is read with the state. The
  per-field **Verborgen** and **URL-Feld** options in the editor name their
  field.
- The theme button in the header names the appearance it switches to, the
  **Sicherheit** button announces whether the settings are expanded, and the
  application name, entry title and editor and unlock headings are reported as
  headings. Error and notice messages under the header are marked as live
  regions where the platform bridge supports it.
- The favorite star of a list card or the detail view is named after its
  entry and action (for example *Acceptance web: Mark as favorite*) and
  announces whether the entry is a favorite; the bulk-selection check box of a
  card is named *Select* plus the entry title, and the number of selected
  entries is a live region.
- Every text field has a visible label that is also its accessible name. List
  cards report their selection (see [Entry selection](#entry-selection)).

The focus order follows the visual order: header, settings, messages, then the
unlock form, editor or entry list with its detail view. Dialogs take the focus
when they open.
