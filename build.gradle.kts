import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import dev.mcdevmcp.buildlogic.AppImageTask
import dev.mcdevmcp.buildlogic.DependencyPolicyCheck
import dev.mcdevmcp.buildlogic.McpSdkDependencyCheck
import dev.mcdevmcp.buildlogic.McpbBundleTask
import dev.mcdevmcp.buildlogic.PackagingTools
import dev.mcdevmcp.buildlogic.RuntimeImageTask
import dev.mcdevmcp.buildlogic.Sha256FileTask
import java.nio.file.FileSystems

plugins {
    application
    id("mcdev.java-preview")
    id("mcdev.mcp-sdk-modules")
    id("mcdev.cutover-check")
    id("com.gradleup.shadow")
}

val applicationVersion = providers.gradleProperty("version").get()

val generateTestVersionProperties = tasks.register<WriteProperties>("generateTestVersionProperties") {
    description = "Generates version metadata for Java tests."
    destinationFile = layout.buildDirectory.file("generated-test-resources/version.properties").get().asFile
    property("version", applicationVersion)
}

dependencies {
    implementation(project(":mcp-tool-api"))
    implementation(project(":storage-model"))
    implementation(libs.mcpSdk)
    implementation(libs.picocli)
    implementation(libs.vineflower)
    implementation(libs.tinyRemapper)
    implementation(libs.mappingIo)
    implementation(libs.slf4jNop)

    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitPlatformLauncher)
    testImplementation(libs.jspecify)
}

extraJavaModuleInfo {
    automaticModule("net.fabricmc:tiny-remapper", "net.fabricmc.tinyremapper")
}

application {
    mainModule.set("dev.mcdevmcp")
    mainClass.set("dev.mcdevmcp.app.Main")
    applicationDefaultJvmArgs = listOf("--enable-preview")
}

sourceSets {
    test {
        resources.srcDir(layout.buildDirectory.dir("generated-test-resources"))
    }
}

val runtimeTest = sourceSets.create("runtimeTest") {
    java.srcDir("src/runtimeTest/java")
    resources.srcDir("src/runtimeTest/resources")
    compileClasspath += sourceSets.main.get().output + configurations.runtimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}

dependencies {
    add(runtimeTest.implementationConfigurationName, sourceSets.main.get().output)
    testImplementation(runtimeTest.output)
}

tasks.processTestResources {
    dependsOn(generateTestVersionProperties)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    dependsOn(tasks.named("shadowJar"))
    testLogging.showStandardStreams = true
    systemProperty("dev.mcdevmcp.test.versionFallback", "true")
    systemProperty("mcdevMcpVersion", applicationVersion)
    systemProperty(
        "mcdevMcpJar", layout.buildDirectory.file("libs/mcdev-mcp-$applicationVersion.jar").get().asFile.absolutePath
    )
}

tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("mcdev-mcp")
    archiveClassifier.set("")
    archiveVersion.set(applicationVersion)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
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

tasks.jar {
    archiveClassifier.set("plain")
}

val generatedMcpbManifests = PackagingTools.operatingSystems.associateWith { system ->
    layout.buildDirectory.file("mcpb/manifest-$system.json")
}

val generateMcpbManifest = tasks.register<JavaExec>("generateMcpbManifest") {
    group = "distribution"
    description = "Generates the Java-owned MCPB catalog and the packer manifest of each operating system."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.mcdevmcp.packaging.McpbManifestGenerator")
    args(
        layout.projectDirectory.file("packaging/mcpb/manifest.template.json").asFile.absolutePath,
        layout.projectDirectory.file("manifest.json").asFile.absolutePath,
        layout.buildDirectory.dir("mcpb").get().asFile.absolutePath,
        applicationVersion
    )
    inputs.file(layout.projectDirectory.file("packaging/mcpb/manifest.template.json"))
    inputs.file(layout.projectDirectory.file("src/main/resources/mcp/tools.json"))
    inputs.property("version", applicationVersion)
    outputs.file(layout.projectDirectory.file("manifest.json"))
    outputs.files(generatedMcpbManifests.values)
}

tasks.named("assemble") {
    dependsOn(generateMcpbManifest)
}

val shadedJarOutput = layout.buildDirectory.file("libs/mcdev-mcp-$applicationVersion.jar")
val releaseJar = layout.buildDirectory.file("distributions/mcdev-mcp-$applicationVersion.jar")
val jarChecksum = layout.buildDirectory.file("distributions/mcdev-mcp-$applicationVersion.jar.sha256")

val stageReleaseJar = tasks.register<Copy>("stageReleaseJar") {
    group = "distribution"
    description = "Copies the exact shaded server JAR into the release-asset directory."
    dependsOn(tasks.named("shadowJar"))
    from(shadedJarOutput)
    into(layout.buildDirectory.dir("distributions"))
}

val generateJarChecksum = tasks.register<Sha256FileTask>("generateJarChecksum") {
    group = "distribution"
    description = "Writes the SHA-256 checksum for the exact shaded server JAR."
    dependsOn(stageReleaseJar)
    inputFile.set(releaseJar)
    checksumFile.set(jarChecksum)
}

val runtimeTestBundle = tasks.register<Zip>("runtimeTestBundle") {
    group = "distribution"
    description = "Packages the preview-built JAR, checksum, and compiled runtime smoke harness."
    dependsOn(tasks.named(runtimeTest.classesTaskName), generateJarChecksum)
    archiveBaseName.set("mcdev-mcp-runtime-test")
    archiveVersion.set(applicationVersion)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    archiveClassifier.set("")
    destinationDirectory.set(layout.buildDirectory.dir("bundles"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(releaseJar) { into("server") }
    from(jarChecksum) { into("server") }
    from(runtimeTest.output) { into("harness") }
    duplicatesStrategy = DuplicatesStrategy.FAIL
}

tasks.register<Sync>("releaseArtifactBundle") {
    group = "distribution"
    description = "Stages the complete build-once provenance payload for CI consumers."
    dependsOn(runtimeTestBundle, generateMcpbManifest)
    into(layout.buildDirectory.dir("artifacts/release"))
    from(releaseJar)
    from(jarChecksum)
    from(generatedMcpbManifests.values)
    from(runtimeTestBundle)
    duplicatesStrategy = DuplicatesStrategy.FAIL
}

// The JDK modules the shaded JAR uses, as reported by jdeps, plus jdk.management for ending the
// startup-cache recording and java.naming, which the application module requires.
val runtimeModules = listOf(
    "java.base", "java.desktop", "java.management", "java.naming", "java.net.http", "java.sql", "jdk.compiler", "jdk.management"
)
val packagingJdkHome = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(providers.gradleProperty("javaFeature").get().toInt()))
}.map { launcher -> launcher.metadata.installationPath.asFile.absolutePath }
val bundleVersion = applicationVersion
// CI packages the JAR and manifests that were built and tested once instead of producing its own per platform.
val prebuiltRelease = providers.gradleProperty("prebuiltRelease").orNull?.let { directory -> layout.projectDirectory.dir(directory) }

val runtimeImage = tasks.register<RuntimeImageTask>("runtimeImage") {
    group = "distribution"
    description = "Links the JDK modules the server needs into its own runtime, with a preview class-data archive."
    jdkHome.set(packagingJdkHome)
    modules.set(runtimeModules)
    image.set(layout.buildDirectory.dir("packaging/runtime"))
}

val appImage = tasks.register<AppImageTask>("appImage") {
    group = "distribution"
    description = "Builds the self-contained application image: a native launcher, the runtime and the exact release JAR."
    jdkHome.set(packagingJdkHome)
    runtimeImage.set(tasks.named<RuntimeImageTask>("runtimeImage").flatMap { task -> task.image })
    if (prebuiltRelease != null) {
        mainJar.set(prebuiltRelease.file("mcdev-mcp-$bundleVersion.jar"))
    } else {
        dependsOn(stageReleaseJar)
        mainJar.set(releaseJar)
    }
    applicationName.set("mcdev-mcp")
    applicationVersion.set(bundleVersion)
    mainClass.set(application.mainClass)
    // The JVM logs to standard output unless told otherwise, which would corrupt the MCP stream.
    // A missing or outdated startup cache is expected and handled by the server itself.
    javaOptions.set(
        listOf(
            "--enable-preview",
            "-Xlog:disable",
            "-Xlog:all=warning,aot=off,cds=off:stderr",
            $$"-XX:AOTCache=$APPDIR$${FileSystems.getDefault().separator}mcdev-mcp.aot"
        )
    )
    destination.set(layout.buildDirectory.dir("packaging/image"))
}

tasks.register<Zip>("appImageZip") {
    group = "distribution"
    description = "Archives the application image built for this platform."
    from(appImage.flatMap { task -> task.destination })
    archiveBaseName.set("mcdev-mcp")
    archiveVersion.set(bundleVersion)
    archiveClassifier.set(PackagingTools.platform)
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    useFileSystemPermissions()
}

tasks.register<JavaExec>("appImageSmoke") {
    group = "verification"
    description = "Launches the application image as an MCP client would, before and after it records its startup cache."
    dependsOn(tasks.named(runtimeTest.classesTaskName), appImage)
    classpath = runtimeTest.runtimeClasspath
    mainClass.set("dev.mcdevmcp.packaging.AppImageSmokeMain")
    val image = appImage.flatMap { task -> task.destination }
    val work = layout.buildDirectory.dir("packaging/smoke")
    val version = bundleVersion
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(image.get().asFile.absolutePath, version, work.get().asFile.absolutePath)
    })
}

val mcpbPackaging = layout.projectDirectory.dir("packaging/mcpb")

val installMcpbPacker = tasks.register<Exec>("installMcpbPacker") {
    group = "distribution"
    description = "Installs the pinned MCPB packer."
    workingDir = mcpbPackaging.asFile
    commandLine(PackagingTools.npm, "ci", "--ignore-scripts")
    inputs.files(mcpbPackaging.file("package.json"), mcpbPackaging.file("package-lock.json"))
    outputs.dir(mcpbPackaging.dir("node_modules"))
}

val mcpbBundle = tasks.register<McpbBundleTask>("mcpbBundle") {
    group = "distribution"
    description = "Packs the application image built for this platform as an MCPB bundle."
    dependsOn(installMcpbPacker)
    image.set(appImage.flatMap { task -> task.destination })
    if (prebuiltRelease != null) {
        manifest.set(prebuiltRelease.file("manifest-${PackagingTools.operatingSystem}.json"))
    } else {
        dependsOn(generateMcpbManifest)
        manifest.set(generatedMcpbManifests.getValue(PackagingTools.operatingSystem))
    }
    packer.set(mcpbPackaging.file("node_modules/@anthropic-ai/mcpb/dist/cli/cli.js"))
    bundle.set(layout.buildDirectory.file("distributions/mcdev-mcp-$bundleVersion-${PackagingTools.platform}.mcpb"))
    unpacked.set(layout.buildDirectory.dir("packaging/mcpb"))
}

tasks.register<JavaExec>("mcpbBundleSmoke") {
    group = "verification"
    description = "Starts the unpacked MCPB bundle as its manifest tells a client to, before and after it records its startup cache."
    dependsOn(tasks.named(runtimeTest.classesTaskName), mcpbBundle)
    classpath = runtimeTest.runtimeClasspath
    mainClass.set("dev.mcdevmcp.packaging.McpbBundleSmokeMain")
    val unpacked = mcpbBundle.flatMap { task -> task.unpacked }
    val work = layout.buildDirectory.dir("packaging/mcpb-smoke")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(unpacked.get().asFile.absolutePath, work.get().asFile.absolutePath)
    })
}

tasks.register<JavaExec>("runtimeArtifactSmoke") {
    group = "verification"
    description = "Runs the build-once runtime smoke against the exact shaded JAR without compilation."
    dependsOn(tasks.named(runtimeTest.classesTaskName), generateJarChecksum)
    classpath = files(runtimeTest.output, releaseJar)
    mainClass.set("dev.mcdevmcp.packaging.RuntimeArtifactSmokeMain")
    args(releaseJar.get().asFile.absolutePath)
}

tasks.register<Exec>("releaseVerifierTest") {
    group = "verification"
    description = "Exercises positive and negative release-provenance verifier fixtures."
    dependsOn(generateJarChecksum, generateMcpbManifest)
    commandLine(
        "pwsh",
        "-NoLogo",
        "-NoProfile",
        "-File",
        layout.projectDirectory.file("scripts/test-verify-release-assets.ps1").asFile.absolutePath
    )
}

tasks.named("build") {
    dependsOn(tasks.named("shadowJar"))
}

// The application plugin's startScripts/distribution tasks reference the shadow
// fat-jar output but Gradle 9.x rejects that as an implicit dependency. Declare
// the dependency explicitly so `build` (and the runtime start scripts) work.
tasks.named("startScripts") {
    dependsOn(tasks.named("shadowJar"))
}

val runtimeModuleVersions = configurations.named("runtimeClasspath").map { configuration ->
    configuration.incoming.resolutionResult.allComponents.mapNotNull { component -> component.moduleVersion }
        .associate { module -> "${module.group}:${module.name}" to module.version }
}

val mcpSdkDependencyCheck = tasks.register<McpSdkDependencyCheck>("mcpSdkDependencyCheck") {
    group = "verification"
    description = "Verifies the reviewed MCP SDK runtime dependencies."
    resolvedModules.set(runtimeModuleVersions)
    expectedVersions.set(
        mapOf(
            "io.modelcontextprotocol.sdk:mcp" to libs.versions.mcpSdk.get(),
            "io.modelcontextprotocol.sdk:mcp-core" to libs.versions.mcpSdk.get(),
            "io.modelcontextprotocol.sdk:mcp-json-jackson3" to libs.versions.mcpSdk.get(),
            "tools.jackson.core:jackson-core" to libs.versions.jackson3.get(),
            "tools.jackson.core:jackson-databind" to libs.versions.jackson3.get(),
            "com.fasterxml.jackson.core:jackson-annotations" to libs.versions.jacksonAnnotations.get(),
            "com.networknt:json-schema-validator" to libs.versions.jsonSchemaValidator.get()
        )
    )
}

val dependencyPolicyCheck = tasks.register<DependencyPolicyCheck>("dependencyPolicyCheck") {
    group = "verification"
    description = "Rejects dynamic or ranged dependency versions."
    declaredExternalSelectors.set(providers.provider {
        allprojects.flatMap { candidate ->
            candidate.configurations.flatMap { configuration ->
                configuration.dependencies.withType<ExternalModuleDependency>().map { dependency ->
                    "${dependency.group}:${dependency.name}:${dependency.version.orEmpty()}"
                }
            }
        }.distinct().sorted()
    })
}

tasks.named("check") {
    dependsOn(":mcp-tool-api:check")
    dependsOn(dependencyPolicyCheck)
    dependsOn(mcpSdkDependencyCheck)
    dependsOn(tasks.named("releaseVerifierTest"))
}
