import org.gradle.api.Action
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JavaToolchainSpec

plugins {
    java
}

val javaFeature = providers.gradleProperty("javaFeature").get().toInt()
val previewJavaLauncher = extensions.getByType(JavaToolchainService::class.java).launcherFor(Action<JavaToolchainSpec> {
    languageVersion.set(JavaLanguageVersion.of(javaFeature))
})

extensions.configure<JavaPluginExtension>("java") {
    modularity.inferModulePath.set(true)
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(javaFeature))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(javaFeature)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(
        listOf(
            "-Xlint:all",
            "-Xlint:-preview",
            "-Xlint:-requires-automatic",
            "-Xlint:-requires-transitive-automatic",
            "-Werror",
            "--enable-preview"
        )
    )
}

// Gradle 9.8 writes the module main class by rewriting module-info.class with a
// bundled ASM that rejects Java 28 class files. Every launch names its main class,
// so the attribute is not needed.
pluginManager.withPlugin("application") {
    tasks.withType<JavaCompile>().configureEach {
        options.javaModuleMainClass.unsetConvention()
        options.javaModuleMainClass.unset()
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("--enable-preview")
    systemProperty("dev.mcdevmcp.test.javaFeature", javaFeature.toString())
    systemProperty("mcdevMcpJava", previewJavaLauncher.get().executablePath.asFile.absolutePath)
}

tasks.withType<JavaExec>().configureEach {
    javaLauncher.set(previewJavaLauncher)
    jvmArgs("--enable-preview")
}
