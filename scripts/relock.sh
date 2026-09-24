#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Regenerates every Gradle dependency lockfile after a catalog, plugin or dependency change:
# build-logic, the root plugin classpath, settings, core and the desktop app for all five packaging targets.
# Needs network access to the configured repositories (including Google Maven for androidx).
# Extra arguments are passed to every Gradle invocation, for example
# -Dorg.gradle.java.installations.paths=/path/to/jdk-25.
set -euo pipefail

cd "$(dirname "$0")/.."
gradle=(./gradlew --console=plain --no-configuration-cache "$@")

"${gradle[@]}" -p build-logic dependencies buildEnvironment --write-locks
"${gradle[@]}" buildEnvironment :core:dependencies --write-locks
for target in windows-x64 linux-x64 linux-arm64 macos-x64 macos-arm64; do
    "${gradle[@]}" :app:dependencies "-Pkeyrook.target=$target" --write-locks
done
git status --short -- '*.lockfile'
