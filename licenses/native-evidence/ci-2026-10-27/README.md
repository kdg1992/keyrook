# CI runtime evidence after the Compose 1.12.2 / Skiko 0.150.2 update

The [collection run](https://github.com/kdg1992/keyrook/actions/runs/40511327420)
used commit `d225436c7edce1c2316d1408129baf7305b70f85`, application version
1.1.0, Compose Multiplatform 1.12.2, Gradle 9.8.2 and Eclipse Adoptium Temurin
`25.0.4.1+1-LTS`. All three collection and artifact-upload steps succeeded, the
approval gate accepted the updated inventories on every target, and the
packaging and installer-test jobs passed. Nothing was tagged or published.

| Target | External runtime artifacts | JDK legal files | JDK legal manifest |
| --- | ---: | ---: | --- |
| Windows x64 | 52 | 253 | Byte-identical to [ci-2026-09-23](../ci-2026-09-23/README.md) |
| Linux x64 | 52 | 254 | Byte-identical to [ci-2026-09-23](../ci-2026-09-23/README.md) |
| macOS ARM64 | 52 | 252 | Byte-identical to [ci-2026-09-23](../ci-2026-09-23/README.md) |

[provenance.json](provenance.json) records the CI artifact identifiers, the ZIP
digests (which match GitHub's artifact API) and the SHA-256 of every retained
file. Each target directory preserves the collector's original
`candidate.properties`, `runtime-artifacts.tsv` and `jdk-legal.sha256`.

Compared with ci-2026-09-23 the set of runtime coordinates is the same; only
versions and hashes changed (Compose 1.12.2, Skiko 0.150.2, Kotlin 2.4.21,
kotlinx.serialization 1.12.0, Apache MINA SSHD common 2.20.0). The Compose JARs
of 1.12.2 have the same bytes as those of 1.12.1. Every runtime artifact record
and the JDK fields equal the reviewed `inventory.properties` of its target, and
each Skiko runtime digest equals the Maven Central checksum recorded in
[the Skiko 0.150.2 evidence](../skiko-0.150.2/artifacts.json). The bundled JDK is
unchanged, so its legal texts remain in
[ci-2026-09-23/jdk-notices](../ci-2026-09-23/jdk-notices/) and the Temurin
evidence there still applies.
