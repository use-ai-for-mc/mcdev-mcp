import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import groovy.json.JsonSlurper
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.collections.ArrayDeque

@CacheableTask
abstract class Sha256FileTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputFile: RegularFileProperty

    @get:OutputFile
    abstract val checksumFile: RegularFileProperty

    @TaskAction
    fun writeChecksum() {
        val input = inputFile.get().asFile.toPath()
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(input).use { stream ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val text = "${HexFormat.of().formatHex(digest.digest())}  ${input.fileName}${System.lineSeparator()}"
        val output = checksumFile.get().asFile.toPath()
        Files.createDirectories(output.parent)
        Files.writeString(output, text, StandardCharsets.US_ASCII)
    }
}

@CacheableTask
abstract class DependencyPolicyCheck : DefaultTask() {
    @get:Input
    abstract val declaredExternalSelectors: ListProperty<String>

    @get:Input
    abstract val productionRuntimeModules: SetProperty<String>

    @TaskAction
    fun verifyDependencyPolicy() {
        val dynamic = declaredExternalSelectors.get().filter { selector ->
            val version = selector.substringAfterLast(':')
            version.contains('+') || version.startsWith("latest.") ||
                    version.startsWith('[') || version.startsWith('(') ||
                    version.endsWith(']') || version.endsWith(')')
        }
        check(dynamic.isEmpty()) {
            "Dynamic or ranged dependency selectors are forbidden: ${dynamic.joinToString()}"
        }

        val containerModules = productionRuntimeModules.get().filter { module ->
            module.startsWith("org.apache.tomcat") || module.startsWith("jakarta.servlet")
        }
        check(containerModules.isEmpty()) {
            "Conformance HTTP container leaked into production runtimeClasspath: ${containerModules.joinToString()}"
        }
    }
}

abstract class McpSdkSnapshotCheck : DefaultTask() {
    @get:Input
    abstract val resolvedModules: MapProperty<String, String>

    @TaskAction
    fun verifyRuntimeClasspath() {
        val gsonModuleName = String(charArrayOf('g', 's', 'o', 'n'))
        val expectedVersions = mapOf(
            "io.modelcontextprotocol.sdk:mcp" to "2.0.1",
            "io.modelcontextprotocol.sdk:mcp-core" to "2.0.1",
            "io.modelcontextprotocol.sdk:mcp-json-jackson3" to "2.0.1",
            "tools.jackson.core:jackson-core" to "3.1.4",
            "tools.jackson.core:jackson-databind" to "3.1.4",
            "com.networknt:json-schema-validator" to "3.0.6"
        )
        val runtimeModules = resolvedModules.get()
        val mismatches = expectedVersions.mapNotNull { (module, expectedVersion) ->
            val actualVersion = runtimeModules[module]
            if (actualVersion == expectedVersion) {
                null
            } else {
                "$module resolved $actualVersion, expected $expectedVersion"
            }
        }
        val gsonModules = runtimeModules.filter { (module, _) ->
            module.substringBefore(':').equals("com.google.code.$gsonModuleName", ignoreCase = true) ||
                    module.substringAfter(':').equals(gsonModuleName, ignoreCase = true)
        }

        check(mismatches.isEmpty() && gsonModules.isEmpty()) {
            buildString {
                appendLine("MCP SDK snapshot dependency verification failed.")
                mismatches.forEach(::appendLine)
                gsonModules.forEach { (module, version) ->
                    appendLine("Unexpected ${gsonModuleName.replaceFirstChar { it.uppercase() }} module: $module:$version")
                }
            }
        }
    }
}

plugins {
    application
    id("com.gradleup.shadow") version "9.6.1"
}

val applicationVersion = providers.gradleProperty("version").get()
val testJavaFeature = providers.gradleProperty("testJavaVersion").orElse("26").map { configuredVersion ->
    configuredVersion.toInt().also { feature ->
        require(feature == 26) { "Java 26 is required for testJavaVersion, got $feature" }
    }
}
val testJavaLauncher = javaToolchains.launcherFor {
    languageVersion.set(testJavaFeature.map(JavaLanguageVersion::of))
}

val generateTestVersionProperties = tasks.register<WriteProperties>("generateTestVersionProperties") {
    description = "Generates version metadata for Java tests."
    destinationFile = layout.buildDirectory.file("generated-test-resources/version.properties").get().asFile
    property("version", applicationVersion)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(26))
    }
}
dependencies {
    implementation(project(":mcp-tool-api"))
    implementation("io.modelcontextprotocol.sdk:mcp:2.0.1")
    implementation("info.picocli:picocli:4.7.7")
    implementation("com.h2database:h2:2.4.240")
    implementation("org.vineflower:vineflower:1.12.0")
    implementation("net.fabricmc:tiny-remapper:0.14.1")
    implementation("net.fabricmc:mapping-io:0.9.1")
    implementation("org.slf4j:slf4j-nop:2.0.18")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("dev.mcdevmcp.app.Main")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(26)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
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

tasks.named<JavaCompile>(sourceSets.test.get().compileJavaTaskName) {
    options.release.set(26)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.named<JavaCompile>(runtimeTest.compileJavaTaskName) {
    options.release.set(26)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

val benchmarkClasses = tasks.register("benchmarkClasses") {
    group = "build"
    description = "Compatibility alias for the benchmark module classes task."
    dependsOn(":benchmark:classes")
}

tasks.register("conformanceRun") {
    group = "verification"
    description = "Compatibility alias for the conformance module run task."
    dependsOn(":conformance:conformanceRun")
}

tasks.register("conformanceJavaExecutable") {
    group = "verification"
    description = "Compatibility alias for the conformance Java executable record."
    dependsOn(":conformance:conformanceJavaExecutable")
}

tasks.register("conformanceHarnessJar") {
    group = "verification"
    description = "Compatibility alias for the conformance module harness JAR."
    dependsOn(":conformance:conformanceHarnessJar")
}

tasks.processTestResources {
    dependsOn(generateTestVersionProperties)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    dependsOn(tasks.named("shadowJar"))
    javaLauncher.set(testJavaLauncher)
    testLogging.showStandardStreams = true
    systemProperty("dev.mcdevmcp.test.versionFallback", "true")
    systemProperty("dev.mcdevmcp.test.javaFeature", testJavaFeature.get())
    systemProperty("mcdevMcpVersion", applicationVersion)
    systemProperty(
        "mcdevMcpJar",
        layout.buildDirectory.file("libs/mcdev-mcp-$applicationVersion.jar").get().asFile.absolutePath
    )
    systemProperty("mcdevMcpJava", testJavaLauncher.get().executablePath.asFile.absolutePath)
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("parity")
    }
}

val parityTest = tasks.register<Test>("parityTest") {
    group = "verification"
    description = "Compares the Java server and CLI with the pinned Node oracle."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("parity")
    }
    maxParallelForks = 1
    dependsOn(tasks.named("test"))
    outputs.upToDateWhen { false }
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
        attributes[
            "Main-Class"
        ] = application.mainClass.get()
        attributes["Implementation-Version"] = applicationVersion
    }
}

tasks.jar {
    archiveClassifier.set("plain")
}

val generateMcpbManifest = tasks.register<JavaExec>("generateMcpbManifest") {
    group = "distribution"
    description = "Generates the Java-owned MCPB catalog and packer manifest."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.mcdevmcp.packaging.McpbManifestGenerator")
    args(
        layout.projectDirectory.file("packaging/mcpb/manifest.template.json").asFile.absolutePath,
        layout.projectDirectory.file("manifest.json").asFile.absolutePath,
        layout.buildDirectory.file("mcpb/manifest.json").get().asFile.absolutePath,
        applicationVersion
    )
    inputs.file(layout.projectDirectory.file("packaging/mcpb/manifest.template.json"))
    inputs.file(layout.projectDirectory.file("src/main/resources/mcp/tools.json"))
    inputs.property("version", applicationVersion)
    outputs.file(layout.projectDirectory.file("manifest.json"))
    outputs.file(layout.buildDirectory.file("mcpb/manifest.json"))
}

val mcpbBundleDirectory = providers.gradleProperty("mcpbBundleDirectory")

tasks.register<JavaExec>("mcpbBundleSmoke") {
    group = "verification"
    description = "Runs initialize/tools-list against an extracted MCPB bundle."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.mcdevmcp.packaging.McpbBundleSmokeMain")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(mcpbBundleDirectory.get())
    })
}

tasks.named("assemble") {
    dependsOn(generateMcpbManifest)
}

val shadedJarOutput = layout.buildDirectory.file("libs/mcdev-mcp-$applicationVersion.jar")
val releaseJar = layout.buildDirectory.file("distributions/mcdev-mcp-$applicationVersion.jar")
val jarChecksum = layout.buildDirectory.file("distributions/mcdev-mcp-$applicationVersion.jar.sha256")
val generatedMcpbManifest = layout.buildDirectory.file("mcpb/manifest.json")

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
    description = "Packages the Java-26-built JAR, checksum, manifest, and compiled runtime smoke harness."
    dependsOn(tasks.named(runtimeTest.classesTaskName), generateJarChecksum, generateMcpbManifest)
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
    from(generatedMcpbManifest) { into("server") }
    from(runtimeTest.output) { into("harness") }
    duplicatesStrategy = DuplicatesStrategy.FAIL
}

val benchmarkBundle = tasks.register<Zip>("benchmarkBundle") {
    group = "distribution"
    description = "Packages the Java-26-built JAR, checksum, and compiled benchmark harness."
    dependsOn(benchmarkClasses, generateJarChecksum)
    archiveBaseName.set("mcdev-mcp-benchmark")
    archiveVersion.set(applicationVersion)
    archiveClassifier.set("")
    destinationDirectory.set(layout.buildDirectory.dir("bundles"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(releaseJar) { into("server") }
    from(jarChecksum) { into("server") }
    from(project(":benchmark").layout.buildDirectory.dir("classes/java/main")) { into("harness") }
    from(project(":benchmark").layout.buildDirectory.dir("resources/main")) { into("harness") }
    duplicatesStrategy = DuplicatesStrategy.FAIL
}

tasks.register<Sync>("java26ArtifactBundle") {
    group = "distribution"
    description = "Stages the complete Java-26 build-once provenance payload for CI consumers."
    dependsOn(runtimeTestBundle, benchmarkBundle, generateMcpbManifest)
    into(layout.buildDirectory.dir("artifacts/java26"))
    from(releaseJar)
    from(jarChecksum)
    from(generatedMcpbManifest) {
        rename { "manifest.json" }
    }
    from(runtimeTestBundle)
    from(benchmarkBundle)
    duplicatesStrategy = DuplicatesStrategy.FAIL
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
    configuration.incoming.resolutionResult.allComponents
        .mapNotNull { component -> component.moduleVersion }
        .associate { module -> "${module.group}:${module.name}" to module.version }
}

val mcpSdkSnapshotCheck = tasks.register<McpSdkSnapshotCheck>("mcpSdkSnapshotCheck") {
    group = "verification"
    description = "Verifies the reviewed MCP SDK snapshot runtime dependencies."
    resolvedModules.set(runtimeModuleVersions)
}

val dependencyPolicyCheck = tasks.register<DependencyPolicyCheck>("dependencyPolicyCheck") {
    group = "verification"
    description = "Rejects dynamic dependencies and production-scoped conformance containers."
    declaredExternalSelectors.set(providers.provider {
        allprojects.flatMap { candidate ->
            candidate.configurations.flatMap { configuration ->
                configuration.dependencies.withType<ExternalModuleDependency>().map { dependency ->
                    "${dependency.group}:${dependency.name}:${dependency.version.orEmpty()}"
                }
            }
        }.distinct().sorted()
    })
    productionRuntimeModules.set(runtimeModuleVersions.map { modules -> modules.keys })
}

fun registerCutoverScan(
    name: String,
    descriptionText: String,
    syntheticFiles: Map<String, String> = emptyMap(),
    allowedSyntheticFiles: Set<String> = emptySet()
) = tasks.register(name) {
    group = "verification"
    description = descriptionText
    val repositoryRoot = project.layout.projectDirectory.asFile
    val allowedScriptFiles = setOf("packaging/mcpb/bootstrap.cjs")
    val allowedPackageMetadata = setOf(
        "packaging/mcpb/package.json",
        "packaging/mcpb/package-lock.json"
    )
    val forbiddenMetadataFiles = setOf(
        "tsconfig.json",
        "jest.config.js",
        "eslint.config.js"
    )
    val forbiddenReferences = Regex(
        listOf(
            "\\btype" + "script\\b",
            "\\bts-" + "jest\\b",
            "\\bb" + "un\\b",
            "@modelcontextprotocol/" + "sdk",
            "\\bjava-" + "parser\\b",
            "\\bsql" + "\\.js\\b",
            "\\bgso" + "n\\b",
            "\\bjson" + "node\\b",
            "\\bjava-" + "callgraph" + "2\\b",
            "\\bjava" + "cg(?:-static)?(?:\\.jar)?\\b",
            "\\bcallgraph" + "\\.txt\\b",
            "\\b(?:build|copy)-java-worker\\b",
            "\\bMCDEV_AST_" + "PARSER\\b",
            "\\bMCDEV_" + "INDEXER\\b",
            "\\bMCDEV_SUPPRESS_INDEXER_" + "HINT\\b",
            "\\bMCDEV_JAVA_WORKER_" + "COMMAND\\b",
            "\\bMCDEV_JAVA_WORKER_ARGS_" + "JSON\\b",
            "\\bMCDEV_INDEX_" + "WORKERS\\b",
            "\\bMCDEV_INDEX_BATCH_" + "SIZE\\b",
            "\\bMCDEV_INDEX_WORKER_HEAP_" + "MB\\b",
            "\\bMCDEV_INDEX_WORKER_RETRY_HEAP_" + "MB\\b",
            "\\bMCDEV_INDEX_PARSE_WORKER_" + "PATH\\b",
            "\\bMCDEV_INDEX_WORKER_" + "MARKER\\b",
            "\\bMCDEV_INDEX_SINGLE_FILE_" + "FALLBACK\\b",
            "\\bMCDEV_MCP_REMAPPER_" + "HEAP\\b",
            "\\bMCDEV_ARGV_" + "CAPTURE\\b",
            "package[._-]?json[._-]?(?:index(?:er|ers|es)?|readers?|writers?)"
        ).joinToString("|"),
        RegexOption.IGNORE_CASE
    )
    doLast {
        val trackedFiles = if (syntheticFiles.isEmpty()) {
            val git = ProcessBuilder("git", "ls-files", "-z")
                .directory(repositoryRoot)
                .redirectErrorStream(true)
                .start()
            val trackedOutput = git.inputStream.readBytes()
            check(git.waitFor() == 0) {
                "Unable to list tracked files for cutoverCheck: ${trackedOutput.toString(StandardCharsets.UTF_8)}"
            }
            trackedOutput.toString(StandardCharsets.UTF_8)
                .split('\u0000')
                .filter(String::isNotEmpty)
        } else {
            syntheticFiles.keys.toList()
        }
        val violations = mutableListOf<String>()

        trackedFiles.forEach { path ->
            val normalizedPath = path.replace('\\', '/')
            val lowercasePath = normalizedPath.lowercase()
            val fileName = lowercasePath.substringAfterLast('/')
            if (
                lowercasePath.endsWith(".ts") ||
                lowercasePath.endsWith(".tsx") ||
                ((lowercasePath.endsWith(".js") ||
                        lowercasePath.endsWith(".mjs") ||
                        lowercasePath.endsWith(".cjs")) &&
                        normalizedPath !in allowedScriptFiles) ||
                ((fileName == "package.json" || fileName == "package-lock.json") &&
                        normalizedPath !in allowedPackageMetadata) ||
                fileName in forbiddenMetadataFiles ||
                lowercasePath.startsWith("java-worker/")
            ) {
                violations += "forbidden tracked file: $normalizedPath"
            }

            val isPackagingLockfile = normalizedPath == "packaging/mcpb/package-lock.json"
            val mustInspectContents =
                (lowercasePath.endsWith(".json") &&
                        normalizedPath != "contracts/node-oracle.json" &&
                        !isPackagingLockfile &&
                        !normalizedPath.startsWith("src/test/resources/contracts/") &&
                        !normalizedPath.startsWith("src/test/resources/oracle/") &&
                        !normalizedPath.startsWith("docs/superpowers/")) ||
                        normalizedPath == "build.gradle.kts" ||
                        normalizedPath == "settings.gradle.kts" ||
                        normalizedPath.endsWith(".gradle") ||
                        normalizedPath.endsWith(".gradle.kts") ||
                        normalizedPath.endsWith(".toml") ||
                        normalizedPath.endsWith(".properties") ||
                        normalizedPath.startsWith(".github/") ||
                        normalizedPath.startsWith("scripts/") ||
                        normalizedPath.startsWith("src/main/")
            if (mustInspectContents) {
                val file = repositoryRoot.toPath().resolve(path)
                val syntheticContents = syntheticFiles[path]
                if (syntheticContents != null || Files.isRegularFile(file)) {
                    val contents = syntheticContents ?: Files.readString(file)
                    val forbiddenNodeMetadata = if (lowercasePath.endsWith(".json")) {
                        val isPackagingMetadata = normalizedPath.startsWith("packaging/mcpb/")
                        val parsedJson = runCatching { JsonSlurper().parseText(contents) }
                            .getOrElse { cause ->
                                violations += "invalid inspected JSON metadata: $normalizedPath (${cause.message})"
                                null
                            }
                        val jsonFields = mutableListOf<Pair<String, String>>()
                        val pendingJsonValues = ArrayDeque<Pair<Any, String?>>()
                        if (parsedJson != null) {
                            pendingJsonValues.add(parsedJson to null)
                        }
                        while (pendingJsonValues.isNotEmpty()) {
                            val (jsonValue, parentKey) = pendingJsonValues.removeFirst()
                            when (jsonValue) {
                                is Map<*, *> -> jsonValue.forEach { (key, nestedValue) ->
                                    if (key is String && nestedValue is String) {
                                        jsonFields += key to nestedValue
                                    }
                                    if (nestedValue != null) {
                                        pendingJsonValues.add(nestedValue to (key as? String ?: parentKey))
                                    }
                                }

                                is Iterable<*> -> jsonValue.forEach { nestedValue ->
                                    if (nestedValue != null) {
                                        pendingJsonValues.add(nestedValue to parentKey)
                                    }
                                }
                            }
                        }
                        fun normalizedMetadataKey(key: String) =
                            key.lowercase().replace("_", "").replace("-", "")

                        fun normalizedEntrypoint(value: String) =
                            value.trim().replace('\\', '/').removePrefix("./")

                        fun hasNodeOptionsMetadata(root: Any): Boolean {
                            val environmentKeys = setOf("env", "environment")
                            val pendingEnvironmentValues = ArrayDeque<Pair<Any, Boolean>>()
                            pendingEnvironmentValues.add(root to false)
                            while (pendingEnvironmentValues.isNotEmpty()) {
                                val (value, assignmentList) = pendingEnvironmentValues.removeFirst()
                                when (value) {
                                    is Map<*, *> -> value.forEach { (key, nestedValue) ->
                                        val normalizedKey = (key as? String)?.let(::normalizedMetadataKey)
                                        if (normalizedKey == "nodeoptions") {
                                            return true
                                        }
                                        if (nestedValue != null) {
                                            pendingEnvironmentValues.add(
                                                nestedValue to
                                                        (normalizedKey in environmentKeys &&
                                                                (nestedValue is String || nestedValue is Iterable<*>))
                                            )
                                        }
                                    }

                                    is Iterable<*> -> value.filterNotNull().forEach { nestedValue ->
                                        pendingEnvironmentValues.add(
                                            nestedValue to
                                                    (assignmentList &&
                                                            (nestedValue is String || nestedValue is Iterable<*>))
                                        )
                                    }

                                    is String -> {
                                        val assignment = value.indexOf('=')
                                        if (assignmentList &&
                                            assignment > 0 &&
                                            normalizedMetadataKey(
                                                value.substring(0, assignment).trim()
                                            ) == "nodeoptions"
                                        ) {
                                            return true
                                        }
                                    }
                                }
                            }
                            return false
                        }

                        val allowedEntrypoints = setOf(
                            "bootstrap.cjs",
                            "packaging/mcpb/bootstrap.cjs"
                        )
                        val javascriptEntrypoint = Regex(
                            """(?i)\b[^\s"';|&()]*\.(?:js|mjs|cjs|ts|tsx)\b"""
                        )

                        fun hasForbiddenJavaScriptEntrypoint(value: String) =
                            javascriptEntrypoint.findAll(value).any { match ->
                                !isPackagingMetadata ||
                                        normalizedEntrypoint(match.value) !in allowedEntrypoints
                            }

                        fun hasForbiddenEntrypointTarget(value: String) =
                            if (isPackagingMetadata) {
                                normalizedEntrypoint(value) !in allowedEntrypoints
                            } else {
                                hasForbiddenJavaScriptEntrypoint(value)
                            }

                        fun normalizedExecutableName(value: String) =
                            value.trim().replace('\\', '/').substringAfterLast('/').lowercase().removeSuffix(".exe")

                        fun isNodeCommand(value: String) = normalizedExecutableName(value) == "node"

                        fun parseCommandSegments(value: String): List<List<String>>? {
                            val segments = mutableListOf<List<String>>()
                            val words = mutableListOf<String>()
                            val word = StringBuilder()
                            var wordStarted = false
                            var quote: Char? = null
                            var requiresSegment = false
                            var index = 0

                            fun finishWord() {
                                if (wordStarted) {
                                    words += word.toString()
                                    word.setLength(0)
                                    wordStarted = false
                                }
                            }

                            fun finishSegment(requiresFollowingSegment: Boolean): Boolean {
                                finishWord()
                                if (words.isEmpty()) {
                                    return false
                                }
                                segments += words.toList()
                                words.clear()
                                requiresSegment = requiresFollowingSegment
                                return true
                            }

                            while (index < value.length) {
                                val character = value[index]
                                if (quote != null) {
                                    when (character) {
                                        quote -> quote = null
                                        '\\' -> {
                                            if (index + 1 < value.length && value[index + 1] == quote) {
                                                index++
                                                word.append(value[index])
                                            } else {
                                                word.append(character)
                                            }
                                        }

                                        else -> word.append(character)
                                    }
                                    wordStarted = true
                                    index++
                                    continue
                                }

                                when {
                                    character == '\'' || character == '"' -> {
                                        quote = character
                                        wordStarted = true
                                    }

                                    character == '\r' || character == '\n' || character == ';' -> {
                                        finishWord()
                                        if (words.isNotEmpty()) {
                                            finishSegment(false)
                                        } else if (requiresSegment && character == ';') {
                                            return null
                                        }
                                        if (character == '\r' &&
                                            index + 1 < value.length &&
                                            value[index + 1] == '\n'
                                        ) {
                                            index++
                                        }
                                    }

                                    character == '&' -> {
                                        if (index + 1 >= value.length || value[index + 1] != '&') {
                                            return null
                                        }
                                        if (!finishSegment(true)) {
                                            return null
                                        }
                                        index++
                                    }

                                    character == '|' -> {
                                        if (!finishSegment(true)) {
                                            return null
                                        }
                                        if (index + 1 < value.length && value[index + 1] == '|') {
                                            index++
                                        }
                                    }

                                    character.isWhitespace() -> finishWord()
                                    character == '\\' && index + 1 < value.length &&
                                            (value[index + 1].isWhitespace() ||
                                                    value[index + 1] in setOf('\'', '"', ';', '&', '|')) -> {
                                        index++
                                        word.append(value[index])
                                        wordStarted = true
                                    }

                                    else -> {
                                        word.append(character)
                                        wordStarted = true
                                    }
                                }
                                index++
                            }

                            if (quote != null) {
                                return null
                            }
                            finishWord()
                            if (words.isNotEmpty()) {
                                finishSegment(false)
                            } else if (requiresSegment) {
                                return null
                            }
                            return segments
                        }

                        fun resolveCommandSegment(words: List<String>): Pair<Int, Boolean>? {
                            val assignmentName = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
                            var executableIndex = 0
                            var hasNodeOptions = false

                            fun consumeAssignments() {
                                while (executableIndex < words.size) {
                                    val assignment = words[executableIndex].indexOf('=')
                                    if (assignment <= 0) {
                                        break
                                    }
                                    val name = words[executableIndex].substring(0, assignment)
                                    if (!assignmentName.matches(name)) {
                                        break
                                    }
                                    hasNodeOptions = hasNodeOptions ||
                                            normalizedMetadataKey(name) == "nodeoptions"
                                    executableIndex++
                                }
                            }

                            consumeAssignments()
                            while (executableIndex < words.size) {
                                when (normalizedExecutableName(words[executableIndex])) {
                                    "env" -> {
                                        executableIndex++
                                        if (executableIndex < words.size && words[executableIndex] == "--") {
                                            executableIndex++
                                        } else if (executableIndex < words.size &&
                                            words[executableIndex].startsWith("-")
                                        ) {
                                            return null
                                        }
                                        consumeAssignments()
                                    }

                                    "exec", "command" -> {
                                        executableIndex++
                                        if (executableIndex < words.size && words[executableIndex] == "--") {
                                            executableIndex++
                                        } else if (executableIndex < words.size &&
                                            words[executableIndex].startsWith("-")
                                        ) {
                                            return null
                                        }
                                    }

                                    else -> break
                                }
                            }
                            if (executableIndex < words.size && words[executableIndex].contains('=')) {
                                return null
                            }
                            return executableIndex.takeIf { it < words.size }
                                ?.let { it to hasNodeOptions }
                        }

                        val nodeToken = Regex("""(?i)\bnode(?:\.exe)?\b""")
                        val nodeOptionsToken = Regex("""(?i)\bNODE_OPTIONS\b""")

                        fun hasForbiddenCommandWords(words: List<String>): Boolean {
                            val (executableIndex, hasNodeOptions) = resolveCommandSegment(words)
                                ?: return words.any { word ->
                                    nodeToken.containsMatchIn(word) ||
                                            nodeOptionsToken.containsMatchIn(word) ||
                                            javascriptEntrypoint.containsMatchIn(word)
                                }
                            if (hasNodeOptions || words.any(nodeOptionsToken::containsMatchIn)) {
                                return true
                            }
                            val executable = words[executableIndex]
                            val arguments = words.drop(executableIndex + 1)
                            if (isNodeCommand(executable)) {
                                return !isPackagingMetadata ||
                                        arguments.size != 1 ||
                                        normalizedEntrypoint(arguments.single()) !in allowedEntrypoints
                            }
                            return words.drop(executableIndex).any { word ->
                                nodeToken.containsMatchIn(word) ||
                                        javascriptEntrypoint.containsMatchIn(word)
                            }
                        }

                        fun hasForbiddenNodeCommandText(value: String): Boolean {
                            val segments = parseCommandSegments(value)
                                ?: return nodeToken.containsMatchIn(value) ||
                                        nodeOptionsToken.containsMatchIn(value) ||
                                        javascriptEntrypoint.containsMatchIn(value)
                            if (isPackagingMetadata &&
                                segments.size != 1 &&
                                segments.flatten().any { word ->
                                    nodeToken.containsMatchIn(word) ||
                                            nodeOptionsToken.containsMatchIn(word) ||
                                            javascriptEntrypoint.containsMatchIn(word)
                                }
                            ) {
                                return true
                            }
                            for (segment in segments) {
                                if (hasForbiddenCommandWords(segment)) {
                                    return true
                                }
                            }
                            return false
                        }

                        fun stringArguments(value: Any?): List<String> = when (value) {
                            is String -> listOf(value)
                            is Map<*, *> -> value.values.flatMap(::stringArguments)
                            is Iterable<*> -> value.flatMap(::stringArguments)
                            else -> emptyList()
                        }

                        fun hasForbiddenStructuredNodeInvocation(root: Any): Boolean {
                            val pendingCommands = ArrayDeque<Any>()
                            pendingCommands.add(root)
                            while (pendingCommands.isNotEmpty()) {
                                when (val value = pendingCommands.removeFirst()) {
                                    is Map<*, *> -> {
                                        val fields = value.entries.associate { (key, fieldValue) ->
                                            (key as? String)?.let(::normalizedMetadataKey) to fieldValue
                                        }
                                        val command = fields["command"]
                                            ?: listOf("executable", "program")
                                                .firstNotNullOfOrNull { fields[it] as? String }
                                        val explicitArguments = fields["args"] ?: fields["arguments"]
                                        val (executable, arguments) = when (command) {
                                            is String -> if (explicitArguments == null) {
                                                if (hasForbiddenNodeCommandText(command)) {
                                                    return true
                                                }
                                                null to emptyList()
                                            } else {
                                                command to stringArguments(explicitArguments)
                                            }

                                            is Iterable<*> -> {
                                                val commandParts = command.toList()
                                                (commandParts.firstOrNull() as? String) to
                                                        (stringArguments(commandParts.drop(1)) +
                                                                stringArguments(explicitArguments))
                                            }

                                            is Map<*, *> -> {
                                                val commandFields = command.entries.associate { (key, fieldValue) ->
                                                    (key as? String)?.let(::normalizedMetadataKey) to fieldValue
                                                }
                                                val commandExecutable =
                                                    listOf("command", "executable", "program")
                                                        .firstNotNullOfOrNull { commandFields[it] as? String }
                                                val commandArguments =
                                                    commandFields["args"] ?: commandFields["arguments"]
                                                commandExecutable to
                                                        (stringArguments(commandArguments) +
                                                                stringArguments(explicitArguments))
                                            }

                                            else -> null to emptyList()
                                        }
                                        if (executable != null &&
                                            hasForbiddenCommandWords(listOf(executable) + arguments)
                                        ) {
                                            return true
                                        }
                                        value.values.filterNotNull().forEach(pendingCommands::add)
                                    }

                                    is Iterable<*> -> value.filterNotNull().forEach(pendingCommands::add)
                                }
                            }
                            return false
                        }

                        val entrypointKeys = setOf("main", "module", "browser", "bin", "exports", "entrypoint")
                        val nodeRuntime = jsonFields.any { (key, value) ->
                            val normalizedKey = normalizedMetadataKey(key)
                            (normalizedKey == "runtime" || normalizedKey == "type") &&
                                    value.equals("node", ignoreCase = true) ||
                                    normalizedKey == "command" &&
                                    (value.equals("node", ignoreCase = true) ||
                                            value.equals("node.exe", ignoreCase = true))
                        }
                        val javascriptMetadata = jsonFields.any { (key, value) ->
                            val normalizedKey = normalizedMetadataKey(key)
                            val isEntrypoint = normalizedKey in entrypointKeys
                            val isCommand = normalizedKey == "command"
                            isEntrypoint && hasForbiddenEntrypointTarget(value) ||
                                    isCommand &&
                                    (!isPackagingMetadata || !isNodeCommand(value)) &&
                                    hasForbiddenNodeCommandText(value)
                        }
                        val nestedJavaScriptMetadata = mutableListOf<Boolean>()
                        val nestedEntrypointKeys = setOf("bin", "exports", "browser")
                        val nestedMetadataKeys = nestedEntrypointKeys + "scripts"
                        val pendingMetadataValues = ArrayDeque<Pair<Any, String?>>()
                        if (parsedJson != null) {
                            pendingMetadataValues.add(parsedJson to null)
                        }
                        while (pendingMetadataValues.isNotEmpty()) {
                            val (jsonValue, parentKey) = pendingMetadataValues.removeFirst()
                            when (jsonValue) {
                                is Map<*, *> -> jsonValue.forEach { (key, nestedValue) ->
                                    val normalizedKey = (key as? String)?.let(::normalizedMetadataKey)
                                    if (nestedValue is String &&
                                        (parentKey in nestedMetadataKeys ||
                                                normalizedKey == "command")
                                    ) {
                                        nestedJavaScriptMetadata +=
                                            parentKey in nestedEntrypointKeys &&
                                                    hasForbiddenEntrypointTarget(nestedValue) ||
                                                    (parentKey == "scripts" || normalizedKey == "command") &&
                                                    (!isPackagingMetadata ||
                                                            normalizedKey != "command" ||
                                                            !isNodeCommand(nestedValue)) &&
                                                    hasForbiddenNodeCommandText(nestedValue)
                                    }
                                    if (nestedValue != null) {
                                        pendingMetadataValues.add(
                                            nestedValue to
                                                    (normalizedKey?.takeIf { it in nestedMetadataKeys } ?: parentKey)
                                        )
                                    }
                                }

                                is Iterable<*> -> jsonValue.forEach { nestedValue ->
                                    if (nestedValue != null) {
                                        pendingMetadataValues.add(nestedValue to parentKey)
                                    }
                                }
                            }
                        }
                        if (isPackagingMetadata) {
                            javascriptMetadata ||
                                    nestedJavaScriptMetadata.any { it } ||
                                    parsedJson != null && hasNodeOptionsMetadata(parsedJson) ||
                                    parsedJson != null && hasForbiddenStructuredNodeInvocation(parsedJson)
                        } else {
                            nodeRuntime ||
                                    javascriptMetadata ||
                                    nestedJavaScriptMetadata.any { it } ||
                                    parsedJson != null && hasForbiddenStructuredNodeInvocation(parsedJson)
                        }
                    } else {
                        false
                    }
                    if (forbiddenReferences.containsMatchIn(contents) || forbiddenNodeMetadata) {
                        violations += "forbidden production/build reference: $normalizedPath"
                    }
                }
            }
        }

        if (syntheticFiles.isEmpty()) {
            check(violations.isEmpty()) {
                "Early worktree cutover is incomplete:\n${violations.joinToString("\n")}"
            }
        } else {
            val unreportedBypasses = syntheticFiles.keys
                .filterNot { it in allowedSyntheticFiles }
                .filter { path -> violations.none { path in it } }
            val rejectedAllowedFiles = allowedSyntheticFiles
                .filter { path -> violations.any { path in it } }
            check(unreportedBypasses.isEmpty() && rejectedAllowedFiles.isEmpty()) {
                buildString {
                    appendLine("cutoverCheck bypass regression failed")
                    if (unreportedBypasses.isNotEmpty()) {
                        appendLine("unreported forbidden fixtures:")
                        appendLine(unreportedBypasses.joinToString("\n"))
                    }
                    if (rejectedAllowedFiles.isNotEmpty()) {
                        appendLine("rejected permitted fixtures:")
                        appendLine(rejectedAllowedFiles.joinToString("\n"))
                    }
                    append("reported violations:\n${violations.joinToString("\n")}")
                }
            }
        }
    }
}

val retiredEnvironmentFixtures = listOf(
    "MCDEV_" + "INDEXER",
    "MCDEV_AST_" + "PARSER",
    "MCDEV_SUPPRESS_INDEXER_" + "HINT",
    "MCDEV_JAVA_WORKER_" + "COMMAND",
    "MCDEV_JAVA_WORKER_ARGS_" + "JSON",
    "MCDEV_INDEX_" + "WORKERS",
    "MCDEV_INDEX_BATCH_" + "SIZE",
    "MCDEV_INDEX_WORKER_HEAP_" + "MB",
    "MCDEV_INDEX_WORKER_RETRY_HEAP_" + "MB",
    "MCDEV_INDEX_PARSE_WORKER_" + "PATH",
    "MCDEV_INDEX_WORKER_" + "MARKER",
    "MCDEV_INDEX_SINGLE_FILE_" + "FALLBACK",
    "MCDEV_MCP_REMAPPER_" + "HEAP",
    "MCDEV_ARGV_" + "CAPTURE"
)
val retiredLibraryFixtures = listOf(
    "type" + "script",
    "ts-" + "jest",
    "b" + "un",
    "@modelcontextprotocol/" + "sdk",
    "java-" + "parser",
    "sql" + ".js",
    "gso" + "n",
    "Json" + "Node",
    "java-" + "callgraph" + "2",
    "java" + "cg-static.jar",
    "callgraph" + ".txt",
    "build-java-" + "worker",
    "Package" + "JsonIndexer"
)
val cutoverBypassFixtures = linkedMapOf(
    ".cutover-fixtures/server.ts" to "export {}",
    ".cutover-fixtures/server.tsx" to "export {}",
    ".cutover-fixtures/server.js" to "export {}",
    ".cutover-fixtures/server.mjs" to "export {}",
    ".cutover-fixtures/server.cjs" to "module.exports = {}",
    ".cutover-fixtures/package.json" to "{}",
    ".cutover-fixtures/package-lock.json" to "{}",
    ".cutover-fixtures/tsconfig.json" to "{}",
    ".cutover-fixtures/jest.config.js" to "module.exports = {}",
    ".cutover-fixtures/eslint.config.js" to "module.exports = {}",
    "java-worker/protocol.txt" to "retired worker",
    "src/main/resources/guides/retired.txt" to "The type" + "script client lives in src/tools/runtime/session" + ".ts",
    ("src/main/java/cutover/Package" + "JsonIndexer.java") to "final class Package" + "JsonIndexer {}",
    ("src/main/java/cutover/Package" + "JsonReader.java") to "final class PackageTools { Object package" + "JsonReader; }",
    ("src/main/java/cutover/Package" + "JsonWriter.java") to "final class PackageTools { Object package_" + "json_writer; }",
    ("src/main/java/cutover/Package" + "JsonIndexerImpl.java") to "final class Package" + "JsonIndexerImpl {}",
    ("src/main/java/cutover/Package" + "JsonIndexerFactory.java") to "final class Package" + "JsonIndexerFactory {}",
    ("src/main/java/cutover/Package" + "JsonReaderFactory.java") to "final class Package" + "JsonReaderFactory {}",
    ("src/main/java/cutover/Package" + "JsonIndexReader.java") to "final class Package" + "JsonIndexReader {}",
    ("src/main/java/cutover/LegacyPackage" + "JsonIndexer.java") to "final class LegacyPackage" + "JsonIndexer {}",
    "src/main/resources/cutover/legacy-package-json.properties" to "legacy_package_" + "json_writer=retired",
    "src/main/resources/cutover/package-json.properties" to "package-" + "json-indexer=retired",
    ".cutover-fixtures/root-node.json" to "{\"runtime\":\"no" + "de\"}",
    ".cutover-fixtures/root-command.json" to "{\"command\":\"no" + "de escape.cjs\"}",
    ".cutover-fixtures/root-structured-command.json" to
            "{\"command\":\"no" + "de\",\"args\":[\"escape.cjs\"]}",
    ".cutover-fixtures/shell-wrapper.json" to "{\"command\":\"sh -c 'node escape.cjs'\"}",
    "packaging/mcpb/escape.json" to "{\"command\":\"no" + "de ../escape.cjs\"}",
    "packaging/mcpb/bare-node.json" to "{\"command\":\"node\"}",
    "packaging/mcpb/shell-wrapper.json" to "{\"command\":\"sh -c 'node bootstrap.cjs'\"}",
    "packaging/mcpb/structured-shell.json" to
            "{\"command\":\"sh\",\"args\":[\"-c\",\"node escape.cjs\"]}",
    "packaging/mcpb/nested-command.json" to
            "{\"server\":{\"command\":{\"executable\":\"node\",\"arguments\":[\"escape.cjs\"]}}}",
    "packaging/mcpb/nested-executable.json" to
            "{\"server\":{\"executable\":\"node\",\"arguments\":[\"escape.cjs\"]}}",
    "packaging/mcpb/nested-program.json" to
            "{\"server\":{\"program\":\"node\",\"args\":[\"escape.cjs\"]}}",
    "packaging/mcpb/node-options-map.json" to
            "{\"command\":\"node\",\"args\":[\"bootstrap.cjs\"],\"env\":{\"NODE_OPTIONS\":\"--require escape.cjs\"}}",
    "packaging/mcpb/node-options-list.json" to
            "{\"command\":\"node\",\"args\":[\"bootstrap.cjs\"],\"environment\":[\"NODE_OPTIONS=--require escape.cjs\"]}",
    "packaging/mcpb/node-options-inline.json" to
            "{\"command\":\"NODE_OPTIONS=--require=escape.cjs node bootstrap.cjs\"}",
    "packaging/mcpb/env-option-wrapper.json" to
            "{\"command\":\"env -S node bootstrap.cjs\"}",
    "packaging/mcpb/exec-option-wrapper.json" to
            "{\"command\":\"exec -a node bootstrap.cjs\"}",
    "packaging/mcpb/malformed-chain.json" to "{\"command\":\"node bootstrap.cjs &&\"}",
    "packaging/mcpb/unclosed-quote.json" to "{\"command\":\"sh -c 'node bootstrap.cjs\"}",
    "packaging/mcpb/pipeline.json" to "{\"command\":\"echo ok | node bootstrap.cjs\"}",
    "packaging/mcpb/semicolon.json" to "{\"command\":\"echo ok; node bootstrap.cjs\"}",
    "packaging/mcpb/direct-script.json" to "{\"command\":\"./bootstrap.cjs\"}"
).apply {
    retiredEnvironmentFixtures.forEachIndexed { index, value ->
        put("src/main/java/cutover/RetiredEnvironment$index.java", value)
    }
    retiredLibraryFixtures.forEachIndexed { index, value ->
        put("src/main/java/cutover/RetiredLibrary$index.java", value)
    }
    put("packaging/mcpb/bootstrap.cjs", "permitted launcher")
    put("packaging/mcpb/package.json", "{\"command\":\"no" + "de bootstrap.cjs\"}")
    put("packaging/mcpb/package-lock.json", "{\"lockfileVersion\":3}")
    put("packaging/mcpb/allowed-structured.json", "{\"command\":\"node\",\"args\":[\"bootstrap.cjs\"]}")
    put("packaging/mcpb/allowed-relative.json", "{\"command\":[\"node\",\"./bootstrap.cjs\"]}")
    put("packaging/mcpb/allowed-executable.json", "{\"server\":{\"executable\":\"node\",\"args\":[\"bootstrap.cjs\"]}}")
    put("packaging/mcpb/allowed-env-wrapper.json", "{\"command\":\"env -- node bootstrap.cjs\"}")
    put("packaging/mcpb/allowed-exec-wrapper.json", "{\"command\":\"exec -- node ./bootstrap.cjs\"}")
    put("packaging/mcpb/allowed-assignment-wrapper.json", "{\"command\":\"MCPB=1 node bootstrap.cjs\"}")
    put("contracts/node-oracle.json", "{\"runtime\":\"no" + "de\"}")
    put("src/test/resources/contracts/cutover/frozen.json", "{\"runtime\":\"no" + "de\"}")
    put("src/test/resources/oracle/cutover/frozen.json", "{\"runtime\":\"no" + "de\"}")
    put(
        "src/main/resources/guides/package-json-transition.txt",
        "Legacy package JSON readers and writers are historical documentation, not production identifiers."
    )
}
val allowedCutoverFixtures = setOf(
    "packaging/mcpb/bootstrap.cjs",
    "packaging/mcpb/package.json",
    "packaging/mcpb/package-lock.json",
    "packaging/mcpb/allowed-structured.json",
    "packaging/mcpb/allowed-relative.json",
    "packaging/mcpb/allowed-executable.json",
    "packaging/mcpb/allowed-env-wrapper.json",
    "packaging/mcpb/allowed-exec-wrapper.json",
    "packaging/mcpb/allowed-assignment-wrapper.json",
    "contracts/node-oracle.json",
    "src/test/resources/contracts/cutover/frozen.json",
    "src/test/resources/oracle/cutover/frozen.json",
    "src/main/resources/guides/package-json-transition.txt"
)

val cutoverCheck = registerCutoverScan(
    "cutoverCheck",
    "Rejects tracked retired implementation surface."
)
val cutoverCheckBypassTest = registerCutoverScan(
    "cutoverCheckBypassTest",
    "Regression-tests cutoverCheck against forbidden and permitted synthetic tracked files.",
    cutoverBypassFixtures,
    allowedCutoverFixtures
)

tasks.named("check") {
    dependsOn(":mcp-tool-api:check")
    dependsOn(":benchmark:test")
    dependsOn(":conformance:classes")
    dependsOn(cutoverCheck)
    dependsOn(cutoverCheckBypassTest)
    dependsOn(dependencyPolicyCheck)
    dependsOn(mcpSdkSnapshotCheck)
    dependsOn(tasks.named("releaseVerifierTest"))
}
