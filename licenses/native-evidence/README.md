# Native component provenance

This directory contains verified upstream notice texts and artifact provenance.
It is **not** a complete native redistribution inventory and does not satisfy the
packaging approval gate. No `reviewed=true` record is provided here.

[Verified CI evidence from 2026-09-23](ci-2026-09-23/README.md) now preserves
the actual Windows x64, Linux x64 and macOS ARM64 runtime inventories, all JDK
legal-file bytes, official platform SBOMs, and their verified archive provenance.
It also records DNG-related strings in the selected macOS native binaries.

## Skiko 0.150.1

The release resolves to commit
`3956e988e6e93eaf1ee985d725049443e7845807`. Its
[build properties](https://github.com/JetBrains/skiko/blob/3956e988e6e93eaf1ee985d725049443e7845807/skiko/gradle.properties)
select Skia `m150-1f14f1166a`, commit
`1f14f1166a847cb0e148f981600bf1e3e6ca6e46`.

[provenance.json](skiko-0.150.1/provenance.json) records the official URL and
SHA-256 of each unmodified notice file. Git preserves their original bytes.
[artifacts.json](skiko-0.150.1/artifacts.json) records all five selected platform
runtime JARs, their Maven Central SHA-256 checksums and every nonempty ZIP entry's
checksum. Artifact bytes were downloaded and checked, without executing natives.
These runtime JARs contain no complete aggregate native license inventory.

The pinned [Skiko build DSL](https://github.com/JetBrains/skiko/blob/3956e988e6e93eaf1ee985d725049443e7845807/skiko/build.gradle.kts)
declares static linkage of ICU, HarfBuzz, PNG, JPEG, WebP, zlib and Expat along
with Skia's own modules. It additionally declares D3D12MemoryAllocator for
Windows JVM and piex/DNG SDK for macOS JVM. Linux declares dynamic system GL,
X11 and fontconfig libraries, with EGL on ARM64. A declared build input is
evidence for an audit; it is not a proof of the exact reachable contents of
every published binary.

The copied external notices use the revisions selected by Skia's pinned
[DEPS](https://github.com/JetBrains/skia/blob/1f14f1166a847cb0e148f981600bf1e3e6ca6e46/DEPS):

| Component | Commit | Copied notice family |
| --- | --- | --- |
| Expat | `6154446fccefbf3ca644894f598969113b0c7bcd` | COPYING |
| HarfBuzz | `9cb1fee51069b206effb4736e443b038d230789d` | COPYING |
| ICU | `364118a1d9da24bb5b770ac3d762ac144d6da5a4` | LICENSE |
| libjpeg-turbo | `e14cbfaa85529d47f9f55b0f104a579c1061f9ad` | LICENSE.md, README.ijg |
| libpng | `d5515b5b8be3901aac04e5bd8bd5c89f287bcd33` | LICENSE |
| libwebp | `845d5476a866141ba35ac133f856fa62f0b7445f` | COPYING, PATENTS |
| zlib | `646b7f569718921d7d4b5b8e22572ff6c76f2596` | LICENSE |
| D3D12MemoryAllocator | `169895d529dfce00390a20e69c2f516066fe7a3b` | LICENSE.txt |
| piex | `bb217acdca1cc0c16b704669dd6f91a1b509c406` | LICENSE |
| DNG SDK | `dbe0a676450d9b8c71bf00688bb306409b779e90` | LICENSE, source-code/technology terms, NOTICE, PATENTS |

This software is based in part on the work of the Independent JPEG Group.

### DNG SDK requires separate resolution

The selected DNG SDK revision is not covered by Skia's own BSD notice. Its
[Android build metadata](https://android.googlesource.com/platform/external/dng_sdk/+/dbe0a676450d9b8c71bf00688bb306409b779e90/Android.bp)
flags special licensing conditions and includes `legacy_by_exception_only`.
The copied [source-code terms](skiko-0.150.1/dng-sdk-LICENSE.source_code.txt)
include commercial-distribution indemnification and restrictions on documentation.
GPL compatibility and the applicable terms have **not** been established.
The macOS package must remain blocked until this is resolved, for example through
verified compatible provenance or a reviewed replacement build without that
component. These findings are not a definitive legal determination.

### Remaining native evidence

The [Skia release build](https://github.com/JetBrains/skia/blob/1f14f1166a847cb0e148f981600bf1e3e6ca6e46/tools/skia_release/build.py)
selects bundled dependency implementations and platform-specific graphics options.
Its [archive recipe](https://github.com/JetBrains/skia/blob/1f14f1166a847cb0e148f981600bf1e3e6ca6e46/tools/skia_release/archive.py)
copies some external license files; it is not an aggregate licensing report.
Per-file/subdirectory exceptions, generated Unicode data, graphics extensions,
compiler runtimes and any installer-embedded auxiliary code still require review.
Skiko also declares optional Windows ANGLE runtime artifacts separately; an audit
must distinguish these from the core runtime JAR instead of assuming their presence.

## Temurin 25 source provenance

The workflow selects Temurin major version 25, so a later run can resolve a newer
patch release. The official API resolved `jdk-25.0.4.1+1` during this review.
[Temurin provenance](temurin-25.0.4.1+1/provenance.json) records its official
source-archive URL, the publisher's archive SHA-256, source commit and build-recipe
commit. The archive has now been downloaded and independently rehashed, matching
the published checksum. Root
LICENSE, ASSEMBLY_EXCEPTION and ADDITIONAL_LICENSE_INFO were retrieved at the
recorded source commit and their actual file hashes are recorded. Their bytes
also match the downloaded source archive.

The release provides platform-specific SBOMs, including
[Windows x64](https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/OpenJDK25U-sbom_x64_windows_hotspot_25.0.4.1_1.json),
[Linux x64](https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/OpenJDK25U-sbom_x64_linux_hotspot_25.0.4.1_1.json)
and [macOS ARM64](https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/OpenJDK25U-sbom_aarch64_mac_hotspot_25.0.4.1_1.json).
The SBOMs and their metadata are now retained locally in this directory. Their
versions match the CI inventories. All collected legal files and paths match
the official platform JDK archives; the per-platform `jdk.legal.sha256` values
are retained with the CI evidence. This is not a comparison of every installed
executable, nor a complete redistribution approval. A locally installed Oracle
JDK is not evidence for a Temurin package.
