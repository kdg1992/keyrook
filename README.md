# Keyrook

[![CI](https://github.com/kdg1992/keyrook/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/kdg1992/keyrook/actions/workflows/ci.yml)
[![CodeQL](https://github.com/kdg1992/keyrook/actions/workflows/codeql.yml/badge.svg?branch=main)](https://github.com/kdg1992/keyrook/actions/workflows/codeql.yml)
[![Release](https://img.shields.io/github/v/release/kdg1992/keyrook?sort=semver)](https://github.com/kdg1992/keyrook/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/kdg1992/keyrook/total)](https://github.com/kdg1992/keyrook/releases)
[![License: GPL-3.0](https://img.shields.io/github/license/kdg1992/keyrook)](LICENSE)
![Platforms](https://img.shields.io/badge/platform-Windows%20%7C%20macOS%20%7C%20Linux-blue)

Keyrook – Offline, encrypted credential vault for hosting providers and sysadmins. Manage website, FTP, mail, hosting panel and server logins plus SSH keys with passphrases in one file. Cross-platform (Windows, macOS, Linux), with automatic encrypted backups.

## Current implementation

The desktop application creates and opens encrypted vaults, edits all eight
entry types, searches and filters entries, manages customers/projects and
supports trash, history and automatic locking. It includes password/passphrase
generation, Ed25519/RSA-4096 keys, encrypted backups and import/export.
Argon2id/AES-256-GCM, optional key files and authenticated format v1 remain
the storage foundation.

This is a development build; use test data. Inactivity locking, supported desktop
session events, lock-on-focus-loss, failed-attempt delays and a local password/expiry
warning list and authenticated PuTTY PPK 2/3 import are implemented. Unsigned native
installers for Windows x64, macOS ARM64 and Linux x64 are published with each
release; platform installation tests are still pending. See [desktop usage](docs/DESKTOP.md) and [packaging status](docs/PACKAGING.md).

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

`VaultCodec` encrypts/decrypts in-memory vaults. `VaultStore` reads/writes files
with concurrency tokens. `VaultSession` owns a document and credentials,
provides independent snapshots, and exposes `create`, `open`, `save`,
`changePassword` and `lock`. Close secrets, credentials and snapshots after use.
Operations are synchronous; desktop callers should use a background thread.

See [the file format](docs/FORMAT.md), [security and memory ownership](docs/SECURITY.md)
and [third-party notices](THIRD-PARTY-NOTICES).

Build checks, dependency review and release activation are described in
[CI and releases](docs/CI.md). Repository settings and successful GitHub runs
must be verified when the workflows are published.

## License

Copyright 2026 Kim Daniel Geisthardt. Keyrook is licensed under
GNU GPL version 3 or later (`GPL-3.0-or-later`); see [LICENSE](LICENSE).
An [additional permission](licenses/GPL-3.0-Additional-Permission.txt) under
section 7 allows linking with the Skiko native libraries and their components.
Native component notices are listed per platform in [licenses/native](licenses/native/).

This product includes DNG technology under license by Adobe Systems Incorporated.
