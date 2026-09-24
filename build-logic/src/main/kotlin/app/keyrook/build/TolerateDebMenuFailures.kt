// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.build

import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import java.io.File

/**
 * Rebuilds the control archive of the single DEB package in [packageDirectory] so that its maintainer scripts
 * treat a failed `xdg-desktop-menu` call as a warning. Each call in [menuCommands] (maintainer script name to
 * exact line) must occur exactly once; otherwise the build fails so that a changed jpackage template is reviewed.
 * The payload member is copied unchanged. Runs as the last action of the DEB packaging task.
 */
class TolerateDebMenuFailures(
    private val packageDirectory: Provider<Directory>,
    private val menuCommands: Map<String, String>,
    private val warning: String,
) : Action<Task> {
    override fun execute(task: Task) {
        val packages = packageDirectory.get().asFile.walk().filter { it.isFile && it.extension == "deb" }.toList()
        check(packages.size == 1) { "Expected one DEB package, found $packages" }
        rebuild(packages.single(), task.temporaryDir.resolve("deb-control"))
    }

    private fun rebuild(deb: File, work: File) {
        work.deleteRecursively()
        val control = work.resolve("control")
        check(control.mkdirs()) { "Cannot create $control" }
        val members = run(work, "ar", "t", deb.absolutePath).lines().filter(String::isNotEmpty)
        check(members.size == 3 && members[0] == "debian-binary" && members[1].startsWith("control.tar.") &&
            members[2].startsWith("data.tar.")) { "Unexpected DEB layout: $members" }
        run(work, "ar", "x", deb.absolutePath, members[0], members[2])
        run(work, "dpkg-deb", "--control", deb.absolutePath, control.absolutePath)
        for ((name, command) in menuCommands) {
            val script = control.resolve(name)
            val lines = script.readLines()
            check(lines.count { it == command } == 1) { "jpackage $name changed; review the menu registration handling" }
            script.writeText(lines.joinToString("\n", postfix = "\n") {
                if (it == command) "$it || echo \"$warning\" >&2" else it
            })
        }
        run(work, "tar", "--create", "--gzip", "--format=gnu", "--owner=0", "--group=0", "--numeric-owner",
            "--sort=name", "--file", "control.tar.gz", "--directory", control.absolutePath, ".")
        val rebuilt = work.resolve(deb.name)
        run(work, "ar", "rcD", rebuilt.absolutePath, members[0], "control.tar.gz", members[2])
        for (name in menuCommands.keys) {
            check("|| echo \"$warning\" >&2" in run(work, "dpkg-deb", "--info", rebuilt.absolutePath, name)) {
                "Rebuilt DEB lacks the $name menu registration handling"
            }
        }
        run(work, "dpkg-deb", "--contents", rebuilt.absolutePath)
        rebuilt.copyTo(deb, overwrite = true)
        work.deleteRecursively()
    }

    private fun run(directory: File, vararg command: String): String {
        val process = ProcessBuilder(*command).directory(directory).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "${command.joinToString(" ")} failed:\n$output" }
        return output
    }
}
