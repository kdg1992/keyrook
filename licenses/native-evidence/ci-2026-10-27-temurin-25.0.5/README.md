# CI evidence for the bundled JDK update to Temurin 25.0.5+8

The [collection run](https://github.com/kdg1992/keyrook/actions/runs/40520479241)
used commit `1f1ac5fa39676d9f4888af414622eb0316baf696` and packaged with the
hash-pinned Eclipse Adoptium Temurin `25.0.5+8-LTS` archives recorded in the
[Temurin evidence](../temurin-25.0.5+8/provenance.json). All three collection and
artifact-upload steps succeeded, the approval gate accepted the updated
inventories on every target, and the packaging and installer-test jobs passed.
Nothing was tagged or published.

| Target | External runtime artifacts | JDK legal files | JDK legal manifest |
| --- | ---: | ---: | --- |
| Windows x64 | 52 | 253 | Matches the official archive; byte-identical to [ci-2026-09-23](../ci-2026-09-23/README.md) |
| Linux x64 | 52 | 254 | Matches the official archive; byte-identical to [ci-2026-09-23](../ci-2026-09-23/README.md) |
| macOS ARM64 | 52 | 252 | Matches the official archive; byte-identical to [ci-2026-09-23](../ci-2026-09-23/README.md) |

[provenance.json](provenance.json) records the CI artifact identifiers, the ZIP
digests (which match GitHub's artifact API), the official JDK archives and the
SHA-256 of every retained file. Each target directory preserves the collector's
original `candidate.properties`, `runtime-artifacts.tsv` and `jdk-legal.sha256`.

Each collected `jdk-legal.sha256` is byte-identical to the manifest computed from
the corresponding official archive, with symbolic links resolved, and to the
25.0.4.1+1 manifest of ci-2026-09-23. The JDK legal texts therefore remain in
[ci-2026-09-23/jdk-notices](../ci-2026-09-23/jdk-notices/). The runtime artifact
records equal [ci-2026-10-27](../ci-2026-10-27/README.md) and the reviewed
inventories; only `jdk.version` changed.
