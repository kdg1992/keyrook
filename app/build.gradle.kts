// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJLinkTask
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import java.security.MessageDigest
import java.util.Properties
import java.time.Duration
import org.gradle.api.artifacts.result.ResolvedArtifactResult

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
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
    implementation("org.jetbrains.compose.desktop:desktop-jvm-$desktopTarget:${libs.versions.compose.get()}")
    implementation("org.jetbrains.compose.material:material:1.12.1")
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
val checkRuntimeDependencies = tasks.register("checkRuntimeDependencies") {
    group = "verification"
    doLast {
        val testGroups = setOf("org.junit", "org.junit.jupiter", "org.junit.platform", "io.kotest", "org.opentest4j", "org.apiguardian")
        check(configurations.runtimeClasspath.get().incoming.resolutionResult.allComponents
            .mapNotNull { it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }
            .none { it.group in testGroups }) { "Test-only dependency in application runtime" }
    }
}
tasks.named("check") { dependsOn(checkRuntimeDependencies) }
val nativeInventory = rootProject.layout.projectDirectory.dir("licenses/native/$desktopTarget")
val packagingResources = layout.buildDirectory.dir("packagingResources")
val packagingJavaHome = file(System.getProperty("java.home"))
val runtimeFiles = configurations.runtimeClasspath
val externalRuntimeArtifacts = runtimeFiles.get().incoming.artifactView {
    componentFilter { it is org.gradle.api.artifacts.component.ModuleComponentIdentifier }
}.artifacts
fun nativeDigest(file: File): String {
    val hash = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(65536)
        while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
    }
    return hash.digest().joinToString("") { "%02x".format(it) }
}
fun nativeLegalRecords(root: File): String {
    check(root.isDirectory) { "Bundled JDK legal notices are unavailable" }
    return root.walkTopDown().filter { it.isFile }.map {
        "${it.relativeTo(root).invariantSeparatorsPath} ${nativeDigest(it)}\n"
    }.sorted().joinToString("").also { check(it.isNotEmpty()) { "Bundled JDK legal notices are empty" } }
}
fun nativeTextDigest(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
fun nativeArtifactKey(artifact: ResolvedArtifactResult): String {
    val id = artifact.id.componentIdentifier as org.gradle.api.artifacts.component.ModuleComponentIdentifier
    return "artifact.${id.group}:${id.module}:${id.version}/${artifact.file.name}.sha256"
}

val nativeInventoryReport = layout.buildDirectory.dir("reports/native-inventory/$desktopTarget")
val inventoryApplicationVersion = project.version.toString()
val inventoryGradleVersion = gradle.gradleVersion
val inventoryComposeVersion = libs.versions.compose.get()
val collectNativeLegalFiles = tasks.register<Sync>("collectNativeLegalFiles") {
    from(packagingJavaHome.resolve("legal"))
    into(nativeInventoryReport.map { it.dir("jdk-legal") })
}
val collectNativeDistributionInventory = tasks.register("collectNativeDistributionInventory") {
    group = "verification"
    description = "Collects exact runtime and JDK evidence without approving or building native packages."
    dependsOn(collectNativeLegalFiles)
    outputs.dir(nativeInventoryReport)
    outputs.upToDateWhen { false }
    doLast {
        check(desktopTarget == "$hostOs-$hostArch") { "Inventory evidence requires its matching host architecture" }
        val artifacts = externalRuntimeArtifacts.artifacts.sortedBy(::nativeArtifactKey)
        check(artifacts.isNotEmpty() && artifacts.map(::nativeArtifactKey).distinct().size == artifacts.size) {
            "Runtime artifact inventory is empty or has ambiguous full artifact identities"
        }
        val legalRoot = packagingJavaHome.resolve("legal")
        val legalRecords = nativeLegalRecords(legalRoot)
        val properties = sortedMapOf(
            "reviewed" to "false",
            "target" to desktopTarget,
            "jdk.vendor" to System.getProperty("java.vendor"),
            "jdk.version" to System.getProperty("java.runtime.version"),
            "jdk.legal.sha256" to nativeTextDigest(legalRecords),
            "packaging.compose.version" to inventoryComposeVersion,
            "packaging.gradle.version" to inventoryGradleVersion,
            "application.version" to inventoryApplicationVersion,
        )
        artifacts.forEach { properties[nativeArtifactKey(it)] = nativeDigest(it.file) }
        fun escapeProperty(value: String): String = buildString {
            value.forEach { char ->
                when (char) {
                    '\\', '=', ':', '#', '!', ' ' -> { append('\\'); append(char) }
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (char.code !in 32..126) append("\\u%04x".format(char.code)) else append(char)
                }
            }
        }
        val report = nativeInventoryReport.get().asFile
        report.mkdirs()
        val reportNames = setOf("candidate.properties", "jdk-legal.sha256", "runtime-artifacts.tsv", "jdk-legal", "README.txt")
        check(report.listFiles().orEmpty().all { it.name in reportNames }) { "Inventory report directory contains unexpected files" }
        val candidate = properties.entries.joinToString("") { "${escapeProperty(it.key)}=${escapeProperty(it.value)}\n" }
        report.resolve("candidate.properties").writeText(candidate, Charsets.US_ASCII)
        report.resolve("jdk-legal.sha256").writeText(legalRecords, Charsets.UTF_8)
        val coordinateRows = artifacts.map {
            val id = it.id.componentIdentifier as org.gradle.api.artifacts.component.ModuleComponentIdentifier
            listOf("${id.group}:${id.module}:${id.version}", it.file.name, properties.getValue(nativeArtifactKey(it)))
                .joinToString("\t") { cell -> cell.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n") }
        }
        report.resolve("runtime-artifacts.tsv").writeText("coordinate\tfilename\tsha256\n" + coordinateRows.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        report.resolve("README.txt").writeText(
            "Unreviewed native redistribution evidence. This report does not approve packaging.\n" +
                "candidate.properties always sets reviewed=false and contains no reviewed notice/source hashes.\n" +
                "Only external runtime artifacts and public JDK legal files are included; no native binaries are copied.\n",
            Charsets.UTF_8,
        )
    }
}
val checkNativeDistributionLicenses = tasks.register("checkNativeDistributionLicenses") {
    group = "verification"
    description = "Refuses native redistribution without an exact reviewed native and JDK notice inventory."
    doLast {
        check(desktopTarget == "$hostOs-$hostArch") { "Native packages require their matching host architecture" }
        val inventoryFile = nativeInventory.file("inventory.properties").asFile
        check(inventoryFile.isFile) {
            "Native redistribution is blocked: missing reviewed inventory for $desktopTarget; see docs/PACKAGING.md"
        }
        val inventory = Properties().apply { inventoryFile.inputStream().use(::load) }
        check(inventory.getProperty("reviewed") == "true") { "Native inventory has not been reviewed" }
        for (name in listOf("NOTICE.txt", "SOURCES.md")) {
            val notice = nativeInventory.file(name).asFile
            check(notice.isFile && inventory.getProperty("notice.$name.sha256") == nativeDigest(notice)) {
                "Reviewed native notices or source availability records are missing or changed"
            }
        }
        val runtimeArtifacts = externalRuntimeArtifacts.artifacts
        val expectedKeys = runtimeArtifacts.map(::nativeArtifactKey).toSet()
        check(expectedKeys.size == runtimeArtifacts.size) { "Ambiguous full runtime artifact identities" }
        check(inventory.stringPropertyNames().filter { it.startsWith("artifact.") }.toSet() == expectedKeys) {
            "Reviewed native inventory does not cover exactly the resolved runtime artifacts"
        }
        runtimeArtifacts.forEach {
            check(inventory.getProperty(nativeArtifactKey(it)) == nativeDigest(it.file)) {
                "Runtime artifact changed since native license review"
            }
        }
        check(inventory.getProperty("jdk.vendor") == System.getProperty("java.vendor") &&
            inventory.getProperty("jdk.version") == System.getProperty("java.runtime.version")) {
            "Bundled JDK differs from the reviewed version/vendor"
        }
        val legalRoot = packagingJavaHome.resolve("legal")
        val legalHash = nativeTextDigest(nativeLegalRecords(legalRoot))
        check(inventory.getProperty("jdk.legal.sha256") == legalHash) { "Bundled JDK legal notices changed since review" }
    }
}
val preparePackagingResources = tasks.register<Sync>("preparePackagingResources") {
    into(packagingResources.map { it.dir("common") })
    from(rootProject.file("LICENSE"), rootProject.file("THIRD-PARTY-NOTICES"))
    from(rootProject.file("licenses")) { into("licenses") }
    from(packagingJavaHome.resolve("legal")) { into("licenses/bundled-jdk") }
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
            windows {
                menuGroup = "Keyrook"
                perUserInstall = true
                dirChooser = true
                upgradeUuid = "b5e7cbba-4582-4cee-82d3-b73b1e3baf0e"
            }
            macOS { bundleID = "app.keyrook.desktop"; dockName = "Keyrook"; appCategory = "public.app-category.utilities" }
            linux { packageName = "keyrook"; menuGroup = "Utility"; appCategory = "utils"; rpmLicenseType = "GPL-3.0-or-later" }
        }
    }
}
tasks.jar {
    from(rootProject.file("LICENSE")) { into("META-INF") }
    from(rootProject.file("THIRD-PARTY-NOTICES")) { into("META-INF") }
    from(rootProject.file("licenses")) { into("META-INF/licenses") }
}
