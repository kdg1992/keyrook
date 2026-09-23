# Native packaging

Native installers are configured for Windows MSI, macOS DMG, and Linux DEB/RPM.
They are built only in the `Release` GitHub Actions workflow on the three standard
hosted runners. A package is built for that runner's actual CPU architecture;
this is not a universal macOS binary or cross-compilation. The filenames include
the target architecture. The application and package version both come from
`version.txt`, including development versions such as `0.1.0`.
See the [Compose native distribution documentation](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html).

## Recovering an unpublished release

Merge release-please pull requests directly into `main` using squash merge;
do not first merge them into an integration branch. Release-please tags the
release PR's merge commit. If its workflow files differ from current `main`,
GitHub can reject creation with `Resource not accessible by integration` even
when the job has `contents: write`. The normal Actions token cannot receive
the additional workflow permission described in the
[GitHub release API documentation](https://docs.github.com/en/rest/releases/releases#create-a-release).

For an unpublished `0.x.y` version, run **Recover release pull request** on
`main`, supplying the old release PR number. It checks the version and manifest,
refuses an existing tag, release or replacement PR, removes the old PR's pending
label, and invokes release-please to generate a replacement at the same version.
It restores the old label if recovery fails. No tag or release is created by
this recovery workflow, and it uses only `GITHUB_TOKEN`.
The single root package uses the default release branch
`release-please--branches--main`; recovery also accepts the previous
`release-please--branches--main--components--keyrook` branch.

Review the replacement's generated changelog and version files, ensure all CI
checks pass, then approve and squash-merge it directly into current `main`.
The normal Release workflow creates the tag and release at that merge commit.
If recovery fails after creating a PR, inspect that PR and the restored pending
label before retrying. Installer publication still requires the license review
below; recovering a source release does not approve redistribution.

## Current redistribution block

**Installer creation and publication are blocked until the exact native library
and bundled JDK license inventory has been reviewed.**

The existing [desktop notices](../licenses/Desktop-Third-Party.md) identify the
JVM libraries and Skiko/Skia, but do not contain a verified, complete inventory
of the native libraries embedded in every platform binary. Building a working
installer does not resolve this gap. No reviewed inventory is shipped yet.

[Native component provenance](../licenses/native-evidence/README.md) now contains
the pinned dependency notice texts, verified runtime-JAR checksums and Temurin
source records. It also identifies special licensing conditions in the DNG SDK
found in the macOS and Linux native binaries. Its compatibility has not been established;
this requires resolution in addition to finishing the artifact/JDK inventory.

[The collected CI evidence](../licenses/native-evidence/ci-2026-09-23/README.md)
now includes 52 external runtime artifacts per platform and the complete JDK
legal directories from Windows x64, Linux x64 and macOS ARM64, matched against
official Temurin archives. Vendor SBOMs and verified source-archive provenance
are retained too. These are evidence records, not approved native inventories;
the DNG question and complete native component review remain open.

A DNG-free replacement requires rebuilding both Skia and the matching Skiko
native libraries, disabling `skia_use_dng_sdk` and `skia_use_piex` and removing
the corresponding Skiko link inputs. Removing a single link declaration is
insufficient: the Linux binary contains defined DNG functions despite their
absence from that explicit link list. Replacement binaries need reproducible
build provenance, actual link/component evidence and a fresh inventory review.
Additional pinned Wuffs and FreeType notices are retained with the component
evidence; neither those notices nor a rebuilt binary automatically approve
redistribution.

`:app:checkNativeDistributionLicenses` fails explicitly when an inventory is
missing or no longer matches the resolved artifacts. Every jlink/jpackage task
depends on this check. Native image/installer tasks additionally refuse execution
outside GitHub Actions. Regular source builds and tests remain available locally.
There is no Gradle switch to ignore the inventory check.

Release packaging pins Temurin `25.0.4.1+1`, matching the retained JDK evidence.
After all platform packages pass verification, publication also downloads the
matching official JDK source archive, verifies its recorded SHA-256, and includes
it with its provenance and the release checksums. A failed source download or
checksum mismatch prevents publication. This makes the matching JDK source
available beside the binaries; it does not approve the remaining native graphics
components or replace their separate source and license review.

A reviewed inventory for each packaging target belongs in
`licenses/native/<os>-<arch>/` and must contain:

- `NOTICE.txt`: complete component notices for the actual native binaries,
  including codec, font, Unicode and graphics dependencies, with their licenses.
- `SOURCES.md`: exact upstream revisions, build options, component provenance,
  corresponding source availability and redistribution review, including the
  bundled JDK. This must cover the actual build, not just a generic project URL.
- `inventory.properties`: `reviewed=true`, exact `jdk.vendor` and `jdk.version`
  matching the packaging JVM's `java.vendor` and `java.runtime.version`, and
  SHA-256 values described below.

The property `artifact.<group>:<module>:<version>/<filename>.sha256` is required
for **every** external resolved runtime artifact, with no missing or additional
artifact records. The collector escapes colons as `\:` in Java properties files.
Identical filenames from different Maven coordinates remain separate records;
duplicate full identities are rejected. The properties
`notice.NOTICE.txt.sha256` and `notice.SOURCES.md.sha256` bind the reviewed text.
`jdk.legal.sha256` hashes the UTF-8 concatenation of all JDK `legal/` file records,
sorted lexicographically, each formatted as `relative/path SHA256\n`, using forward
slashes. Hashes are lowercase hexadecimal. These checks detect changes; they do
not replace the human review of the notices and source obligations. New dependency
or JDK builds require a renewed inventory review.

### Collecting reproducible evidence

Run `./gradlew :app:collectNativeDistributionInventory` on the intended host to
collect the inputs for that review. This task resolves dependencies and hashes
files; it does not compile native code, run jlink/jpackage or create installers.
It is independent of `check` and of the approval gate. The result is written to
`app/build/reports/native-inventory/<os>-<arch>/`:

- `candidate.properties` always has `reviewed=false`. It records exact external
  runtime artifact hashes, JDK vendor/runtime version/legal hash and build-tool
  versions, but no approved notice or source-record hashes.
- `runtime-artifacts.tsv` maps each runtime Maven coordinate to its artifact
  filename and SHA-256.
- `jdk-legal.sha256` records relative JDK legal paths and their individual hashes;
  `jdk-legal/` contains those public notice files for review.
- `README.txt` states that collected evidence does not authorize redistribution.

Records are sorted, use fixed line endings and have no generated timestamp. The
same resolved artifacts and JDK produce identical report bytes. The collector
uses the same digest routines as the gate and refuses cross-target collection.
It records no machine username, absolute dependency/JDK location, environment
dump, vault contents or secrets. Existing unexpected files in the report root
cause a failure instead of being included in an uploaded report.

The three release build jobs collect and upload these reports as
`native-inventory-linux`, `native-inventory-windows` and
`native-inventory-macos` before running the unchanged approval gate. Reports
expire after seven days and remain available when the subsequent gate rejects
missing or stale approved records. A failed collector does not upload partial
evidence. The inventory upload grants no publication permission and cannot make
the packaging job green after an approval failure. Reports generated with a local
Oracle JDK still cannot approve the Temurin runtime selected by GitHub Actions.

The JDK runtime retains its own legal files. The installer also receives readable
copies of `LICENSE`, `THIRD-PARTY-NOTICES`, all repository license texts, and the
packaging JDK's `legal/` directory through Compose application resources. A
platform-specific archive of those files accompanies each release.

## Workflow behavior

A manual `workflow_dispatch` tests and packages all three platforms without
creating a tag or release. Successful verification outputs are retained for seven
days. Currently the missing inventory stops the run before installer creation.
The separate unreviewed evidence artifacts remain downloadable for the license
review even when that packaging attempt fails at the approval gate.
The first complete manual run and installation smoke tests on all supported
platforms must succeed before a release pull request is merged.

After packaging, the workflow launches the generated application image with its
bundled runtime and `--self-test <new-report-path>`. This explicit diagnostic
performs an in-memory vault encryption/decryption roundtrip, an encrypted
Ed25519 export/import roundtrip and offscreen desktop rendering. It uses only
synthetic data, accesses no user vault and writes a fixed success marker to a
new file. A failure, missing marker or two-minute timeout blocks publication.
This verifies the application image; it does not replace installation, upgrade,
uninstall or operating-system session-lock tests. The diagnostic is covered by
ordinary source tests, but execution from each packaged launcher remains pending
until the license gate permits packaging.

On `main` pushes, release-please maintains the version/changelog pull request.
When it creates a release, the same workflow builds the installers. Build jobs
have read-only permissions. Only after all builds succeed does a separate job
verify their checksums and upload the complete installer set, notice archives,
`LICENSE`, `THIRD-PARTY-NOTICES`, and combined `SHA256SUMS.txt`. Temporary transfer
artifacts expire after one day; permanent downloads are release assets. No code
from an artifact is executed by the publishing job. Each native job has a
40-minute limit. Linux installs `rpm` before packaging.

If a build fails after release-please creates a tag/release, the release may exist
without installer assets. It must not be advertised as a complete download.
Correct the cause and rerun the failed jobs for that workflow. No secondary tag
workflow is required. The configured modules include desktop, XML and cryptography
support; packaged installation tests still need to verify the actual runtime.

Code signing/notarization is deliberately unconfigured. A future signing change
must add protected credentials and a reviewed signing step before checksums and
publication, without weakening the inventory or test checks.

<!-- unsigned-start -->
## Unsigned installers

These installers are unsigned; the macOS application is not Apple-notarized.
Download only from this project's release page and compare the file's SHA-256
with `SHA256SUMS.txt` before installing.

- Windows: if SmartScreen displays an unknown-publisher warning, inspect the
  download source and checksum first. When available, **More info → Run anyway**
  permits this individual installer. Organization policy or Smart App Control may
  prevent that exception; do not disable system-wide protections.
- macOS: after opening the application once, **System Settings → Privacy &
  Security → Open Anyway** may allow an exception for that application. Follow
  [Apple's instructions](https://support.apple.com/en-gb/102445); do not disable
  Gatekeeper globally.
- Linux: use the distribution's package installer for the downloaded DEB or RPM.
  These packages are not signed by a distribution repository.

Windows behavior depends on the active protection and administrator policy;
see [Microsoft's SmartScreen documentation](https://learn.microsoft.com/windows/security/threat-protection/microsoft-defender-smartscreen/microsoft-defender-smartscreen-overview).
<!-- unsigned-end -->
