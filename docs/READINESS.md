# Development readiness

Keyrook is still in development. Installers are published for reviewed
platforms, but use synthetic data until platform acceptance is complete.

| Area | Implemented and tested | Remaining boundary |
| --- | --- | --- |
| Vault storage | Authenticated format v1, atomic writes, conflict detection, Argon2 settings, optional key files and factor replacement | No older supported format needs migration; unsupported versions are rejected. Higher-cost KDF headers require core API approval. |
| Desktop records | Eight entry types, customer/project editing, search, filters, sorting, trash, history, TOTP-secret editing with RFC 6238 code display/copy (RFC 4226/6238 test vectors) and generators | TOTP codes follow the local system clock; HOTP (counter-based) secrets and unsupported algorithm, digit or period values are refused; other `otpauth` parameters are ignored. German and English interface, selectable in the settings; only the system file chooser's buttons follow the Java runtime locale. |
| SSH | Ed25519/RSA-4096 generation, OpenSSH/PEM and MAC-verified PPK 2/3 import, validated public-key export and command copying | PPK 1, other key types and excessive KDF costs are refused. The adapter uses established providers and tested format handling; no external cryptographic audit is claimed. |
| Transfer and recovery | Automatic/manual encrypted backups, retention, authenticated restore preview, new-file restoration, encrypted export and confirmed plaintext import/export | External format support is deliberately bounded; see the exact Bitwarden/KeePass limitations in [desktop usage](DESKTOP.md). |
| Security | Inactivity/session-event locking, clipboard ownership/expiry, failed-attempt delay, password/expiry warnings and regression coverage | JVM erasure, OS event coverage and clipboard history have documented limits. Real desktop lock/sleep/resume acceptance on each supported system follows the [manual acceptance protocol](ACCEPTANCE.md). |
| Builds | Source checks on Windows, Linux and macOS, native-window lifecycle/event-routing checks, CodeQL, dependency graph/review, release automation and a packaged-runtime diagnostic | Installer, upgrade and uninstall tests are part of the [manual acceptance protocol](ACCEPTANCE.md) and pending until it is completed. Native-window tests inject controlled events; actual OS lock/sleep notifications still need acceptance. |
| Redistribution | License gate, artifact hashes, source/notice provenance, reviewed native/JDK inventories for Windows x64, Linux x64 and macOS ARM64, DNG SDK notices and a GPL section 7 additional permission | Dependency or JDK changes require a renewed inventory review. Linux ARM64 and macOS x64 are not reviewed. |

Source checks and the offscreen diagnostic do not certify real desktop session
events or installation behavior. The packaged diagnostic exercises bundled
cryptography, SSH and rendering only after the unchanged license gate permits
building; the reviewed inventories now permit that. See [packaging](PACKAGING.md) and [security](SECURITY.md) for the
required evidence and limits. No complete-release claim follows from a green
source build.

## Verification coverage

The [manual acceptance protocol](ACCEPTANCE.md) must pass on Windows, macOS and
Linux before release 1.0 is approved.

| Covered by CI | Covered by the manual acceptance protocol |
| --- | --- |
| Core and desktop tests on Windows, Linux and macOS, including format, cryptography, settings parsing, clipboard ownership logic, inactivity deadline and update-response validation | Installing the unsigned MSI, DMG, DEB and RPM, including SmartScreen and Gatekeeper steps |
| Offscreen Compose rendering and lint | Settings file location and permissions on a real installation |
| Native window checks with injected focus, minimize and dialog events under each window lock choice | Upgrade from the previous release with an existing vault and settings; uninstall leaving user data in place |
| Packaged-runtime self-test of each application image before publication | Window lock policies, OS screen lock, user switching, sleep, lid close and inactivity locking with real OS events |
| CodeQL, dependency review, license and inventory gates | Clipboard expiry and ownership against the real system clipboard |
| | Update check network behavior, keyboard-only use and language switching in the installed application |
