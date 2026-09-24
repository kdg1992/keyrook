# Keyrook

[![CI](https://github.com/kdg1992/keyrook/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/kdg1992/keyrook/actions/workflows/ci.yml)
[![CodeQL](https://github.com/kdg1992/keyrook/actions/workflows/codeql.yml/badge.svg?branch=main)](https://github.com/kdg1992/keyrook/actions/workflows/codeql.yml)
[![Release](https://img.shields.io/github/v/release/kdg1992/keyrook?sort=semver)](https://github.com/kdg1992/keyrook/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/kdg1992/keyrook/total)](https://github.com/kdg1992/keyrook/releases)
[![License: GPL-3.0-or-later](https://img.shields.io/badge/license-GPL--3.0--or--later-blue)](LICENSE)
![Platforms](https://img.shields.io/badge/platform-Windows%20%7C%20macOS%20%7C%20Linux-blue)

Keyrook – Offline, encrypted credential vault for hosting providers and sysadmins. Manage website, FTP, mail, hosting panel and server logins plus SSH keys with passphrases in one file. Cross-platform (Windows, macOS, Linux), with automatic encrypted backups.

## Features

- Encrypted vault file: Argon2id key derivation, AES-256-GCM, authenticated
  [format](docs/FORMAT.md), optional 32-byte key file, atomic writes with
  conflict detection.
- Eight entry types (web, transfer, email, hosting panel, server, SSH key,
  domain, custom) with tags, full-text search, filters, sorting, trash and
  field history.
- Customers and projects with contact details (contact person, e-mail, phone,
  website), project descriptions and encrypted notes; entry templates that
  preset type, field layout, tags, customer and project without any values.
- Favorites (a pinned mark stored in the entry) with their own filter, a
  per-session list of recently used entries, and bulk actions for marked
  entries (trash, restore, add/remove tag, favorites).
- List/detail layout with per-field reveal and copy; masked values are hidden
  again on selection change, focus loss, minimizing and lock.
- TOTP codes (RFC 6238) from Base32 secrets or `otpauth://` URIs.
- Password generator with presets (standard, shell/FTP-safe, max. 16
  characters) and optional exclusion of ambiguous characters; passphrase
  generator with a user-supplied word list; Ed25519/RSA-4096 SSH key
  generation, OpenSSH/PEM and authenticated PuTTY PPK 2/3 import, SSH/SFTP
  command copying.
- Clipboard ownership with expiry, inactivity and OS-session locking,
  configurable window lock policy and failed-attempt delays.
- Local warning list for expired and expiring entries and short, repetitive,
  reused or old (unchanged for over a year) passwords and possible duplicate
  entries.
- Optional check of passwords against known breaches (Have I Been Pwned
  Pwned Passwords, k-anonymity): only the first 5 characters of each SHA-1
  hash are sent, after a confirmation on every run; results stay in memory
  until lock.
- Customer reports: a read-only customer overview, a self-contained HTML
  handover sheet per customer and an expiry-date export as ICS calendar or
  CSV; none of them contains passwords, notes or hidden fields.
- Automatic encrypted backups with retention, restore preview and a read-only
  integrity check of the vault and its backups.
- Encrypted export; import from Keyrook JSON/CSV, mapped CSV, KeePass CSV,
  KeePass XML and Bitwarden unencrypted JSON; confirmed plaintext export.
- Keyboard navigation and shortcuts, themed in-window dialogs, light/dark
  appearance, German and English interface, remembered window placement.
- Accessibility: interface scaling from 90 % to 150 %, a high-contrast mode and
  screen-reader names, roles and states for controls and masked values.
- Update check on request or, opt-in, at start; it only reports new releases.
  Keyrook never downloads or installs updates. Apart from these two
  user-started features it makes no network request.

Details are in [desktop usage](docs/DESKTOP.md); see also
[security properties and limitations](docs/SECURITY.md) and the
[readiness overview](docs/READINESS.md). Until release 1.0 passes the
[manual acceptance protocol](docs/ACCEPTANCE.md), use it with test data.

### Known limitations

- Keyrook has not been independently audited.
- Installers are not code-signed, and the macOS application is not notarized
  (see [unsigned installers](docs/PACKAGING.md#unsigned-installers)).
- Linux desktops generally deliver no operating-system lock, user-switch or
  sleep events to Java; there the inactivity timeout and the window lock
  policy are the fallback ([session behavior](docs/SECURITY.md#session-behavior)).
- Packages exist only for Windows x64, macOS ARM64 (Apple silicon) and Linux
  x64; there are no Linux ARM64 or macOS x64 (Intel) builds.
- Shown and edited values become immutable JVM strings that cannot be reliably
  erased from memory ([memory ownership](docs/SECURITY.md#memory-ownership)),
  and clipboard histories or clipboard managers can keep copies Keyrook cannot
  remove ([session behavior](docs/SECURITY.md#session-behavior)).

## Install

Download the installer for your platform from the
[Releases page](https://github.com/kdg1992/keyrook/releases/latest): MSI for
Windows x64, DMG for macOS ARM64 (Apple silicon), DEB or RPM for Linux x64.
The installers are unsigned and the macOS application is not notarized; follow
the [unsigned installation steps](docs/PACKAGING.md#unsigned-installers) instead
of disabling SmartScreen or Gatekeeper. Each release's installers are
installed, upgraded and removed in the release workflow before publication
(see [installer tests](docs/PACKAGING.md#installer-tests)). To upgrade, back up
your vault and install the new version over the old one; vaults and settings
remain in place.

### Upgrade notes

- Vaults from Keyrook 0.7.x and earlier use document schema 1. Keyrook 0.8.0
  and later open them unchanged and convert them to schema 2 on the first save.
  Before that save, the unchanged file is kept next to the vault as
  `<vault>.schema-v1-r<revision>.keyrook.bak`.
- Older versions cannot open a converted vault; they refuse it without
  changing it. There is no downgrade path for a converted vault: to go back,
  open the kept `.schema-v1-r<revision>.keyrook.bak` copy (renamed to
  `.keyrook`) with the older version. Changes made after the conversion are not
  in that copy. See [vaults of earlier releases](docs/DESKTOP.md#vaults-of-earlier-releases).

### Verify downloads

Every release includes `SHA256SUMS.txt`. Compare the installer's SHA-256 with
its line in that file before installing:

```sh
sha256sum --ignore-missing -c SHA256SUMS.txt   # Linux
shasum -a 256 Keyrook-*.dmg                    # macOS, compare manually
```

```powershell
Get-FileHash .\Keyrook-*.msi -Algorithm SHA256  # Windows, compare manually
```

## Build and test

Install a JDK 25 and point `JAVA_HOME` to it. Gradle uses the configured Java 25
toolchain. The first build downloads pinned dependencies from Maven Central,
Google Maven (AndroidX only) and the Gradle plugin portal.

```powershell
# Windows, with JAVA_HOME already set to a JDK 25 installation:
.\gradlew.bat check
.\gradlew.bat :app:run
```

```sh
# macOS / Linux:
sh ./gradlew check
sh ./gradlew :app:run
```

Test reports are generated under `core/build/reports/tests/test/` and
`app/build/reports/tests/test/`. Desktop tests include an offscreen render.
The core JAR remains a library; start the desktop application with `:app:run`.

## Core API

The `core` module is the application's internal library. Its Kotlin API is
not covered by Semantic Versioning and can change in any release; the
[compatibility promise](docs/FORMAT.md#compatibility-promise-from-100) covers
the vault format and application behaviour only.

`VaultCodec` encrypts/decrypts in-memory vaults. `VaultStore` reads/writes files
with concurrency tokens. `VaultSession` owns a document and credentials,
provides independent snapshots, and exposes `create`, `open`, `save`,
`changePassword` and `lock`. Close secrets, credentials and snapshots after use.
Operations are synchronous; desktop callers should use a background thread.

See [the architecture](docs/ARCHITECTURE.md), [the file format](docs/FORMAT.md), [security and memory ownership](docs/SECURITY.md)
and [third-party notices](THIRD-PARTY-NOTICES).

Build checks, dependency review and releases are described in
[CI and releases](docs/CI.md). Contributions follow
[CONTRIBUTING.md](CONTRIBUTING.md); report vulnerabilities privately as
described in the [security policy](.github/SECURITY.md).

## License

Copyright 2026 Kim Daniel Geisthardt. Keyrook is licensed under
GNU GPL version 3 or later (`GPL-3.0-or-later`); see [LICENSE](LICENSE).
An [additional permission](licenses/GPL-3.0-Additional-Permission.txt) under
section 7 allows linking with the Skiko native libraries and their components.
Native component notices are listed per platform in [licenses/native](licenses/native/).

This product includes DNG technology under license by Adobe Systems Incorporated.
