# Native sources and review: macOS ARM64

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
[runtime-artifacts.tsv](../../native-evidence/ci-2026-09-23/macos-arm64/runtime-artifacts.tsv).
Their licenses are Apache-2.0, MIT, ISC and the Bouncy Castle license, listed in
`THIRD-PARTY-NOTICES`. None of them requires distribution of source code.

## Skiko native runtime

- Artifact: [skiko-awt-runtime-macos-arm64-0.150.1.jar](https://repo.maven.apache.org/maven2/org/jetbrains/skiko/skiko-awt-runtime-macos-arm64/0.150.1/skiko-awt-runtime-macos-arm64-0.150.1.jar),
  SHA-256 `bd186dd6bf8736eb69be190107e1b3ab7bd2452ae917faaa516ef513217a5f33`, identical to the Maven Central checksum.
- Native library `libskiko-macos-arm64.dylib`, SHA-256 `4b22275236c861e1288750f3022a5851440f1ba7dec227e6d428b5ee0899175a`
- Native library `libskiko-macos-x64.dylib`, SHA-256 `d0bc2312ebe44602a2618fc0d87b8a775519be7d53989421a170e5a5cff9e3dd`
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
| piex (Google) | Apache-2.0 | None; notices included in NOTICE.txt |
| Adobe DNG SDK 1.4 (Android external/dng_sdk) | DNG SDK License Agreement and DNG patent license | None; notices included in NOTICE.txt |

### DNG SDK and piex

Static inspection found DNG SDK and piex code in this binary. The DNG SDK License
Agreement grants use, reproduction, modification, distribution and sublicensing
of the software for any purpose. Keyrook complies with its conditions: the
copyright notices and the required attribution "This product includes DNG
technology under license by Adobe Systems Incorporated." are included in
NOTICE.txt, THIRD-PARTY-NOTICES and the README. No Adobe documentation or
trademark is distributed. Keyrook is distributed free of charge and not as a
commercial product; the indemnification clause applies to whoever distributes
the software in a commercial product. The GPL compatibility of the combination
is ensured by the section 7 additional permission in
licenses/GPL-3.0-Additional-Permission.txt. The DNG SDK source is available at
[android.googlesource.com](https://android.googlesource.com/platform/external/dng_sdk/+/dbe0a676450d9b8c71bf00688bb306409b779e90).

## Bundled JDK

- Eclipse Temurin `25.0.4.1+1-LTS` (Eclipse Adoptium), GPL-2.0 with Classpath
  Exception and the terms in its `legal/` directory.
- The legal directory fingerprint `jdk.legal.sha256` matches the official
  Temurin archive of this target; the individual files are recorded in
  [jdk-legal.sha256](../../native-evidence/ci-2026-09-23/macos-arm64/jdk-legal.sha256)
  and ship in the installer under `licenses/bundled-jdk/`.
- Corresponding source: each GitHub release publishes the official Temurin
  source archive whose SHA-256 is recorded in the
  [Temurin provenance](../../native-evidence/temurin-25.0.4.1+1/provenance.json).
  The release workflow refuses publication if this archive is missing or differs.

## Keyrook

Keyrook source code for every release is available at
<https://github.com/kdg1992/keyrook> under the release tag.
