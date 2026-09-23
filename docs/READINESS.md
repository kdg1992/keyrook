# Development readiness

The 0.3.0 development branch extends the desktop application. It is not an
approved installer release. Use synthetic data until platform acceptance and
redistribution review are complete.

| Area | Implemented and tested | Remaining boundary |
| --- | --- | --- |
| Vault storage | Authenticated format v1, atomic writes, conflict detection, Argon2 settings, optional key files and factor replacement | No older supported format needs migration; unsupported versions are rejected. Higher-cost KDF headers require core API approval. |
| Desktop records | Eight entry types, customer/project editing, search, filters, sorting, trash, history, TOTP-secret editing and generators | TOTP values are stored, not used to generate one-time codes. English catalogs are prepared; the interface remains German. |
| SSH | Ed25519/RSA-4096 generation, OpenSSH/PEM import, validated public-key export and command copying | Direct PuTTY PPK import remains unsupported because the selected parser does not verify its MAC. Use the documented external conversion procedure. |
| Transfer and recovery | Automatic/manual encrypted backups, retention, authenticated restore preview, new-file restoration, encrypted export and confirmed plaintext import/export | External format support is deliberately bounded; see the exact Bitwarden/KeePass limitations in [desktop usage](DESKTOP.md). Backup settings are session-local. |
| Security | Inactivity/session-event locking, clipboard ownership/expiry, failed-attempt delay, password/expiry warnings and regression coverage | JVM erasure, OS event coverage and clipboard history have documented limits. Real desktop lock/sleep/resume acceptance remains pending on each supported system. |
| Builds | Source checks on Windows, Linux and macOS, CodeQL, dependency graph/review, release automation and a packaged-runtime diagnostic | Packaged-launcher execution, installer/upgrade/uninstall tests and a successful manual packaging run are pending. |
| Redistribution | License gate, artifact hashes, source/notice provenance and separate evidence review | Exact native/JDK inventories are not approved. DNG SDK terms/provenance in the macOS dependency remain unresolved. Installer creation/publication stays blocked. |

Source checks and the offscreen diagnostic do not certify real desktop session
events or installation behavior. The packaged diagnostic exercises bundled
cryptography, SSH and rendering only after the unchanged license gate permits
building. See [packaging](PACKAGING.md) and [security](SECURITY.md) for the
required evidence and limits. No complete-release claim follows from a green
source build.
