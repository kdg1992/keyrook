// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJLinkTask
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import app.keyrook.build.CheckNativeDistributionLicenses
import app.keyrook.build.CollectNativeDistributionInventory
import app.keyrook.build.NativeInventoryTask
import app.keyrook.build.TolerateDebMenuFailures
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import java.time.Duration

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    id("keyrook.runtime-dependency-check")
}
kotlin { jvmToolchain(25) }
val hostOs = System.getProperty("os.name").lowercase().let {
    when { it.startsWith("windows") -> "windows"; it.startsWith("mac") -> "macos"; else -> "linux" }
}
val hostArch = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "arm64" else "x64"
val desktopTarget = providers.gradleProperty("keyrook.target").getOrElse("$hostOs-$hostArch")
check(desktopTarget in setOf("windows-x64", "linux-x64", "linux-arm64", "macos-x64", "macos-arm64"))
dependencies {
    implementation(project(":core"))
    // Per-target coordinate, so it is not a catalog entry; its version follows the catalog's `compose`.
    implementation("org.jetbrains.compose.desktop:desktop-jvm-$desktopTarget:${libs.versions.compose.get()}")
    implementation(libs.compose.material)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
dependencyLocking {
    lockAllConfigurations()
    lockFile = file("gradle/dependency-locks/$desktopTarget.lockfile")
}
tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("java.awt.headless", "true")
    systemProperty("skiko.renderApi", "SOFTWARE")
}
tasks.register<JavaExec>("desktopWindowCheck") {
    group = "verification"
    description = "Checks native AWT event routing on an isolated CI desktop."
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.keyrook.app.DesktopWindowCheck")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    systemProperty("java.awt.headless", "false")
    timeout.set(Duration.ofSeconds(90))
    doFirst {
        check(System.getenv("GITHUB_ACTIONS") == "true") { "Native window checks require an isolated CI desktop" }
    }
}
val nativeInventory = rootProject.layout.projectDirectory.dir("licenses/native/$desktopTarget")
val packagingResources = layout.buildDirectory.dir("packagingResources")
val packagingJavaHome = file(System.getProperty("java.home"))
val packagingJdkLegal = packagingJavaHome.resolve("legal")
val externalRuntimeArtifacts = configurations.runtimeClasspath.get().incoming.artifactView {
    componentFilter { it is ModuleComponentIdentifier }
}.artifacts
val nativeInventoryReport = layout.buildDirectory.dir("reports/native-inventory/$desktopTarget")
val collectNativeLegalFiles = tasks.register<Sync>("collectNativeLegalFiles") {
    from(packagingJdkLegal)
    into(nativeInventoryReport.map { it.dir("jdk-legal") })
}
tasks.withType<NativeInventoryTask>().configureEach {
    target = desktopTarget
    hostTarget = "$hostOs-$hostArch"
    runtimeArtifacts(externalRuntimeArtifacts)
    jdkLegalDirectory = packagingJdkLegal
}
tasks.register<CollectNativeDistributionInventory>("collectNativeDistributionInventory") {
    group = "verification"
    description = "Collects exact runtime and JDK evidence without approving or building native packages."
    dependsOn(collectNativeLegalFiles)
    applicationVersion = project.version.toString()
    gradleVersion = gradle.gradleVersion
    composeVersion = libs.versions.compose
    reportDirectory = nativeInventoryReport
}
val checkNativeDistributionLicenses = tasks.register<CheckNativeDistributionLicenses>("checkNativeDistributionLicenses") {
    group = "verification"
    description = "Refuses native redistribution without an exact reviewed native and JDK notice inventory."
    inventoryDirectory = nativeInventory
}
val preparePackagingResources = tasks.register<Sync>("preparePackagingResources") {
    into(packagingResources.map { it.dir("common") })
    from(rootProject.file("LICENSE"), rootProject.file("THIRD-PARTY-NOTICES"))
    from(rootProject.file("licenses")) { into("licenses") }
    from(packagingJdkLegal) { into("licenses/bundled-jdk") }
}
val requireCiPackaging = tasks.register("requireCiPackaging") {
    doLast { check(System.getenv("GITHUB_ACTIONS") == "true") { "Native installers are built only by the GitHub release workflow" } }
}
tasks.matching { it.name in setOf("prepareAppResources", "prepareReleaseAppResources") }.configureEach {
    dependsOn(preparePackagingResources)
}
tasks.withType<AbstractJLinkTask>().configureEach { dependsOn(requireCiPackaging, checkNativeDistributionLicenses) }
tasks.withType<AbstractJPackageTask>().configureEach {
    dependsOn(requireCiPackaging, checkNativeDistributionLicenses, preparePackagingResources)
}
// jpackage's DEB maintainer scripts run xdg-desktop-menu under `set -e`. xdg-utils exits with status 3 when no
// XDG desktop-directories directory exists (servers, containers, WSL, minimal window-manager setups), so the
// package stays half-configured after installation and cannot be removed. Compose passes its own jpackage
// resource directory, so the finished package's control archive is rebuilt with both calls made non-fatal.
// The payload member is copied unchanged.
val debMenuCommands = mapOf(
    "postinst" to "xdg-desktop-menu install /opt/keyrook/lib/keyrook-Keyrook.desktop",
    "prerm" to "do_if_file_belongs_to_single_package /opt/keyrook/lib/keyrook-Keyrook.desktop " +
        "xdg-desktop-menu uninstall /opt/keyrook/lib/keyrook-Keyrook.desktop",
)
val debMenuWarning = "keyrook: desktop menu entry not updated (xdg-desktop-menu status \$?)"
tasks.withType<AbstractJPackageTask>().matching { it.targetFormat == TargetFormat.Deb }.configureEach {
    doLast(TolerateDebMenuFailures(destinationDir, debMenuCommands, debMenuWarning))
}
compose.desktop {
    application {
        mainClass = "app.keyrook.app.MainKt"
        jvmArgs += "-Dkeyrook.version=${project.version}"
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
        javaHome = packagingJavaHome.absolutePath
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "Keyrook"
            packageVersion = project.version.toString()
            description = "Local encrypted credential vault"
            vendor = "Kim Daniel Geisthardt"
            copyright = "Copyright 2026 Kim Daniel Geisthardt"
            licenseFile.set(rootProject.file("LICENSE"))
            appResourcesRootDir.set(packagingResources)
            modules("java.desktop", "java.logging", "java.management", "java.naming", "java.net.http", "java.sql", "java.xml", "jdk.crypto.ec", "jdk.unsupported")
            // Original project artwork generated by scripts/generate-icons.py; bundled as application files, not runtime artifacts.
            windows {
                iconFile.set(project.file("icons/keyrook.ico"))
                menuGroup = "Keyrook"
                perUserInstall = true
                dirChooser = true
                upgradeUuid = "b5e7cbba-4582-4cee-82d3-b73b1e3baf0e"
            }
            macOS {
                iconFile.set(project.file("icons/keyrook.icns"))
                bundleID = "app.keyrook.desktop"; dockName = "Keyrook"; appCategory = "public.app-category.utilities"
                // jpackage rejects macOS bundle versions starting with 0. Until 1.0.0 the bundle carries
                // 1.0.0; the application, release and installer file names keep the real version.
                val macBundleVersion = project.version.toString().takeUnless { it.startsWith("0.") } ?: "1.0.0"
                packageVersion = macBundleVersion
                dmgPackageVersion = macBundleVersion
            }
            linux { iconFile.set(project.file("icons/keyrook.png")); packageName = "keyrook"; menuGroup = "Utility"; appCategory = "utils"; rpmLicenseType = "GPL-3.0-or-later" }
        }
    }
}
tasks.jar {
    from(rootProject.file("LICENSE")) { into("META-INF") }
    from(rootProject.file("THIRD-PARTY-NOTICES")) { into("META-INF") }
    from(rootProject.file("licenses")) { into("META-INF/licenses") }
}
