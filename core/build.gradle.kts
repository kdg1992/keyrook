// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    id("keyrook.runtime-dependency-check")
}
kotlin { jvmToolchain(25) }
dependencies {
    implementation(libs.serialization.json)
    implementation(libs.bouncycastle)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.sshd.common)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.junit.launcher)
}
dependencyLocking { lockAllConfigurations() }
tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    maxParallelForks = 1
}
tasks.jar {
    from(rootProject.file("LICENSE")) { into("META-INF") }
    from(rootProject.file("THIRD-PARTY-NOTICES")) { into("META-INF") }
    // Collected review evidence stays in the repository; the notices themselves are packaged.
    from(rootProject.file("licenses")) { into("META-INF/licenses"); exclude("native-evidence/**") }
}
