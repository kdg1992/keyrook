# CI runtime evidence after the Bouncy Castle 1.87 / SSHD 2.20.1 update

The [collection run](https://github.com/kdg1992/keyrook/actions/runs/45033725079)
used commit `342ea03ef441ce680944673432699781c88b6ef0`, application version
1.4.0, Compose Multiplatform 1.12.2, Gradle 9.9.1 and Eclipse Adoptium Temurin
`25.0.5+8-LTS`. All three collection and artifact-upload steps succeeded, the
approval gate accepted the updated inventories on every target, and the
packaging and installer-test jobs passed. Nothing was tagged or published.

| Target | External runtime artifacts | JDK legal files | JDK legal manifest |
| --- | ---: | ---: | --- |
| Windows x64 | 52 | 253 | Byte-identical to [ci-2026-10-27-temurin-25.0.5](../ci-2026-10-27-temurin-25.0.5/README.md) |
| Linux x64 | 52 | 254 | Byte-identical to [ci-2026-10-27-temurin-25.0.5](../ci-2026-10-27-temurin-25.0.5/README.md) |
| macOS ARM64 | 52 | 252 | Byte-identical to [ci-2026-10-27-temurin-25.0.5](../ci-2026-10-27-temurin-25.0.5/README.md) |

[provenance.json](provenance.json) records the CI artifact identifiers, the ZIP
digests (which match GitHub's artifact API) and the SHA-256 of every retained
file. Each target directory preserves the collector's original
`candidate.properties`, `runtime-artifacts.tsv` and `jdk-legal.sha256`.

Compared with ci-2026-10-27-temurin-25.0.5 the set of runtime coordinates is the
same; only versions and hashes changed (Bouncy Castle `bcprov`, `bcpkix` and
`bcutil` 1.87, Apache MINA SSHD common 2.20.1, kotlinx.serialization core and
JSON 1.13.0). The new JAR digests equal the checksums published on Maven
Central. Every runtime artifact record and the JDK fields equal the reviewed
`inventory.properties` of its target. The bundled JDK is unchanged, so its legal
texts remain in [ci-2026-09-23/jdk-notices](../ci-2026-09-23/jdk-notices/) and
the [Temurin evidence](../temurin-25.0.5+8/provenance.json) still applies.
