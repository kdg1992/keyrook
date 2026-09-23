# Builds, dependency review and releases

## Verification

Run `./gradlew check` with JDK 25 (`.\gradlew.bat check` on Windows). The root task runs all core tests, source-header/whitespace lint and a runtime dependency check that keeps JUnit and other test tools out of the application. This lightweight lint is not a full Kotlin style or semantic analyzer; CodeQL provides separate security analysis.

The `CI` workflow runs on pull requests, pushes to `main` and manual dispatch. Its stable build checks are `build-linux`, `build-windows` and `build-macos`. Each full build validates the Gradle wrapper and uses Temurin 25. Only changes entirely confined to Markdown, excluding `CHANGELOG.md`, skip expensive Windows/macOS steps. Those jobs still complete successfully; Linux runs verification. Renames are treated as deletion plus addition so a source-file deletion cannot be hidden by renaming it to Markdown. Failed path classification fails all three build checks.

`pr-title` checks Conventional Commits using the title as data, never as shell code. Editing a title reruns the check. Tests are not disabled to accommodate CI failures. Reports are uploaded on failure and expire after seven days.

CodeQL uses the stable 2.27.1 bundle explicitly because the action's default 2.27.0 extractor does not support Kotlin 2.4.20. Review this bundle pin alongside Kotlin and CodeQL action updates.

## Dependency graph and review

`dependency-submission.yml` resolves all Gradle dependencies on `main` without a full build and submits the graph. Runtime classpaths are marked as runtime; test and build-tool dependencies remain in the graph as development dependencies.

For a PR, `dependency-submission-pr.yml` generates a graph with read-only repository permissions and uploads it as a seven-day artifact. `dependency-submission-upload.yml` runs only after that named workflow succeeds. It uses the pinned Gradle action to retrieve the triggering run's artifacts and submit them. This privileged job has no checkout, Gradle execution, shell commands or cache restoration; it does not execute PR code. `actions: read` is needed to retrieve that other run's artifact.

`dependency-review.yml` waits up to 15 minutes for snapshots. High/critical vulnerabilities in every scope fail the check. Licenses must be on the configured GPL-3-compatible allowlist; missing/unresolved licenses also fail. A final API check rejects remaining snapshot warnings, because the upstream action can proceed after its retry timeout. Missing graphs must never be interpreted as a clean review.

The reviewed JUnit versions have narrow package/version exceptions because EPL-2.0 JUnit is separate test tooling. `checkRuntimeDependencies` enforces its absence from the runtime. There is also a single-revision exception for Gradle Actions: its repository includes both MIT code and a proprietary cache component, so GitHub cannot assign one license to the whole repository. Every workflow explicitly selects `cache-provider: basic`, the MIT implementation, and never uses enhanced caching. Updating these exceptions requires a fresh review. None of these exceptions exempts a dependency from vulnerability checks.

The Dependabot configuration schedules Monday updates for Gradle and GitHub Actions. Bouncy Castle and sshj stay outside Gradle update groups. Security-library and major-version updates require maintainer review; no workflow auto-merges dependency PRs.

## Safe initial activation

The initial repository has no dependency graph or active workflows. Introduce the setup in this order:

1. Publish the core/build prerequisites and `ci/setup` through reviewed PRs. Verify all three build checks before continuing.
2. Publish the graph-generation and upload workflows as the first `ci/security` change. After merging, run `Dependency submission` on `main` and verify the graph under **Insights → Dependency graph**.
3. Add CodeQL and Dependency Review in a follow-up security PR. The privileged `workflow_run` uploader must already exist on the default branch for that PR's graph to be submitted. Do not bypass a failed dependency review to bootstrap its own uploader.
4. Publish `ci/release` through a separate reviewed PR. The release workflow is permission-sensitive and requires maintainer approval before merging.

All workflow files are ready for these separate changes; do not publish the security bootstrap and its dependent review gate as one first PR. There is no need to rewrite history or disable a required check.

## Release process

`version.txt` is the Gradle version source. Release Please uses the `simple` strategy and `.release-please-manifest.json` to maintain a release PR containing the next version and changelog. Feature commits increase the minor version, fixes increase the patch version; breaking changes stay below 1.0 while the current major is zero. Promotion to 1.0 or another major is a deliberate maintainer decision.

Release PRs are merged only after explicit release approval and green checks. The workflow uses only `GITHUB_TOKEN`; it does not need a personal token. **PRs created or updated by that token do not automatically trigger other workflows.** After each Release Please update, a maintainer must close and reopen the release PR using their own GitHub session to trigger the full PR verification suite. Do not accept results for an earlier commit or merge without those checks.

Once an approved release PR is merged, Release Please creates its tag and GitHub release. A downstream job in the same workflow uploads the license texts, third-party notices and checksums using `release_created` and `tag_name`. It does not rely on another workflow being triggered by that tag.

The current deliverable is a core library, not a desktop installer. Manually dispatching `Release` only verifies/tests the core JAR and uploads it as a seven-day test artifact; it cannot create a release. Native `.msi`, `.dmg`, `.deb` and `.rpm` packaging must be added in downstream jobs in this same workflow when the desktop application exists, with a manual packaging run before the first installer release. That later release must include all installer hashes and explain that installers are unsigned. No signing certificates or paid services are configured.

## Repository settings after the first green runs

These settings are not changed by repository files. An administrator must inspect and apply them after the corresponding checks exist:

- **Settings → General → Pull Requests:** enable only squash merging, use PR title and description for the commit message, and enable automatic head-branch deletion.
- **Settings → Actions → General → Workflow permissions:** default to read-only contents and allow Actions to create pull requests. No custom secrets are required.
- **Settings → Rules → Rulesets → main:** require a PR, an up-to-date branch and `build-linux`, `build-windows`, `build-macos`, `pr-title`, `dependency-review` and `CodeQL`; prohibit force-pushes and branch deletion. Confirm exact check contexts from real runs before saving.
- **Settings → Code security:** enable Dependabot alerts and security updates, and confirm Secret Scanning is enabled. Verify Gradle dependencies in the graph rather than assuming the version catalog alone populates it.

All runners are standard GitHub-hosted runners. Actions use full commit SHAs, minimal job permissions and timeouts. Public workflow logs/artifacts must contain only synthetic test data. Caches on PRs are read-only. Release runs are never canceled by a newer run.

## Upstream references

- [Gradle dependency submission and fork workflow separation](https://github.com/gradle/actions/blob/v6.3.0/docs/dependency-submission.md)
- [Gradle Actions licensing and basic caching](https://github.com/gradle/actions/blob/v6.3.0/DISTRIBUTION.md)
- [Dependency Review configuration](https://github.com/actions/dependency-review-action/tree/v5.0.0)
- [Release Please and GITHUB_TOKEN event limitations](https://github.com/googleapis/release-please-action/tree/v5.0.0#other-actions-on-release-please-prs)
