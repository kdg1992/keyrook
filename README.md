# Keyrook

[![CI](https://github.com/kdg1992/keyrook/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/kdg1992/keyrook/actions/workflows/ci.yml)
[![CodeQL](https://github.com/kdg1992/keyrook/actions/workflows/codeql.yml/badge.svg?branch=main)](https://github.com/kdg1992/keyrook/actions/workflows/codeql.yml)
[![Release](https://img.shields.io/github/v/release/kdg1992/keyrook?sort=semver)](https://github.com/kdg1992/keyrook/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/kdg1992/keyrook/total)](https://github.com/kdg1992/keyrook/releases)
[![License: GPL-3.0](https://img.shields.io/github/license/kdg1992/keyrook)](LICENSE)
![Platforms](https://img.shields.io/badge/platform-Windows%20%7C%20macOS%20%7C%20Linux-blue)

Keyrook – Offline, encrypted credential vault for hosting providers and sysadmins. Manage website, FTP, mail, hosting panel and server logins plus SSH keys with passphrases in one file. Cross-platform (Windows, macOS, Linux), with automatic encrypted backups.

## Current implementation

The JVM core implements typed vault records, Argon2id/AES-256-GCM encryption,
optional key-file material, authenticated format v1, atomic local storage and
manual session locking/password changes. Desktop UI, SSH key operations,
backup automation, import/export and installers are not available yet.
This is a development build; use test data.

## Build and test

Install a JDK 25 and point `JAVA_HOME` to it. Gradle uses the configured Java 25
toolchain. The first build downloads pinned dependencies from Maven Central
and the Gradle plugin portal.

```powershell
# Windows, with JAVA_HOME already set to a JDK 25 installation:
.\gradlew.bat :core:build
```

```sh
# macOS / Linux:
sh ./gradlew :core:build
```

Test reports are generated at `core/build/reports/tests/test/index.html`.
The resulting core JAR is a library, not an executable application.

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
