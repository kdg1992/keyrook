// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
kotlin { jvmToolchain(25) }
dependencies {
    implementation(libs.serialization.json)
    implementation(libs.bouncycastle)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.junit.launcher)
}
dependencyLocking { lockAllConfigurations() }
val checkRuntimeDependencies = tasks.register("checkRuntimeDependencies") {
    group = "verification"
    description = "Prevents separate test tooling from entering the application runtime."
    doLast {
        val testGroups = setOf("org.junit", "org.junit.jupiter", "org.junit.platform", "io.kotest", "org.opentest4j", "org.apiguardian")
        val forbidden = configurations.runtimeClasspath.get().incoming.resolutionResult.allComponents
            .mapNotNull { it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }
            .filter { it.group in testGroups }
        check(forbidden.isEmpty()) { "Test-only dependencies on runtime classpath: ${forbidden.joinToString()}" }
    }
}
tasks.named("check") { dependsOn(checkRuntimeDependencies) }
tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    maxParallelForks = 1
}
tasks.jar {
    from(rootProject.file("LICENSE")) { into("META-INF") }
    from(rootProject.file("THIRD-PARTY-NOTICES")) { into("META-INF") }
    from(rootProject.file("licenses")) { into("META-INF/licenses") }
}
