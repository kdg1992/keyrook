// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.build

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.Properties

/** Inputs shared by the inventory collector and the redistribution gate. */
abstract class NativeInventoryTask : DefaultTask() {
    /** Packaging target selected for this build, such as `linux-x64`. */
    @get:Input
    abstract val target: Property<String>

    /** Target matching the machine that runs the build. */
    @get:Input
    abstract val hostTarget: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val runtimeArtifactFiles: ConfigurableFileCollection

    /** Identities of [runtimeArtifactFiles]; the files themselves are tracked through that input. */
    @get:Internal
    abstract val runtimeArtifacts: SetProperty<ResolvedArtifactResult>

    /** The packaging JDK's `legal/` directory. Its absence is reported by the task action. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val jdkLegalDirectory: DirectoryProperty

    fun runtimeArtifacts(collection: ArtifactCollection) {
        runtimeArtifactFiles.from(collection.artifactFiles)
        runtimeArtifacts.set(collection.resolvedArtifacts)
    }

    protected fun jdkLegalRecords(): String = NativeInventory.legalRecords(jdkLegalDirectory.get().asFile)
}

/** Collects exact runtime and JDK evidence without approving or building native packages. */
abstract class CollectNativeDistributionInventory : NativeInventoryTask() {
    @get:Input
    abstract val applicationVersion: Property<String>

    @get:Input
    abstract val gradleVersion: Property<String>

    @get:Input
    abstract val composeVersion: Property<String>

    @get:OutputDirectory
    abstract val reportDirectory: DirectoryProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun collect() {
        check(target.get() == hostTarget.get()) { "Inventory evidence requires its matching host architecture" }
        val artifacts = runtimeArtifacts.get().sortedBy(NativeInventory::artifactKey)
        check(artifacts.isNotEmpty() && artifacts.map(NativeInventory::artifactKey).distinct().size == artifacts.size) {
            "Runtime artifact inventory is empty or has ambiguous full artifact identities"
        }
        val legalRecords = jdkLegalRecords()
        val properties = sortedMapOf(
            "reviewed" to "false",
            "target" to target.get(),
            "jdk.vendor" to System.getProperty("java.vendor"),
            "jdk.version" to System.getProperty("java.runtime.version"),
            "jdk.legal.sha256" to NativeInventory.textDigest(legalRecords),
            "packaging.compose.version" to composeVersion.get(),
            "packaging.gradle.version" to gradleVersion.get(),
            "application.version" to applicationVersion.get(),
        )
        artifacts.forEach { properties[NativeInventory.artifactKey(it)] = NativeInventory.digest(it.file) }
        val report = reportDirectory.get().asFile
        report.mkdirs()
        val reportNames = setOf("candidate.properties", "jdk-legal.sha256", "runtime-artifacts.tsv", "jdk-legal", "README.txt")
        check(report.listFiles().orEmpty().all { it.name in reportNames }) { "Inventory report directory contains unexpected files" }
        val candidate = properties.entries.joinToString("") { "${escapeProperty(it.key)}=${escapeProperty(it.value)}\n" }
        report.resolve("candidate.properties").writeText(candidate, Charsets.US_ASCII)
        report.resolve("jdk-legal.sha256").writeText(legalRecords, Charsets.UTF_8)
        val coordinateRows = artifacts.map {
            val id = NativeInventory.moduleId(it)
            listOf("${id.group}:${id.module}:${id.version}", it.file.name, properties.getValue(NativeInventory.artifactKey(it)))
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

    private fun escapeProperty(value: String): String = buildString {
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
}

/** Refuses native redistribution without an exact reviewed native and JDK notice inventory. */
abstract class CheckNativeDistributionLicenses : NativeInventoryTask() {
    /** `licenses/native/<target>`; a missing directory is reported by the task action. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inventoryDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        check(target.get() == hostTarget.get()) { "Native packages require their matching host architecture" }
        val inventoryRoot = inventoryDirectory.get().asFile
        val inventoryFile = inventoryRoot.resolve("inventory.properties")
        check(inventoryFile.isFile) {
            "Native redistribution is blocked: missing reviewed inventory for ${target.get()}; see docs/PACKAGING.md"
        }
        val inventory = Properties().apply { inventoryFile.inputStream().use(::load) }
        check(inventory.getProperty("reviewed") == "true") { "Native inventory has not been reviewed" }
        for (name in listOf("NOTICE.txt", "SOURCES.md")) {
            val notice = inventoryRoot.resolve(name)
            check(notice.isFile && inventory.getProperty("notice.$name.sha256") == NativeInventory.digest(notice)) {
                "Reviewed native notices or source availability records are missing or changed"
            }
        }
        val artifacts = runtimeArtifacts.get()
        val expectedKeys = artifacts.map(NativeInventory::artifactKey).toSet()
        check(expectedKeys.size == artifacts.size) { "Ambiguous full runtime artifact identities" }
        check(inventory.stringPropertyNames().filter { it.startsWith("artifact.") }.toSet() == expectedKeys) {
            "Reviewed native inventory does not cover exactly the resolved runtime artifacts"
        }
        artifacts.forEach {
            check(inventory.getProperty(NativeInventory.artifactKey(it)) == NativeInventory.digest(it.file)) {
                "Runtime artifact changed since native license review"
            }
        }
        check(inventory.getProperty("jdk.vendor") == System.getProperty("java.vendor") &&
            inventory.getProperty("jdk.version") == System.getProperty("java.runtime.version")) {
            "Bundled JDK differs from the reviewed version/vendor"
        }
        val legalHash = NativeInventory.textDigest(jdkLegalRecords())
        check(inventory.getProperty("jdk.legal.sha256") == legalHash) { "Bundled JDK legal notices changed since review" }
    }
}
