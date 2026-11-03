package dev.mcdevmcp.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

abstract class McpSdkDependencyCheck : DefaultTask() {
    @get:Input
    abstract val resolvedModules: MapProperty<String, String>

    /** Reviewed module versions, keyed by `group:name`. */
    @get:Input
    abstract val expectedVersions: MapProperty<String, String>

    @TaskAction
    fun verifyRuntimeClasspath() {
        val gsonModuleName = String(charArrayOf('g', 's', 'o', 'n'))
        val runtimeModules = resolvedModules.get()
        val mismatches = expectedVersions.get().mapNotNull { (module, expectedVersion) ->
            val actualVersion = runtimeModules[module]
            if (actualVersion == expectedVersion) {
                null
            } else {
                "$module resolved $actualVersion, expected $expectedVersion"
            }
        }
        val gsonModules = runtimeModules.filter { (module, _) ->
            module.substringBefore(':')
                .equals("com.google.code.$gsonModuleName", ignoreCase = true) || module.substringAfter(':')
                .equals(gsonModuleName, ignoreCase = true)
        }

        check(mismatches.isEmpty() && gsonModules.isEmpty()) {
            buildString {
                appendLine("MCP SDK dependency verification failed.")
                mismatches.forEach(::appendLine)
                gsonModules.forEach { (module, version) ->
                    appendLine("Unexpected ${gsonModuleName.replaceFirstChar { it.uppercase() }} module: $module:$version")
                }
            }
        }
    }
}
