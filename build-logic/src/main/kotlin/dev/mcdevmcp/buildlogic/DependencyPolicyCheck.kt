package dev.mcdevmcp.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class DependencyPolicyCheck : DefaultTask() {
    @get:Input
    abstract val declaredExternalSelectors: ListProperty<String>

    @TaskAction
    fun verifyDependencyPolicy() {
        val dynamic = declaredExternalSelectors.get().filter { selector ->
            val version = selector.substringAfterLast(':')
            version.contains('+') || version.startsWith("latest.") || version.startsWith('[') || version.startsWith('(') || version.endsWith(
                ']'
            ) || version.endsWith(')')
        }
        check(dynamic.isEmpty()) {
            "Dynamic or ranged dependency selectors are forbidden: ${dynamic.joinToString()}"
        }
    }
}
