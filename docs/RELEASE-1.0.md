# Keyrook 1.0.0 release notes

This text can be pasted into the GitHub release for `v1.0.0`. Links are
relative to the repository; replace them with absolute links when pasting.

Keyrook 1.0.0 is the first production release of the offline, encrypted
credential vault for hosting providers and sysadmins. It completes the 0.x
series without changing the vault format of 0.8.x. The installers passed the
[manual acceptance protocol](ACCEPTANCE.md) on Windows, macOS and Linux; the
results are recorded in the 1.0.0 acceptance tracking issue. Keyrook has not
been independently audited, and the installers are unsigned and not notarized.

## Highlights since the 0.x series

### Vault and security

- One encrypted vault file: Argon2id and AES-256-GCM with an authenticated
  header, optional key file, atomic writes and conflict detection.
- Document schema 2 with frozen test vectors and a registered migration from
  schema 1.
- Inactivity and operating-system session locking, a configurable window lock
  policy, clipboard ownership with expiry and failed-attempt delays.
- Automatic encrypted backups with retention, restore preview, restore to a new
  file and a read-only integrity check.

### Organisation

- Eight entry types with tags, full-text search, filters, sorting, trash and
  field history.
- Customers and projects with contact details, descriptions and encrypted
  notes; entry templates.
- Favorites, recently used entries and bulk actions for marked entries.
- TOTP codes from Base32 secrets or `otpauth://` URIs; Ed25519/RSA-4096 SSH
  key generation, OpenSSH/PEM and authenticated PuTTY PPK 2/3 import.

### Generator and health

- Password generator presets (standard, shell/FTP-safe, max. 16 characters),
  exclusion of ambiguous characters and a passphrase generator.
- Warnings for expired and expiring entries, short, repetitive, reused and old
  passwords and possible duplicate entries.
- Optional breach check against Have I Been Pwned Pwned Passwords with
  k-anonymity: only the first 5 characters of each SHA-1 hash are sent, after
  a confirmation on every run.

### Reports and exports

- Read-only customer overview, self-contained HTML handover sheet and expiry
  export as ICS calendar or CSV, all without passwords, notes or hidden fields.
- Encrypted export; import from Keyrook JSON/CSV, mapped CSV, KeePass CSV,
  KeePass XML and Bitwarden unencrypted JSON; confirmed plaintext export with
  owner-only file permissions.

### Accessibility

- Interface scaling from 90 % to 150 %, a high-contrast mode, keyboard
  navigation and screen-reader names, roles and states.
- German and English interface, light and dark appearance.

### Packaging and installers

- MSI for Windows x64, DMG for macOS ARM64 and DEB/RPM for Linux x64, each
  with a bundled Java runtime and `SHA256SUMS.txt`.
- Every installer is installed, upgraded from the previous release and removed
  by the release workflow before publication.
- Update check on request or, opt-in, at start; Keyrook never downloads or
  installs updates.

## Upgrade notes

- Back up your vault, then install 1.0.0 over the previous version. Vaults and
  settings stay in place.
- Vaults from 0.8.x already use document schema 2 and are not changed by the
  upgrade.
- Vaults from 0.7.x and earlier use document schema 1. Keyrook opens them
  unchanged and converts them to schema 2 on the first save. Before that save,
  the unchanged file is kept next to the vault as
  `<vault>.schema-v1-r<revision>.keyrook.bak`.
- There is no downgrade path for a converted vault: releases up to 0.7.x
  refuse it without changing it. To go back, open the kept copy with the older
  release; changes made after the conversion are not in it. See
  [vaults of earlier releases](DESKTOP.md#vaults-of-earlier-releases).
- On macOS, Finder showed bundle version `1.0.0` for the 0.x releases as well;
  **About** shows the real version.

## Compatibility

Every 1.x release reads every vault, backup and Keyrook export written by any
earlier release, including 0.x. Format changes come only with a registered
migration, and older releases refuse newer vaults. See the
[compatibility promise](FORMAT.md#compatibility-promise-from-100).

## Support

Security fixes are made only for the latest 1.x minor release and shipped as
patch releases, as are security updates of the bundled Java runtime. The 0.x
releases are no longer supported. See the
[security policy](../.github/SECURITY.md).

## Known limitations

No independent audit, unsigned and unnotarized installers, limited
operating-system lock events on Linux, no Linux ARM64 or macOS x64 packages,
and JVM strings and clipboard histories that Keyrook cannot erase. Details are
in the [known limitations](../README.md#known-limitations).
