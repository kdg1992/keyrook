# Manual acceptance protocol for release 1.0

This protocol covers what CI cannot verify: installing, upgrading and removing
the unsigned installers, and the behavior of locking, clipboard handling and
the update check on real desktops with real operating-system events. The
maintainer runs it on physical or fully virtualized machines before release
1.0 is approved. The coverage split between CI and this protocol is listed in
[READINESS.md](READINESS.md#verification-coverage).

Steps use the English interface labels; the German labels are listed in
[DESKTOP.md](DESKTOP.md). **Ctrl** means **Command (⌘)** on macOS.

## Preparation

### Target systems

| ID | System | Package | Notes |
| --- | --- | --- | --- |
| WIN | Windows 11 x64, current feature update | MSI | Standard user account; a second local account for user switching. |
| MAC | macOS on Apple Silicon, current major version | DMG | A second user account with fast user switching enabled. |
| LNX-G | Ubuntu 24.04 LTS, GNOME, Wayland session | DEB | Keyrook runs through XWayland. Check `echo $XDG_SESSION_TYPE` prints `wayland`. |
| LNX-K | Kubuntu 24.04 LTS, KDE Plasma, X11 session | DEB | Choose the X11 session at login. Check `echo $XDG_SESSION_TYPE` prints `x11`. |
| LNX-R | Fedora Workstation, current release | RPM | Sections 1–3 only (package handling). |

Use a laptop for at least one Windows, one macOS and one Linux run so that lid
close can be tested. Only x64 Windows/Linux and ARM64 macOS packages are
reviewed and published; other architectures are out of scope.

### Version under test

1. Record the commit SHA of the approved release pull request that sets
   `version.txt` to `1.0.0`.
2. Before merging, run the `Release` workflow manually on that commit
   (see [PACKAGING.md](PACKAGING.md#workflow-behavior)) and download the
   installers from the run's artifacts. After publication, repeat sections 1
   and 7 with the assets of the published `v1.0.0` release.
3. Compare each installer's SHA-256 with the checksum file that accompanies it
   (`SHA256SUMS.txt` for a published release):
   - Windows: `Get-FileHash .\<installer>.msi -Algorithm SHA256`
   - macOS: `shasum -a 256 <installer>.dmg`
   - Linux: `sha256sum <installer>.deb` (or `.rpm`)
4. For the upgrade test, also download the installer of the most recent
   release before 1.0 from the [release page](https://github.com/kdg1992/keyrook/releases)
   and verify it the same way.

### Synthetic test vault

Never use real credentials, real hostnames or a real vault. Every value below
is invented and may appear in screenshots and issues.

1. Create a folder `keyrook-acceptance` in the tester's home directory, with
   a subfolder `backups`.
2. Using the previous release (section 2) or the version under test, create
   `keyrook-acceptance/acceptance.keyrook` with master password
   `Acceptance-Test-Only-2026` and no key file.
3. Add these entries:
   - Web login `Acceptance web`: URL `https://example.com/login`, username
     `test-user`, password `KR-CANARY-web-0001`, notes `KR-CANARY-note-0001`.
   - Server `Acceptance server`: host `test.example.net`, port `22`, username
     `deploy`, password `KR-CANARY-ssh-0002`.
   - Custom entry `Acceptance custom` with a hidden field `token` set to
     `KR-CANARY-token-0003`.
4. Under **Data → Backup folder**, select `keyrook-acceptance/backups` and
   confirm the default retention.
5. Keep a text editor open for paste checks (Notepad, TextEdit in plain-text
   mode, gedit or KWrite).

A value starting with `KR-CANARY` must never be visible unless the tester has
just pressed **Show** for it, and must never be pasted after the checks below
say the clipboard is cleared.

### Lock check

Several steps end with "**Lock check** passes". This means all of:

1. The unlock form is shown with the message *Vault locked. Unsaved input was
   discarded.*
2. No entry title, field value or notes are visible anywhere in the window or
   in any remaining Keyrook dialog.
3. Pasting into the text editor inserts nothing if a Keyrook copy was still on
   the clipboard at the time of locking.
4. After unlocking again, every masked field and the notes in the detail view
   show dots until **Show** is pressed.

### Recording results

Copy this table once per target system into the release pull request or a
tracking issue. Use one row per test ID; a test passes only if every expected
result in its steps is observed. Put observed platform behavior in *Notes*,
for example which OS event locked the vault or that the fallback applied.

| Test ID | OS and version | Desktop/session | Keyrook version | Date | Tester | Result (pass/fail/n.a.) | Notes / issue link |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1.1 | | | | | | | |

## 1. Installation and first start

### 1.1 Install

Windows:

1. Double-click the MSI. If SmartScreen shows an unknown-publisher warning,
   choose **More info → Run anyway** as described in
   [PACKAGING.md](PACKAGING.md#unsigned-installers). Do not disable SmartScreen
   or Smart App Control; if policy blocks the exception, record it and stop.
2. Accept the license and the default directory. The package is a per-user
   installation, so no administrator prompt is expected.
3. Expected: a **Keyrook** Start menu entry exists and **Settings → Apps →
   Installed apps** lists Keyrook with version `1.0.0`.

macOS:

1. Open the DMG and drag **Keyrook** to **Applications**. Eject the DMG.
2. Open Keyrook from **Applications**. Gatekeeper blocks the unsigned,
   unnotarized application.
3. Open **System Settings → Privacy & Security**, choose **Open Anyway** for
   Keyrook and confirm. Do not disable Gatekeeper globally.
4. Expected: Keyrook starts; later starts open without a warning.

Linux:

1. DEB: `sudo apt install ./<installer>.deb`. RPM: `sudo dnf install ./<installer>.rpm`.
   Both packages are unsigned by a distribution repository; accept only after
   the checksum comparison.
2. Expected: `dpkg -s keyrook` (or `rpm -q keyrook`) reports version `1.0.0`,
   and Keyrook appears in the application menu under Utilities.

### 1.2 First start and settings file

1. Before the first start, make sure no settings directory exists (rename an
   old one): `%APPDATA%\Keyrook`, `~/Library/Application Support/Keyrook`, or
   `$XDG_CONFIG_HOME/keyrook` (default `~/.config/keyrook`).
2. Start Keyrook from the Start menu, Launchpad or application menu.
3. Expected: the unlock form appears; **About** shows version `1.0.0`; no
   settings file exists yet, because it is written on the first preference
   change or successful unlock.
4. Open **Security** and change **Appearance** to **Dark**.
5. Expected: `settings.json` now exists in the directory from step 1.
6. Check permissions:
   - Windows: `icacls "$env:APPDATA\Keyrook\settings.json"` lists only the
     current user, with full access `(F)`.
   - macOS: `ls -ld ~/Library/Application\ Support/Keyrook ~/Library/Application\ Support/Keyrook/settings.json`
     shows `drwx------` and `-rw-------`.
   - Linux: `stat -c '%a %n' ~/.config/keyrook ~/.config/keyrook/settings.json`
     prints `700` and `600`.
7. Open the synthetic vault. Expected: `settings.json` contains the vault path
   and backup settings, but no password, key-file path, entry title or
   `KR-CANARY` value (search the file for `KR-CANARY` and `Acceptance`).
8. Set **Language** to **Deutsch**, quit and restart. Expected: the interface
   starts in German and the unlock form is pre-filled with the vault path.
   Switch back to **English**.

## 2. Upgrade from the previous release

Run this on a system where the previous release is installed and the version
under test is not.

1. In the previous release, open the synthetic vault and set non-default
   preferences: appearance **Dark**, language **English**, lock after **2**
   minutes, **Lock when the window…** *loses focus or is minimized*, clipboard
   **10** seconds, **Check for updates on start** enabled. Confirm the backup
   folder is configured.
2. Click **About → Check for updates**. Expected: version `1.0.0` is reported
   as available, with plain-text release notes. (This applies only if 1.0.0 is
   already published; otherwise record n.a.)
3. Quit Keyrook. Record the SHA-256 of `acceptance.keyrook` and a copy of
   `settings.json`.
4. Install the version under test over the previous one with the steps from
   1.1 (on macOS, replace the application in **Applications**).
5. Expected: only one Keyrook installation exists (one entry in Installed apps
   on Windows, one `Keyrook.app` on macOS, one `keyrook` package on Linux), and
   **About** shows `1.0.0`. On macOS, Finder shows bundle version `1.0.0` for
   0.x releases as well (see [PACKAGING.md](PACKAGING.md)); rely on **About**.
6. Start Keyrook. Expected: all preferences from step 1 are unchanged, the
   unlock form is pre-filled with the vault path, and after unlocking a notice
   shows the restored backup folder and retention.
7. Expected before any edit: the SHA-256 of `acceptance.keyrook` equals the
   value from step 3 (opening never rewrites the file). All three entries and
   their values are present.
8. Edit `Acceptance web`, change the notes and save. Expected: the save
   succeeds and a new file appears in `backups`.
9. With **Check for updates on start** still enabled, the start in step 6
   made one request; no notice is shown because no newer version exists.

## 3. Uninstall

1. Quit Keyrook.
2. Uninstall:
   - Windows: **Settings → Apps → Installed apps → Keyrook → Uninstall**.
   - macOS: move `/Applications/Keyrook.app` to the Trash.
   - Linux: `sudo apt remove keyrook` or `sudo dnf remove keyrook`.
3. Expected: the application and its menu entry are gone; `dpkg -s keyrook`
   or `rpm -q keyrook` reports it as not installed.
4. Expected: `keyrook-acceptance/acceptance.keyrook`, its `.acceptance.keyrook.lock`
   sidecar, the `backups` folder and the settings directory all remain
   unchanged. Uninstalling never removes user data, including with
   `apt purge`, because these files are not part of the package.
5. Remove the settings manually and confirm the directory is gone:
   - Windows: `Remove-Item -Recurse "$env:APPDATA\Keyrook"`
   - macOS: `rm -r ~/Library/Application\ Support/Keyrook`
   - Linux: `rm -r "${XDG_CONFIG_HOME:-$HOME/.config}/keyrook"`
6. Reinstall the version under test for the remaining sections. Expected: it
   starts with default preferences.

## 4. Locking

Use a window of at least 900 dp width so that the detail view is shown beside
the entry list. "Reveal" below means: select `Acceptance web` and press
**Show** next to the password, so `KR-CANARY-web-0001` is visible.

### 4.1 Window lock policies

For each policy, set **Security → Lock when the window…** accordingly, set
**Lock after minutes** to **30** so the inactivity deadline cannot interfere,
then unlock and reveal before each step.

*loses focus or is minimized*:

1. Switch to the text editor (Alt+Tab, ⌘+Tab or clicking it). Return.
   Expected: **Lock check** passes.
2. Open **Data → Backup folder** and cancel the file chooser. Expected: the
   vault stays unlocked; moving between Keyrook's own dialogs does not lock.
3. Minimize the window (title-bar button, ⌘+M on macOS, Super+H on GNOME).
   Restore it. Expected: **Lock check** passes.

*is minimized* (default):

4. Switch to the text editor and return. Expected: the vault stays unlocked,
   but the revealed password shows dots again.
5. Minimize and restore. Expected: **Lock check** passes.

*never (inactivity and OS only)*:

6. Switch to the text editor and return. Expected: unlocked, password masked.
7. Minimize and restore. Expected: unlocked, password masked.

Under every policy:

8. Open `Acceptance web` in the editor, change the title without saving and
   press **Lock** (Ctrl+L). Expected: **Lock check** passes and, after
   unlocking, the title is unchanged.
9. Change the policy while unlocked. Expected: the new policy applies to the
   next window event without a restart, and it survives a restart.

### 4.2 Inactivity timeout

1. Set **Lock after minutes** to **1** and any window policy.
2. Unlock, reveal, copy a password (clipboard **120** seconds), then leave
   mouse and keyboard untouched.
3. Expected: after about 60 seconds, **Lock check** passes, including an empty
   paste.
4. Repeat with the policy *never* and the text editor in front for 70 seconds
   (activity in other applications does not count). Expected: returning shows
   the locked form.

### 4.3 Operating-system events

For each event below, set the policy to *never*, **Lock after minutes** to
**30** and clipboard to **120** seconds. Then any lock observed within a
minute comes from an OS notification, not from a window event or the deadline.
Before each event: unlock, reveal, copy the password with **Copy**.

| Event | Windows | macOS | Linux GNOME | Linux KDE |
| --- | --- | --- | --- | --- |
| Screen lock | Win+L | Ctrl+⌘+Q | Super+L | Meta+L |
| User switch | Win+L → **Switch user**, sign in as the second user, switch back | Control Center → user name → second user, switch back | User menu → **Switch User…** | Application launcher → **Leave → Switch User** |
| Sleep | Start → Power → **Sleep** | Apple menu → **Sleep** | `systemctl suspend` | `systemctl suspend` |
| Display sleep | n.a. | `pmset displaysleepnow` | n.a. | n.a. |
| Lid close | close for 30 s, open | close for 30 s, open | close for 30 s, open (if configured to suspend) | as GNOME |

For each event:

1. Trigger it, wait 30 seconds, resume and log back in.
2. Expected on Windows and macOS: **Lock check** passes immediately on return,
   and no `KR-CANARY` value is visible at any moment after resume. The JDK
   normally advertises user-session and system-sleep notifications on these
   systems, and screen-sleep notifications on macOS; a missing lock here is a
   failure to report.
3. Expected on Linux: the JDK typically advertises none of these notifications,
   so the vault may still be unlocked. Record "no OS event" in *Notes*; this is
   the documented limit, not a failure. The revealed value must still be
   masked if the window lost the focus. Then run the fallback check:
   - Set **Lock after minutes** to **1**, trigger the screen lock (not sleep),
     wait 2 minutes, log back in. Expected: **Lock check** passes.
   - Repeat with the policy *loses focus or is minimized* and **30** minutes.
     Record whether the screen lock caused a focus-loss lock.
4. A `KR-CANARY` value visible after resume on any platform is a failure.

Suspend detection: the inactivity deadline compares the JVM's monotonic clock
with the wall clock. When the wall clock has advanced at least 30 seconds more
than the monotonic clock (a suspend), the vault locks on the first timer tick
or input after resume, even if the platform delivered no sleep event. Expected
on every platform: the vault is locked within about one second after resume
and an expired clipboard value is cleared. Record any delay.

## 5. Clipboard expiry and ownership

Set clipboard to **10** seconds. If a clipboard history is enabled (Windows
Win+V history, Klipper, GNOME extensions, macOS third-party tools), note it:
Keyrook cannot remove copies held there.

1. Copy `Acceptance web`'s password with **Copy** in the detail view. Paste
   immediately. Expected: `KR-CANARY-web-0001`.
2. Wait 12 seconds and paste again. Expected: nothing is inserted.
3. Select the entry in the list and press Ctrl+C (focus in the list, not in a
   text field). Paste immediately, then after 12 seconds. Expected: the value,
   then nothing.
4. Ownership: copy the password, then within 5 seconds copy the text
   `unrelated-text` in the text editor. Wait 15 seconds and paste. Expected:
   `unrelated-text`; Keyrook does not clear a later owner.
5. Copy the password and press Ctrl+L. Expected: an immediate paste inserts
   nothing.
6. Copy the password and change the clipboard setting to **20**. Expected: an
   immediate paste inserts nothing.
7. Copy the password and quit Keyrook. Expected: a paste inserts nothing.
8. Set clipboard to **120**, restart and copy. Expected: the value can still
   be pasted after 60 seconds and not after 125 seconds.

## 6. Update check

1. Confirm **Security → Check for updates on start** is off on a fresh
   installation (section 3, step 6).
2. Start monitoring network connections of the Keyrook process:
   - Windows: Resource Monitor → **Network → Network Activity**, filter the
     Keyrook process.
   - macOS: `nettop -p <pid>` (find the PID with `pgrep -i keyrook`).
   - Linux: `sudo tcpdump -i any -n 'port 53 or port 443'` in a terminal.
3. Start Keyrook, unlock the vault and wait 2 minutes. Expected: no network
   activity from Keyrook.
4. Click **About → Check for updates**. Expected: the result says
   *Keyrook 1.0.0 is up to date.* (or reports a newer release), and the only
   connection is one HTTPS connection to `api.github.com`. Nothing is
   downloaded and no browser opens.
5. Disconnect the network and click again. Expected: *The update check failed.
   Please try again later or check the release page manually.*; the vault
   stays usable.
6. Enable **Check for updates on start** and restart. Expected: exactly one
   request to `api.github.com` at start, independent of unlocking, and no
   notice while 1.0.0 is the latest release.

## 7. Keyboard-only flow and language switch

Do not touch the mouse during steps 1–9.

1. Start Keyrook. Use Tab/Shift+Tab to reach the password field of the unlock
   form, type the master password, Tab to **Unlock** and press Enter.
   Expected: the vault opens and the entry list has the focus.
2. Press Ctrl+F, type `server`, press ↓. Expected: `Acceptance server` is
   selected with a visible selection border.
3. Press Ctrl+B, then Ctrl+F, Ctrl+A and Ctrl+V. Expected: the search field
   contains `deploy`. Clear the search field.
4. Press ↓ into the list, Home, End, ↑. Expected: the selection moves and
   stays visible.
5. Press Enter. Expected: the editor opens with the title focused. Press
   Escape. Expected: the editor closes without a prompt.
6. Press Ctrl+N, type a title `Keyboard entry`, press Ctrl+S. Expected: the
   entry is saved and selected.
7. Press Delete (⌫ on macOS), confirm with Enter. Expected: the entry moves to
   the trash.
8. Tab to **Data**, open it with Enter, move with ↑/↓, close with Escape.
9. Press Ctrl+L. Expected: **Lock check** passes.
10. Language: in **Security**, switch **Language** to **Deutsch**. Expected:
    labels change immediately (for example **Sperren**, **Sicherheit**),
    the vault stays unlocked, and entry titles and values are unchanged.
    Switch to **English**, restart, and confirm English is kept. Standard file
    chooser buttons follow the operating-system language, not this choice.

## Known limits

These are documented behavior, not failures. Record the observed behavior in
*Notes* so later releases can compare.

- OS lock, user-switch and sleep notifications depend on what the JDK
  advertises per platform; Linux desktops generally deliver none, and the
  inactivity deadline or a focus-loss lock is the fallback
  ([SECURITY.md](SECURITY.md#session-behavior)).
- Suspend is recognised from the wall clock advancing further than the monotonic
  clock; a forward wall-clock change of 30 seconds or more while awake also locks.
- Clipboard history and clipboard managers keep copies Keyrook cannot remove;
  clearing is best effort without an atomic OS compare-and-clear.
- Revealed text and edited fields are immutable JVM strings; locking removes
  them from the screen, not necessarily from memory.
- Installers are unsigned and the macOS application is not notarized.
- Key-file paths are never saved; the key file must be selected after each
  start.

## Reporting a failure

1. Reproduce the failure once more with the synthetic vault from this
   protocol.
2. Open an issue on [GitHub](https://github.com/kdg1992/keyrook/issues) with:
   - Test ID and step number from this protocol.
   - Keyrook version (from **About**) and installer file name.
   - OS name, version and architecture; for Linux also the desktop
     environment and `$XDG_SESSION_TYPE`.
   - Window lock policy, inactivity minutes and clipboard seconds in use.
   - Expected result as written here, and the observed result.
   - How often it occurs (always, intermittent, once).
3. Never attach real vaults, key files, backups, plaintext exports or real
   credentials. Attach synthetic data only, remove absolute paths that reveal
   user or customer names, and check screenshots for visible values before
   uploading.
4. If the failure leaves a secret visible or unlocked where this protocol
   expects a lock on Windows or macOS, report it privately through the
   repository's **Security → Report a vulnerability** form instead of a public
   issue.
5. Mark the row as *fail* with the issue link. Release 1.0 is not approved
   while a failure without an accepted explanation remains open.
