# Native packaging

Native installers are configured for Windows MSI, macOS DMG, and Linux DEB/RPM.
They are built only in the `Release` GitHub Actions workflow on the three standard
hosted runners. A package is built for that runner's actual CPU architecture;
this is not a universal macOS binary or cross-compilation. The filenames include
the target architecture. The application and package version both come from
`version.txt` (plain `MAJOR.MINOR.PATCH`, for example `0.8.0`). On macOS,
jpackage rejects bundle versions whose first number is 0, so packages of
`0.x.y` releases carry the macOS app bundle and DMG version `1.0.0`, while the
application itself, the release and the installer file names keep the real
version. From `1.0.0` on, the bundle version equals the application version.
Finder therefore shows `1.0.0` for every 0.x release; **About** always shows the
real version.
See the [Compose native distribution documentation](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html).

## Release candidates

Keyrook versions carry no pre-release suffix such as `-rc.1`, and no prerelease
is published on the release page. A release candidate is the set of artifacts of
a manual `Release` workflow dispatch (see [workflow behavior](#workflow-behavior))
on the release pull request's branch: it builds, tests and packages exactly
that head commit with the version the release will have, without creating a tag
or release. Record the commit SHA and the run URL with the test results, and
merge nothing into `main` while the candidate is being tested, because every
merge updates the release pull request and invalidates the candidate. The
[manual acceptance protocol](ACCEPTANCE.md#version-under-test) runs against
such a candidate.

## Recovering an unpublished release

Merge release-please pull requests directly into `main` using squash merge;
do not first merge them into an integration branch. Release-please tags the
release PR's merge commit. If its workflow files differ from current `main`,
GitHub can reject creation with `Resource not accessible by integration` even
when the job has `contents: write`. The normal Actions token cannot receive
the additional workflow permission described in the
[GitHub release API documentation](https://docs.github.com/en/rest/releases/releases#create-a-release).

For an unpublished `MAJOR.MINOR.PATCH` version, before or after 1.0.0, run
**Recover release pull request** on
`main`, supplying the old release PR number. It checks the version and manifest,
refuses an existing tag, release or replacement PR, removes the old PR's pending
label, and invokes release-please to generate a replacement at the same version.
It restores the old label if recovery fails and otherwise dispatches the
[release PR checks](CI.md#release-process) for the replacement. No tag or release is created by
this recovery workflow, and it uses only `GITHUB_TOKEN`. An existing draft release
for the version also blocks recovery; to replace a draft whose packaging failed,
see [When packaging or installer tests fail](#when-packaging-or-installer-tests-fail).
The single root package uses the default release branch
`release-please--branches--main`; recovery also accepts the previous
`release-please--branches--main--components--keyrook` branch.

Review the replacement's generated changelog and version files, ensure all CI
checks pass, then approve and squash-merge it directly into current `main`.
The normal Release workflow creates the tag and draft release at that merge
commit and publishes it after the installer tests pass.
If recovery fails after creating a PR, inspect that PR and the restored pending
label before retrying. Installer publication still requires the license review
below; recovering a source release does not change the reviewed inventory.

## Reviewed redistribution inventory

Reviewed inventories exist for Windows x64, Linux x64 and macOS ARM64 in
[`licenses/native/`](../licenses/native/). They were created from the
[collected CI evidence](../licenses/native-evidence/ci-2026-09-23/README.md):
52 external runtime artifacts per platform and the complete JDK legal
directories, matched against official Temurin archives, together with the
verified Skiko artifacts and [native component provenance](../licenses/native-evidence/README.md).
Each `NOTICE.txt` contains the full notices of the components linked into that
platform's Skiko binary. Each `SOURCES.md` records the exact upstream revisions,
binary digests, source availability and the review decision.

The Linux and macOS binaries contain the Adobe DNG SDK and piex. The DNG SDK
License Agreement permits distribution and sublicensing for any purpose; its
notices and the required attribution are included. Keyrook grants an additional
permission under GPL version 3 section 7 for linking with the Skiko native
libraries and their components; see
[GPL-3.0-Additional-Permission.txt](../licenses/GPL-3.0-Additional-Permission.txt).
The Windows x64 binary contains no DNG SDK markers.

Linux ARM64 and macOS x64 are not reviewed and cannot be packaged.

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
available beside the binaries.

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
copies of `LICENSE`, `THIRD-PARTY-NOTICES`, the repository license texts under
`licenses/` (including the GPL additional permission and every reviewed
`licenses/native/<os>-<arch>/` inventory, `NOTICE.txt` and `SOURCES.md`), and the
packaging JDK's `legal/` directory through Compose application resources. A
platform-specific archive of those files accompanies each release. The collected
review evidence in `licenses/native-evidence/` stays in the source repository and
is excluded from installers, notice archives and JAR resources; the component
notices it contains are already part of each `NOTICE.txt`, and links from the
packaged `SOURCES.md` files to that evidence refer to the source repository. The
approval gate does not hash packaged resources, so this does not affect the
reviewed inventories.

The application icon is original project artwork under GPL-3.0-or-later, not
third-party material. `scripts/generate-icons.py` (Python 3 standard library
only) draws it from its geometry and writes `app/icons/keyrook.svg`, the
installer icons `app/icons/keyrook.png` (Linux), `keyrook.ico` (Windows) and
`keyrook.icns` (macOS), and the window icon resource
`app/src/main/resources/app/keyrook/app/keyrook-icon.png`; `--check` verifies
that the committed files match. These are project files packaged with the
application, not resolved runtime artifacts, so they do not change the native
inventory above.

## Bundled JDK security updates

Every installer contains its own Java runtime, so a Java security release is a
Keyrook release. The Temurin build is pinned by hash in the retained evidence
and read by [`scripts/collect-jdk-archive.mjs`](../scripts/collect-jdk-archive.mjs)
(the evidence directory, the runtime-evidence file and the expected
`jdkVersion` near the top of the script). Dependabot does not update this pin,
because it is neither a Gradle nor a GitHub Actions dependency; the Temurin
action's `java-version` in `release.yml` is only a cache label. Check for a new
Temurin 25 update after each quarterly OpenJDK security release (January, April,
July and October), and immediately for an out-of-band fix. To move to it:

1. Record the new version's official archives from the Adoptium release page:
   the source archive and the Windows x64, Linux x64 and macOS ARM64 JDK
   archives with their published SHA-256 values, the platform SBOMs and the
   legal texts, in a new `licenses/native-evidence/temurin-<version>/`
   directory with its `provenance.json`, following the existing one.
2. Point the pin in `scripts/collect-jdk-archive.mjs` at the new evidence and
   version, and add the per-platform runtime archive records it reads.
3. Dispatch `Release` manually. Packaging then stops at the approval gate
   because the reviewed inventories still name the old JDK, but the
   `native-inventory-<platform>` evidence is uploaded. Retain it as a new
   `licenses/native-evidence/ci-<date>/` directory, as described in
   [collecting reproducible evidence](#collecting-reproducible-evidence).
4. Review the JDK `legal/` changes against the previous evidence, then update
   `jdk.version`, `jdk.legal.sha256`, `SOURCES.md` and the notice hashes of each
   `licenses/native/<os>-<arch>/` inventory. The external runtime artifacts do
   not change, so their records stay as they are.
5. Dispatch `Release` again. Expected: the approval gate, the packaged
   self-tests and the installer tests pass on all three platforms, and the
   packaged `legal/` directory matches the new evidence.
6. Merge the change as `fix(deps): update the bundled JDK to Temurin <version>`
   so that Release Please proposes a patch release, and release it promptly
   through the normal release pull request.

## Workflow behavior

A manual `workflow_dispatch` tests and packages all three platforms without
creating a tag or release. Successful verification outputs are retained for seven
days. The separate evidence artifacts remain downloadable for a renewed license
review even when a packaging attempt fails at the approval gate.
Use it to confirm packaging and the [installer tests](#installer-tests) before
merging a release pull request that follows packaging changes; in a release run,
a failure in either blocks publication.

After packaging, the workflow launches the generated application image with its
bundled runtime and `--self-test <new-report-path>`. This explicit diagnostic
performs an in-memory vault encryption/decryption roundtrip, an encrypted
Ed25519 export/import roundtrip and offscreen desktop rendering. It uses only
synthetic data, accesses no user vault and writes a fixed success marker to a
new file. A failure, missing marker or two-minute timeout blocks publication.
This verifies the application image; installation, upgrade and removal are
covered by the [installer tests](#installer-tests), and operating-system
session-lock behavior by the [manual acceptance protocol](ACCEPTANCE.md). The diagnostic is covered by
ordinary source tests and runs from each packaged launcher before publication.

### Linux desktop menu registration

jpackage's DEB and RPM scripts register the menu entry with `xdg-desktop-menu`.
xdg-utils refuses with exit status 3 ("No writable system menu directory found")
when neither `/usr/share/desktop-directories` nor
`/usr/local/share/desktop-directories` exists, which is common on servers,
containers, WSL and minimal window-manager installations. The DEB scripts run
under `set -e`, so packages up to 0.5.0 stay half-configured on such systems and
cannot be removed with `apt-get remove`. Compose passes its own jpackage resource
directory, so the Linux DEB task rebuilds the finished package's control archive
with the `postinst` registration and `prerm` removal made non-fatal: they print
`keyrook: desktop menu entry not updated` instead. The payload member is copied
unchanged and the build fails if jpackage's script lines change. Without the
directory, the application is still installed and runs from
`/opt/keyrook/bin/Keyrook`, but has no menu entry.

The RPM is not rewritten. RPM treats a failing `%post` as a warning, but its
`%preun` ends with the same menu removal, so on a system without the directory
`dnf remove keyrook` can fail; `rpm -e --nopreun keyrook` removes it there.
The Fedora installer test logs whether its image has the directory and whether
the menu entry was registered; RPM installation without the directory is not
tested separately.

### Installer tests

The `installer-tests` job runs after all packages are built, on the same three
standard runners, for both manual and release runs. It reuses the uploaded
`packages-<platform>` artifact (no rebuild), verifies each installer against the
run's checksum manifest, and then, per format:

| Format | Where | Fresh install | Upgrade | Removal |
| --- | --- | --- | --- | --- |
| DEB | Ubuntu runner, first without and then with `/usr/share/desktop-directories` | `apt-get install ./…deb`; `dpkg-query` shows one installed version; with the directory, the menu entry exists | old DEB, then new DEB over it, with the directory | `apt-get remove keyrook`; package no longer installed, install directory and menu entry gone |
| RPM | `fedora:44` container, pinned by digest, new container per scenario | `dnf install /…rpm`; `rpm -q` shows exactly one version | old RPM, then new RPM over it | `dnf remove keyrook`; package gone, install directory gone |
| MSI | Windows runner account | `msiexec /i … /qn`; exactly one `Keyrook` uninstall entry with the new version | old MSI, then new MSI (same upgrade code, major upgrade) | `msiexec /x … /qn`; entry gone, launcher gone, no files left in the install directory |
| DMG | macOS runner | attach read-only with `-nobrowse`, copy `Keyrook.app` into a temporary Applications directory, compare with the image | old bundle replaced completely by the new one, as Finder's Replace does | bundle deleted |

After each fresh install and each upgrade, the installed launcher runs
`--self-test` against a new report file in a temporary directory and must write
exactly `Keyrook runtime check passed`. The launcher path is taken from the
package manager (`dpkg -L` / `rpm -ql`, normally `/opt/keyrook/bin/Keyrook`), from
the `INSTALLDIR` property in the verbose MSI log (normally
`%LOCALAPPDATA%\Keyrook\Keyrook.exe` for the per-user MSI), or from the copied
bundle (`Keyrook.app/Contents/MacOS/Keyrook`). The self-test is headless and
renders offscreen, so no display server is needed.

The upgrade source is the highest published, non-draft, non-prerelease release
whose version is strictly lower than the package under test and which ships an
installer for the same platform, architecture and format together with
`SHA256SUMS.txt`. The release being built is still a draft at this point and
is therefore never its own upgrade source. The old installer is downloaded with
`gh release download` using the read-only `GITHUB_TOKEN` and checked against that
release's `SHA256SUMS.txt`. When no such release exists, the upgrade scenario is
skipped with a workflow notice; the fresh-install scenario still runs. Verbose
Windows Installer logs are uploaded only when the job fails and expire after
seven days. A failing installer test blocks publication.

These tests do not cover:

- the interactive installer UI, the MSI directory chooser, Start menu and desktop
  shortcuts, file associations, or launching the application in a real user
  desktop session;
- SmartScreen, Smart App Control, Gatekeeper, quarantine attributes and the
  macOS "Open Anyway" flow: installers from the artifact and the release API carry
  no download quarantine, and nothing is signed or notarized;
- per-machine (administrator) Windows installation, installing as a standard user
  without administrator rights, or installing for another account; the runner
  account is an administrator with UAC prompts disabled;
- DMG license acceptance by a person and drag-and-drop into `/Applications`;
  macOS has no uninstaller, so removal is deleting the bundle, and data written to
  `~/Library` is not removed by that;
- whether the RPM's declared dependencies are complete: the Fedora container
  first installs a desktop library baseline (Mesa GL, X11 client libraries,
  FreeType, Fontconfig, DejaVu fonts) that a Fedora workstation normally has; the
  declared requirements are printed for review. The DEB runs on a runner image
  that already contains many desktop libraries;
- other distributions or releases, ARM64 Linux, x64 macOS, and upgrades that skip
  more than one version;
- migration of user vaults and settings across versions, since the self-test uses
  only synthetic in-memory data.

The Fedora image digest is not updated by Dependabot; review and update it
manually alongside other workflow pins.

On `main` pushes, release-please maintains the version/changelog pull request.
When the release PR is merged, release-please creates the `vX.Y.Z` tag at the
merge commit and a **draft** GitHub release (`"draft": true` and
`"force-tag-creation": true` in `release-please-config.json`; without the second
option GitHub would create the tag only on publication, and release-please would
not find the previous release). A draft is invisible to the public, to
`releases/latest` and therefore to the application's update check. The same
workflow then builds the installers from the tag. Build and installer-test jobs
have read-only permissions. Only after all builds and installer tests succeed
does the separate `publish` job, the only one with `contents: write` besides
release-please itself, verify their checksums, find the draft by its tag in the
release list (the tag endpoint does not return drafts), check that it targets
the tagged commit, upload the complete installer set, notice archives, `LICENSE`,
`THIRD-PARTY-NOTICES` and combined `SHA256SUMS.txt`, and append the unsigned-installation
instructions. Its last step publishes the draft and marks it as the latest
release. Temporary transfer artifacts expire after one day; permanent downloads
are release assets. No code from an artifact is executed by the publishing job.
Each native job has a 40-minute limit. Linux installs `rpm` before packaging.
The configured modules include desktop, XML and cryptography support; the
installer tests verify them through the installed launcher's self-test.

### When packaging or installer tests fail

The release stays an unpublished draft; nothing is announced. Its tag already
exists, and the release PR is labelled `autorelease: tagged`, so later `main`
pushes neither recreate it nor include new commits in it.

- **Transient failure** (runner, network, download): rerun the failed jobs of
  that Release run. The publish job replaces assets left by an interrupted
  upload and refuses to touch a release that is already published.
- **Defect in the tagged code or workflow**: the tag cannot move, so replace the
  draft with a new release PR at the same version. First merge the fix into
  `main` while the draft and tag still exist. Then discard the draft and its tag,
  change the old release PR's label from `autorelease: tagged` back to
  `autorelease: pending`, and immediately run
  [Recover release pull request](#recovering-an-unpublished-release) on `main`
  with that PR number; do not push to `main` in between, because the next
  Release run would otherwise recreate the draft at the old commit.

To discard a broken draft (maintainer with write access):

```bash
gh release delete vX.Y.Z --cleanup-tag --yes   # deletes the draft and its tag
git ls-remote --tags origin vX.Y.Z             # must print nothing
gh pr edit <release PR> --remove-label 'autorelease: tagged' --add-label 'autorelease: pending'
```

Never publish a draft by hand: that bypasses the checksum and installer-test
gate. A published release is never modified by the workflow; a defect found after
publication requires a new version.

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
