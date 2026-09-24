// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
// Locks the plugin classpath shared by all projects; scripts/relock.sh regenerates buildscript-gradle.lockfile.
buildscript { configurations.classpath { resolutionStrategy.activateDependencyLocking() } }
plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    id("keyrook.runtime-dependency-check") apply false
}

val lintSources = files(
    "build.gradle.kts", "settings.gradle.kts",
    listOf("core", "app", "build-logic").map { dir -> fileTree(dir) { include("*.gradle.kts", "src/**/*.kt") } },
)
val lint = tasks.register("lint") {
    group = "verification"
    description = "Checks source license headers and whitespace hygiene."
    inputs.files(lintSources)
    val sources: FileCollection = lintSources
    val root = rootDir
    doLast {
        sources.forEach { source ->
            val lines = source.readLines()
            check(lines.size >= 2 && lines[0].startsWith("// SPDX-FileCopyrightText:") &&
                lines[1] == "// SPDX-License-Identifier: GPL-3.0-or-later") {
                "Missing SPDX header: ${source.relativeTo(root)}"
            }
            lines.forEachIndexed { index, line ->
                check('\t' !in line && line == line.trimEnd()) {
                    "Whitespace violation: ${source.relativeTo(root)}:${index + 1}"
                }
            }
        }
    }
}
tasks.named("check") { dependsOn(lint, ":core:check", ":app:check") }
val releaseVersion = providers.fileContents(layout.projectDirectory.file("version.txt")).asText.get().trim()
check(Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(releaseVersion)) { "Invalid version.txt" }
allprojects {
    group = "app.keyrook"
    version = releaseVersion
}
