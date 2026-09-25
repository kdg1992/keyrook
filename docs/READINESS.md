# Release readiness

Keyrook 1.0 is a production release for its documented scope: the features and
limits described in the [README](../README.md), [desktop usage](DESKTOP.md) and
[security properties](SECURITY.md). It has not been independently audited, and
the installers are unsigned and not notarized (see the
[known limitations](../README.md#known-limitations)). The table below lists what
is implemented and tested and where each area's documented boundary lies. The
[manual acceptance protocol](ACCEPTANCE.md) results for 1.0.0 are recorded in
the 1.0.0 acceptance tracking issue.

| Area | Implemented and tested | Remaining boundary |
| --- | --- | --- |
| Vault storage | Authenticated envelope v1 with document schema 2, in-memory migration of schema 1 with a kept copy of the old file, frozen schema 1 and schema 2 test vectors, atomic writes, conflict detection, Argon2 settings, optional key files and factor replacement | Releases up to 0.7.x (schema 1) cannot open a migrated vault; unsupported versions are rejected. Migration of vaults written by the real 0.7.0 release is part of the [manual acceptance protocol](ACCEPTANCE.md). Higher-cost KDF headers require core API approval. |
| Desktop records | Eight entry types, customer/project editing with contact details and notes, entry templates, favorites, recently used entries, bulk actions, search, filters, sorting, trash, history, TOTP-secret editing with RFC 6238 code display/copy (RFC 4226/6238 test vectors), password generator presets and passphrase generation, customer overview, handover sheet and ICS/CSV expiry export | TOTP codes follow the local system clock; HOTP (counter-based) secrets and unsupported algorithm, digit or period values are refused; other `otpauth` parameters are ignored. German and English interface, selectable in the settings; only the system file chooser's buttons follow the Java runtime locale. |
| SSH | Ed25519/RSA-4096 generation, OpenSSH/PEM and MAC-verified PPK 2/3 import, validated public-key export and command copying | PPK 1, other key types and excessive KDF costs are refused. The adapter uses established providers and tested format handling; no external cryptographic audit is claimed. |
| Transfer and recovery | Automatic/manual encrypted backups, retention, authenticated restore preview, new-file restoration, encrypted export and confirmed plaintext import/export | External format support is deliberately bounded; see the exact Bitwarden/KeePass limitations in [desktop usage](DESKTOP.md). |
| Security | Inactivity/session-event locking, clipboard ownership/expiry, failed-attempt delay, expiry, weak, reused, old-password and duplicate warnings, a consent-gated breach check and regression coverage | JVM erasure, OS event coverage and clipboard history have documented limits. Real desktop lock/sleep/resume acceptance on each supported system follows the [manual acceptance protocol](ACCEPTANCE.md). |
| Builds | Source checks on Windows, Linux and macOS, native-window lifecycle/event-routing checks, CodeQL, dependency graph/review, release automation, a packaged-runtime diagnostic and automated [installer tests](PACKAGING.md#installer-tests) that install, upgrade from the previous release and remove the MSI, DMG, DEB and RPM before publication | Installer tests run silently on CI runners; interactive installers, SmartScreen/Gatekeeper, per-machine installs and real user data across upgrades remain in the [manual acceptance protocol](ACCEPTANCE.md). Native-window tests inject controlled events; actual OS lock/sleep notifications still need acceptance. |
| Accessibility | Interface scale, high-contrast mode, keyboard navigation and screen-reader names, roles and states, with semantics tests | Behavior with real screen readers (NVDA, Narrator, VoiceOver, Orca) and operating-system scaling is checked in the [manual acceptance protocol](ACCEPTANCE.md#8-accessibility). |
| Redistribution | License gate, artifact hashes, source/notice provenance, reviewed native/JDK inventories for Windows x64, Linux x64 and macOS ARM64, DNG SDK notices and a GPL section 7 additional permission | Dependency or JDK changes require a renewed inventory review; bundled JDK security updates follow the [runbook](PACKAGING.md#bundled-jdk-security-updates). Linux ARM64 and macOS x64 are not reviewed. Installers are not signed or notarized. |

Source checks, the offscreen diagnostic and the silent installer tests do not
certify real desktop session events or interactive installation behavior. The packaged diagnostic exercises bundled
cryptography, SSH and rendering only after the unchanged license gate permits
building; the reviewed inventories now permit that. See [packaging](PACKAGING.md) and [security](SECURITY.md) for the
required evidence and limits. No complete-release claim follows from a green
source build.

## Verification coverage

The [manual acceptance protocol](ACCEPTANCE.md) must pass on Windows, macOS and
Linux before 1.0.0 and each later minor release is approved.

| Covered by CI | Covered by the manual acceptance protocol |
| --- | --- |
| Core and desktop tests on Windows, Linux and macOS, including format, frozen schema 1 and schema 2 vault fixtures, schema migration, cryptography, settings parsing, clipboard ownership logic, inactivity deadline and update-response validation | Installing the unsigned MSI, DMG, DEB and RPM interactively, including SmartScreen and Gatekeeper steps, menu entries and shortcuts |
| Offscreen Compose rendering and lint | Settings file location and permissions on a real installation |
| Native window checks with injected focus, minimize and dialog events under each window lock choice | Upgrade from the previous release with an existing vault and settings; uninstall leaving user data in place |
| Packaged-runtime self-test of each application image before publication | Window lock policies, OS screen lock, user switching, sleep, lid close and inactivity locking with real OS events |
| Silent install, upgrade from the previous release and removal of each installer, with the installed launcher's self-test ([installer tests](PACKAGING.md#installer-tests)) | |
| CodeQL, dependency review, license and inventory gates | Clipboard expiry and ownership against the real system clipboard |
| | Update check and breach check network behavior, keyboard-only use and language switching in the installed application |
| | Screen readers, interface scale and high contrast on real desktops |
| | Conversion of vaults written by the real 0.7.0 release, with and without key file, and 0.7.0's refusal of a converted vault |
| | Key-file unlock, backup restore to a new file, and owner-only permissions (Windows ACL, POSIX mode) of plaintext exports, handover sheets and expiry exports |
| | Handover sheet in offline browsers, ICS import into a calendar, CSV in a spreadsheet, **Open URL** and TOTP codes against a phone authenticator |
| | A smoke pass over templates, customer details, bulk actions, generator presets, reports and health warnings |

## Remaining gates for 1.0

Release 1.0.0 is approved only after the last gate below. Status:

1. **Branch protection** (done). The `main` ruleset described in
   [repository settings](CI.md#repository-settings-after-the-first-green-runs)
   is active with the required checks `build-linux`, `build-windows`,
   `build-macos`, `pr-title`, `dependency-review` and `CodeQL`; only squash
   merging is enabled, with the PR title and description as the commit message.
2. **Release flow** (done). The draft-release flow (tag and draft release,
   packaging, installer tests, then publication) was proven with release 0.8.1,
   and `Recover release pull request` accepts any `MAJOR.MINOR.PATCH` version,
   before or after 1.0.0 (see
   [recovering an unpublished release](PACKAGING.md#recovering-an-unpublished-release)).
3. **1.0 documentation pull request** (done with its merge). It replaces the
   development-build statements (this overview, the README, the security
   documents, desktop usage and the support policy in
   [.github/SECURITY.md](../.github/SECURITY.md)), removes the pre-1.0 bump
   options from `release-please-config.json` and adds the
   [1.0.0 release notes](RELEASE-1.0.md). The maintainer ends its squash commit
   description with the footer `Release-As: 1.0.0` (see
   [release process](CI.md#release-process)), so Release Please proposes 1.0.0
   in its release pull request.
4. **Acceptance run** (remaining). The owner runs the
   [manual acceptance protocol](ACCEPTANCE.md) on every target system against a
   recorded release candidate: a manual `Release` dispatch build of the 1.0.0
   release pull request's head SHA (see
   [release candidates](PACKAGING.md#release-candidates)). The run includes the
   real migration of vaults written by 0.7.0. `main` stays frozen during the
   run, and every row passes or has an accepted explanation. The results are
   recorded in the 1.0.0 acceptance tracking issue.
5. **Release.** Only then is the release pull request for 1.0.0 merged, after
   its checks pass; afterwards the published installers are checked again as
   described under [version under test](ACCEPTANCE.md#version-under-test).
