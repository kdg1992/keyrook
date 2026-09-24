// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.build

import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import java.io.File
import java.security.MessageDigest

/** Digest and record formats shared by the inventory collector and the redistribution gate. */
internal object NativeInventory {
    fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    fun legalRecords(root: File): String {
        check(root.isDirectory) { "Bundled JDK legal notices are unavailable" }
        return root.walkTopDown().filter { it.isFile }.map {
            "${it.relativeTo(root).invariantSeparatorsPath} ${digest(it)}\n"
        }.sorted().joinToString("").also { check(it.isNotEmpty()) { "Bundled JDK legal notices are empty" } }
    }

    fun textDigest(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun moduleId(artifact: ResolvedArtifactResult): ModuleComponentIdentifier =
        artifact.id.componentIdentifier as ModuleComponentIdentifier

    fun artifactKey(artifact: ResolvedArtifactResult): String {
        val id = moduleId(artifact)
        return "artifact.${id.group}:${id.module}:${id.version}/${artifact.file.name}.sha256"
    }
}
