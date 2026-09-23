# Native sources and review: Windows x64

Reviewed on 2026-09-23 by the Keyrook copyright holder for release packaging
with Eclipse Temurin `25.0.4.1+1-LTS`, Compose Multiplatform 1.12.1 and
Gradle 9.7.1. The evidence comes from the
[CI collection run](https://github.com/kdg1992/keyrook/actions/runs/35887498871)
retained in [ci-2026-09-23](../../native-evidence/ci-2026-09-23/README.md).
This record is bound by hash in `inventory.properties`. Any change of a runtime
artifact, of the JDK or of its legal files requires a new review.

## Java runtime artifacts

All 52 external runtime artifacts of this target are recorded with their SHA-256
in `inventory.properties` and in
[runtime-artifacts.tsv](../../native-evidence/ci-2026-09-23/windows-x64/runtime-artifacts.tsv).
Their licenses are Apache-2.0, MIT, ISC and the Bouncy Castle license, listed in
`THIRD-PARTY-NOTICES`. None of them requires distribution of source code.

## Skiko native runtime

- Artifact: [skiko-awt-runtime-windows-x64-0.150.1.jar](https://repo.maven.apache.org/maven2/org/jetbrains/skiko/skiko-awt-runtime-windows-x64/0.150.1/skiko-awt-runtime-windows-x64-0.150.1.jar),
  SHA-256 `22a5e52c9fb4278f998785c5029d96f007c8eb8944f6eb0d3f4b06fb3775edcf`, identical to the Maven Central checksum.
- Native library `skiko-windows-x64.dll`, SHA-256 `5061ffbbfbad9fc8c6c8e0c1741b674c863293d3d071b061db9e6e0fc3ef1d38`
- Prebuilt by JetBrains from Skiko commit
  [`3956e988e6e93eaf1ee985d725049443e7845807`](https://github.com/JetBrains/skiko/tree/3956e988e6e93eaf1ee985d725049443e7845807)
  and Skia commit
  [`1f14f1166a847cb0e148f981600bf1e3e6ca6e46`](https://github.com/JetBrains/skia/tree/1f14f1166a847cb0e148f981600bf1e3e6ca6e46)
  with the build options of the pinned
  [Skiko build script](https://github.com/JetBrains/skiko/blob/3956e988e6e93eaf1ee985d725049443e7845807/skiko/build.gradle.kts)
  and [Skia release build](https://github.com/JetBrains/skia/blob/1f14f1166a847cb0e148f981600bf1e3e6ca6e46/tools/skia_release/build.py).
  Keyrook redistributes these binaries unmodified.
- Component revisions follow the pinned
  [Skia DEPS](https://github.com/JetBrains/skia/blob/1f14f1166a847cb0e148f981600bf1e3e6ca6e46/DEPS);
  the table in [native component provenance](../../native-evidence/README.md)
  lists each commit and the source URL and SHA-256 of each notice.

| Component | License | Source obligation |
| --- | --- | --- |
| Skiko 0.150.1 (JetBrains) | Apache-2.0 | None; notices included in NOTICE.txt |
| Skia m150-1f14f1166a (Google, JetBrains fork) | BSD-3-Clause | None; notices included in NOTICE.txt |
| Expat | MIT | None; notices included in NOTICE.txt |
| HarfBuzz | MIT (Old MIT) | None; notices included in NOTICE.txt |
| ICU | Unicode-3.0 and bundled third-party terms | None; notices included in NOTICE.txt |
| libjpeg-turbo | IJG, BSD-3-Clause, zlib | None; notices included in NOTICE.txt |
| libpng | libpng-2.0 | None; notices included in NOTICE.txt |
| libwebp | BSD-3-Clause with patent grant | None; notices included in NOTICE.txt |
| zlib | Zlib | None; notices included in NOTICE.txt |
| Wuffs | Apache-2.0 | None; notices included in NOTICE.txt |
| FreeType | FTL | None; notices included in NOTICE.txt |
| D3D12 Memory Allocator (AMD) | MIT | None; notices included in NOTICE.txt |

### DNG SDK

The Windows x64 binary contains no DNG SDK markers. The pinned Skiko build script
declares DNG SDK and piex only for macOS. The additional permission for linked
Skiko components nevertheless applies to all targets.

## Bundled JDK

- Eclipse Temurin `25.0.4.1+1-LTS` (Eclipse Adoptium), GPL-2.0 with Classpath
  Exception and the terms in its `legal/` directory.
- The legal directory fingerprint `jdk.legal.sha256` matches the official
  Temurin archive of this target; the individual files are recorded in
  [jdk-legal.sha256](../../native-evidence/ci-2026-09-23/windows-x64/jdk-legal.sha256)
  and ship in the installer under `licenses/bundled-jdk/`.
- Corresponding source: each GitHub release publishes the official Temurin
  source archive whose SHA-256 is recorded in the
  [Temurin provenance](../../native-evidence/temurin-25.0.4.1+1/provenance.json).
  The release workflow refuses publication if this archive is missing or differs.

## Keyrook

Keyrook source code for every release is available at
<https://github.com/kdg1992/keyrook> under the release tag.
