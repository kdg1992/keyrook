# CI runtime and native license evidence

The [collection run](https://github.com/kdg1992/keyrook/actions/runs/35887498871)
used commit `de0ff01748375ee7248fa30326a0f1992384b5ca`, application version
0.2.0, and Eclipse Adoptium Temurin `25.0.4.1+1-LTS`. All three collection and
artifact-upload steps succeeded. The subsequent packaging checks failed because
approved native inventories are absent; no installer was created or published.

| Target | External runtime artifacts | JDK legal files | Match official JDK archive |
| --- | ---: | ---: | --- |
| Windows x64 | 52 | 253 | All files and their paths |
| Linux x64 | 52 | 254 | All files and their paths |
| macOS ARM64 | 52 | 252 | All files and their paths |

[provenance.json](provenance.json) records the CI artifact identifiers and archive
digests, official JDK download URLs and independently checked SHA-256 digests,
per-platform legal-file fingerprints, and checksums of the retained vendor SBOMs.
The three CI ZIP digests match GitHub's artifact API. The official JDK archives
match their publisher-provided `.sha256.txt` files. Every collected JDK legal
file matches the corresponding official binary archive, including resolved
Unix symbolic links. This comparison covers the legal directory, not a bytewise
comparison of every executable installed on the runner.

Each target directory preserves the collector's original `candidate.properties`,
`runtime-artifacts.tsv`, and `jdk-legal.sha256`. The 759 legal-file records share
54 distinct byte sequences, stored once under `jdk-notices/<sha256>.txt`.
For each line `relative/path sha256` in a target's `jdk-legal.sha256`, the matching
notice file supplies the exact original bytes. The SHA-256 of the complete
manifest, including its final LF, equals `jdk.legal.sha256` in the candidate.
Windows includes small files referring to another legal file by relative path;
their destinations are included too. Reconstruct those paths when using the
notices in a distribution, rather than shipping only the content-addressed names.

The official platform CycloneDX SBOMs and accompanying metadata are retained in
[the Temurin evidence directory](../temurin-25.0.4.1+1/). Their JDK versions match
the three reports. The 120,577,922-byte official JDK source archive was downloaded
and independently hashed; its digest matches the published digest recorded in
the [source provenance](../temurin-25.0.4.1+1/provenance.json). Its root LICENSE,
ASSEMBLY_EXCEPTION and ADDITIONAL_LICENSE_INFO bytes also match the already
retained source notices. Large binary/source archives are not committed here;
their official URLs and hashes identify the files used for these comparisons.

## macOS DNG evidence

The selected `skiko-awt-runtime-macos-arm64:0.150.1` JAR includes both ARM64 and
x64 dylibs. Their digests match the previously verified
[Maven artifact entry inventory](../skiko-0.150.1/artifacts.json). A static scan
for printable ASCII strings matching `dng_`, `DNG SDK`, `Adobe DNG`, `piex::`, or
`_ZN4piex` found 1,420 matching strings in ARM64 and 1,424 in x64. Representative
strings and the binary digests are recorded in `provenance.json`. No native
library was executed during inspection.

These embedded class/function names and diagnostics corroborate the pinned
[Skiko build declaration](https://github.com/JetBrains/skiko/blob/3956e988e6e93eaf1ee985d725049443e7845807/skiko/build.gradle.kts#L64)
that links DNG SDK on macOS. They do not establish which functions are reachable
or a complete source-to-binary mapping. The pinned
[DNG SDK metadata](https://android.googlesource.com/platform/external/dng_sdk/+/dbe0a676450d9b8c71bf00688bb306409b779e90/Android.bp)
flags special licensing conditions. The retained source license contains
documentation restrictions and commercial-distribution indemnification terms.
No compatible alternative grant for this exact component has been verified.

## What this evidence does not approve

All candidates deliberately retain `reviewed=false`. These records close the
missing CI runtime inventories, JDK notice capture and official-archive comparison,
vendor SBOM capture, and source-archive checksum verification. They do not resolve
the DNG license question, prove a complete inventory of all code statically linked
into Skiko, or cover future packaging-tool additions. The approval records were
created separately in [`licenses/native/`](../../native/) after the review of
this evidence, together with the DNG SDK notices and a GPL section 7 additional
permission.

This collection covers Windows x64, Linux x64 and macOS ARM64 only. It does not
approve Linux ARM64 or macOS x64 packaging. Future JDK patches, dependency changes
or different native build options require fresh matching evidence.
