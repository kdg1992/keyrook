# Development readiness

The 0.3.0 development branch extends the desktop application. It is not an
approved installer release. Use synthetic data until platform acceptance and
redistribution review are complete.

| Area | Implemented and tested | Remaining boundary |
| --- | --- | --- |
| Vault storage | Authenticated format v1, atomic writes, conflict detection, Argon2 settings, optional key files and factor replacement | No older supported format needs migration; unsupported versions are rejected. Higher-cost KDF headers require core API approval. |
| Desktop records | Eight entry types, customer/project editing, search, filters, sorting, trash, history, TOTP-secret editing and generators | TOTP values are stored, not used to generate one-time codes. English catalogs are prepared; the interface remains German. |
| SSH | Ed25519/RSA-4096 generation, OpenSSH/PEM and MAC-verified PPK 2/3 import, validated public-key export and command copying | PPK 1, other key types and excessive KDF costs are refused. The adapter uses established providers and tested format handling; no external cryptographic audit is claimed. |
| Transfer and recovery | Automatic/manual encrypted backups, retention, authenticated restore preview, new-file restoration, encrypted export and confirmed plaintext import/export | External format support is deliberately bounded; see the exact Bitwarden/KeePass limitations in [desktop usage](DESKTOP.md). Backup settings are session-local. |
| Security | Inactivity/session-event locking, clipboard ownership/expiry, failed-attempt delay, password/expiry warnings and regression coverage | JVM erasure, OS event coverage and clipboard history have documented limits. Real desktop lock/sleep/resume acceptance remains pending on each supported system. |
| Builds | Source checks on Windows, Linux and macOS, native-window lifecycle/event-routing checks, CodeQL, dependency graph/review, release automation and a packaged-runtime diagnostic | Packaged-launcher execution, installer/upgrade/uninstall tests and a successful manual packaging run are pending. Native-window tests inject controlled events; actual OS lock/sleep notifications still need acceptance. |
| Redistribution | License gate, artifact hashes, source/notice provenance and separate evidence review | Exact native/JDK inventories are not approved. DNG SDK terms/provenance in the macOS and Linux binaries remain unresolved. Installer creation/publication stays blocked; a DNG-free replacement build or verified applicable license grant is needed. |

Source checks and the offscreen diagnostic do not certify real desktop session
events or installation behavior. The packaged diagnostic exercises bundled
cryptography, SSH and rendering only after the unchanged license gate permits
building. See [packaging](PACKAGING.md) and [security](SECURITY.md) for the
required evidence and limits. No complete-release claim follows from a green
source build.
