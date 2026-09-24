# Contributing to Keyrook

This guide describes how changes are prepared, reviewed and released.

## Security issues

Do not report vulnerabilities in public issues or pull requests. Use private
reporting as described in the [security policy](.github/SECURITY.md).

## Prerequisites

- JDK 25, with `JAVA_HOME` pointing to it. Gradle uses the configured Java 25
  toolchain; the wrapper downloads and verifies the pinned Gradle distribution.
- Git and a GitHub account for pull requests.

## Build and verify

Run the full verification before opening a pull request:

```sh
./gradlew check        # macOS / Linux
.\gradlew.bat check    # Windows
```

`check` runs core and desktop tests, an offscreen Compose render,
source-header/whitespace lint and runtime dependency checks. Start the
application with `./gradlew :app:run`. See [CI and releases](docs/CI.md) for
what the CI workflows check in addition.

## Branches, pull requests and commits

- Work on a branch named after the change type and topic, for example
  `feat/totp-codes`, `fix/backup-rotation`, `docs/readme`, `ci/installer-tests`
  or `chore/gradle-update`. Do not push to `main`.
- Pull request titles must follow
  [Conventional Commits](https://www.conventionalcommits.org/):
  `type(scope): summary` with the type `feat`, `fix`, `docs`, `test`,
  `refactor`, `ci`, `chore`, `build`, `perf` or `revert`, for example
  `fix(storage): preserve the original file`. The `pr-title` check enforces
  this.
- Pull requests are squash-merged with the PR title and description as the
  commit message on `main`. Release Please reads these commits to prepare the
  next release: `feat` raises the minor version, `fix` raises the patch version,
  and both appear in the changelog. Types such as `docs`, `ci` or `chore` do not
  start a release on their own. Choose the type that describes the effect for
  users. Because the description becomes the commit body, do not write
  `BREAKING CHANGE:` or `Release-As:` lines in it unless that effect is
  intended.
- Breaking changes are marked with `!` after the type or a `BREAKING CHANGE:`
  footer. During 0.x they raise only the minor version. From 1.0.0 on they
  raise the major version, so they are avoided and need the owner's explicit
  approval before merging. Version 1.0.0 itself is requested once with a
  `Release-As: 1.0.0` footer at the end of the final 1.0 pull request's
  description (see [CI and releases](docs/CI.md#release-process)).
- Dependency updates follow the same rule: an update that changes what ships
  in the app (a runtime library) is `fix(deps)`, so it is released; an update
  that only touches build or test tooling is `build(deps)`, and GitHub Actions
  updates are `ci(deps)`. Gradle updates must regenerate every lockfile with
  `scripts/relock.sh` and, for runtime libraries, update the reviewed native
  inventories in `licenses/native/` (see `docs/PACKAGING.md`).
- Describe what changed, why, how it was tested and any risks, using the pull
  request template. Keep each pull request focused on one change.

## Code requirements

- Every Kotlin source and Gradle build file starts with the SPDX header that
  the lint task checks:

  ```kotlin
  // SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
  // SPDX-License-Identifier: GPL-3.0-or-later
  ```

  Workflow and other YAML files carry the same lines as `#` comments. Tabs and
  trailing whitespace are rejected.
- Do not implement cryptographic primitives or protocols yourself. Use the
  established providers already in use (Bouncy Castle, Apache MINA SSHD) and
  the `Secret`/`Credentials` ownership rules described in
  [docs/SECURITY.md](docs/SECURITY.md).
- Changes to the vault format, cryptography, locking or clipboard handling need
  tests and an update of the corresponding documentation in `docs/`.
- Tests, fixtures, screenshots and logs contain synthetic data only: no real
  vaults, passwords, keys, hostnames or customer names.

## Dependencies

Keyrook is licensed under GPL-3.0-or-later, so new or updated dependencies must
be under a GPL-3.0-compatible license. The `dependency-review` check compares
each pull request's dependency changes with an allowlist of compatible licenses
and rejects missing licenses and known high or critical vulnerabilities; the
few reviewed exceptions are documented in [docs/CI.md](docs/CI.md) and changing
them needs a fresh maintainer review. Runtime dependencies are listed in
[THIRD-PARTY-NOTICES](THIRD-PARTY-NOTICES), and changes to the desktop runtime
or bundled JDK require a renewed native inventory review as described in
[docs/PACKAGING.md](docs/PACKAGING.md).

Dependency versions are pinned in `gradle/libs.versions.toml` and in Gradle
lockfiles, with separate desktop lockfiles per target platform. When a change
alters the resolved dependencies, update the lockfiles as described in
[docs/CI.md](docs/CI.md) and commit them with the change.

## License

By contributing, you agree that your contribution is licensed under
GPL-3.0-or-later, including the
[additional permission](licenses/GPL-3.0-Additional-Permission.txt) under
section 7.
