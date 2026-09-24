// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.build

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/** Fails when separate test tooling is part of the resolved application runtime graph. */
abstract class CheckRuntimeDependencies : DefaultTask() {
    @get:Input
    abstract val runtimeGraph: Property<ResolvedComponentResult>

    @TaskAction
    fun check() {
        val forbidden = runtimeGraph.get().allModules()
            .filter { it.group in testGroups }
            .map { "${it.group}:${it.module}:${it.version}" }
            .sorted()
        check(forbidden.isEmpty()) { "Test-only dependencies on runtime classpath: ${forbidden.joinToString()}" }
    }

    private fun ResolvedComponentResult.allModules(): Set<ModuleComponentIdentifier> {
        val seen = mutableSetOf<ResolvedComponentResult>()
        val pending = ArrayDeque(listOf(this))
        while (pending.isNotEmpty()) {
            val component = pending.removeFirst()
            if (seen.add(component)) {
                component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { pending.addLast(it.selected) }
            }
        }
        return seen.mapNotNull { it.id as? ModuleComponentIdentifier }.toSet()
    }

    private companion object {
        val testGroups = setOf("org.junit", "org.junit.jupiter", "org.junit.platform", "io.kotest", "org.opentest4j", "org.apiguardian")
    }
}

/** Registers `checkRuntimeDependencies` for a JVM project and makes `check` depend on it. */
class RuntimeDependencyCheckPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.withType(JavaPlugin::class.java) {
            val runtimeCheck = project.tasks.register("checkRuntimeDependencies", CheckRuntimeDependencies::class.java) { task ->
                task.group = "verification"
                task.description = "Prevents separate test tooling from entering the application runtime."
                task.runtimeGraph.set(
                    project.configurations.named(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME)
                        .flatMap { it.incoming.resolutionResult.rootComponent },
                )
            }
            project.tasks.named("check") { it.dependsOn(runtimeCheck) }
        }
    }
}
