// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
// Conventions and typed tasks shared by the Keyrook projects. They use only the Gradle API and the Kotlin
// standard library, so the included build adds no dependencies beyond the catalog's Kotlin plugin.
buildscript { configurations.classpath { resolutionStrategy.activateDependencyLocking() } }
plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)
}
kotlin { jvmToolchain(25) }
dependencyLocking { lockAllConfigurations() }
gradlePlugin {
    plugins {
        register("runtimeDependencyCheck") {
            id = "keyrook.runtime-dependency-check"
            implementationClass = "app.keyrook.build.RuntimeDependencyCheckPlugin"
        }
    }
}
