// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
}

val lintSources = files(
    "build.gradle.kts", "settings.gradle.kts",
    subprojects.map { project -> project.fileTree(project.projectDir) { include("build.gradle.kts", "src/**/*.kt") } },
)
val lint = tasks.register("lint") {
    group = "verification"
    description = "Checks source license headers and whitespace hygiene."
    inputs.files(lintSources)
    doLast {
        lintSources.forEach { source ->
            val lines = source.readLines()
            check(lines.size >= 2 && lines[0].startsWith("// SPDX-FileCopyrightText:") &&
                lines[1] == "// SPDX-License-Identifier: GPL-3.0-or-later") {
                "Missing SPDX header: ${source.relativeTo(rootDir)}"
            }
            lines.forEachIndexed { index, line ->
                check('\t' !in line && line == line.trimEnd()) {
                    "Whitespace violation: ${source.relativeTo(rootDir)}:${index + 1}"
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
