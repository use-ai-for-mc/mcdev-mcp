import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.nio.charset.StandardCharsets
import java.nio.file.Files

@CacheableTask
abstract class Utf8TextFileTask : DefaultTask() {
    @get:Input
    abstract val content: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun writeText() {
        val output = outputFile.get().asFile.toPath()
        Files.createDirectories(output.parent)
        Files.writeString(output, content.get(), StandardCharsets.UTF_8)
    }
}

plugins {
    application
    id("com.gradleup.shadow") version "9.6.1"
}

val applicationVersion = rootProject.providers.gradleProperty("version").get()
val conformanceJavaLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(26))
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(26))
    }
}

dependencies {
    implementation(project(":"))
    implementation(project(":mcp-tool-api"))
    implementation("io.modelcontextprotocol.sdk:mcp:2.0.1")
    implementation("org.apache.tomcat.embed:tomcat-embed-core:11.0.25")
}

application {
    mainClass.set("dev.mcdevmcp.conformance.ConformanceServerMain")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(26)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

val generateConformanceVersionProperties = tasks.register<WriteProperties>("generateConformanceVersionProperties") {
    description = "Generates version metadata for classpath conformance execution."
    destinationFile = layout.buildDirectory.file("generated-resources/version.properties").get().asFile
    property("version", applicationVersion)
}

sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("generated-resources"))
}

tasks.processResources {
    dependsOn(generateConformanceVersionProperties)
}

tasks.register<JavaExec>("conformanceRun") {
    group = "verification"
    description = "Runs the test-only Streamable HTTP conformance server."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    javaLauncher.set(conformanceJavaLauncher)
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.rmi/sun.rmi.transport=ALL-UNNAMED"
    )
    systemProperty("dev.mcdevmcp.test.versionFallback", "true")
    systemProperty("mcdevMcpVersion", applicationVersion)
    providers.environmentVariable("MCDEV_MCP_CONFORMANCE_SHUTDOWN_FILE").orNull?.let { shutdownFile ->
        systemProperty("dev.mcdevmcp.conformance.shutdownFile", shutdownFile)
    }
}

val conformanceJavaExecutable = tasks.register<Utf8TextFileTask>("conformanceJavaExecutable") {
    description = "Records the Java 26 executable used to launch the conformance harness."
    content.set(conformanceJavaLauncher.map { launcher -> launcher.executablePath.asFile.absolutePath })
    outputFile.set(rootProject.layout.buildDirectory.file("conformance/java-executable.txt"))
}

tasks.register<ShadowJar>("conformanceHarnessJar") {
    group = "verification"
    description = "Builds the test-only executable Streamable HTTP conformance harness."
    dependsOn(tasks.named("classes"), conformanceJavaExecutable)
    archiveFileName.set("mcdev-mcp-conformance.jar")
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("conformance"))
    from(sourceSets.main.get().output)
    configurations = listOf(project.configurations.runtimeClasspath.get())
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    append("META-INF/LICENSE")
    append("META-INF/LICENSE.txt")
    append("META-INF/NOTICE")
    exclude("module-info.class", "META-INF/versions/*/module-info.class")
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    exclude(
        "META-INF/services/io.micrometer.context.ContextAccessor",
        "META-INF/services/reactor.blockhound.integration.BlockHoundIntegration"
    )
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
        attributes["Implementation-Version"] = applicationVersion
    }
}

tasks.named<ShadowJar>("shadowJar") {
    enabled = false
}

tasks.jar {
    manifest {
        attributes["Automatic-Module-Name"] = "dev.mcdevmcp.conformance"
    }
}
