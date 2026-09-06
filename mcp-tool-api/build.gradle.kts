plugins {
    `java-library`
    id("org.gradlex.extra-java-module-info") version "1.14.2"
}

val testJavaFeature = providers.gradleProperty("testJavaVersion").orElse("26").map { configuredVersion ->
    configuredVersion.toInt().also { feature ->
        require(feature == 26) { "Java 26 is required for testJavaVersion, got $feature" }
    }
}
val testJavaLauncher = javaToolchains.launcherFor {
    languageVersion.set(testJavaFeature.map(JavaLanguageVersion::of))
}

java {
    modularity.inferModulePath.set(true)
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(26))
    }
}

dependencies {
    api("io.modelcontextprotocol.sdk:mcp-core:2.0.1")
    compileOnly("jakarta.servlet:jakarta.servlet-api:6.1.0")

    testImplementation("io.modelcontextprotocol.sdk:mcp-json-jackson3:2.0.1")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

extraJavaModuleInfo {
    failOnMissingModuleInfo.set(false)
    failOnAutomaticModules.set(false)

    module("io.modelcontextprotocol.sdk:mcp-core", "io.modelcontextprotocol.sdk.mcp.core") {
        overrideModuleName()
        exportAllPackages()
        requiresTransitive("com.fasterxml.jackson.annotation")
        requiresStaticTransitive("jakarta.servlet")
        requiresTransitive("java.net.http")
        requiresTransitive("org.reactivestreams")
        requiresTransitive("org.slf4j")
        requiresTransitive("reactor.core")
        uses("io.modelcontextprotocol.json.McpJsonMapperSupplier")
        uses("io.modelcontextprotocol.json.schema.JsonSchemaValidatorSupplier")
    }

    module(
        "io.modelcontextprotocol.sdk:mcp-json-jackson3",
        "io.modelcontextprotocol.sdk.mcp.json.jackson3"
    ) {
        overrideModuleName()
        exports("io.modelcontextprotocol.json.jackson3")
        exports("io.modelcontextprotocol.json.schema.jackson3")
        requires("com.networknt.schema")
        requiresTransitive("io.modelcontextprotocol.sdk.mcp.core")
        requires("org.slf4j")
        requires("tools.jackson.core")
        requiresTransitive("tools.jackson.databind")
    }
}

val jpmsSmoke = sourceSets.create("jpmsSmoke")

dependencies {
    add(jpmsSmoke.implementationConfigurationName, files(tasks.named("jar")))
    add(jpmsSmoke.compileOnlyConfigurationName, "jakarta.servlet:jakarta.servlet-api:6.1.0")
    add(
        jpmsSmoke.implementationConfigurationName,
        "io.modelcontextprotocol.sdk:mcp-json-jackson3:2.0.1"
    )
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(26)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    javaLauncher.set(testJavaLauncher)
    filter.isFailOnNoMatchingTests = false
}

tasks.named<JavaCompile>(jpmsSmoke.compileJavaTaskName) {
    options.release.set(26)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

val jpmsSmokeTest = tasks.register<JavaExec>("jpmsSmokeTest") {
    group = "verification"
    description = "Compiles and runs the typed tool API and MCP SDK providers as named JPMS modules."
    dependsOn(tasks.named(jpmsSmoke.classesTaskName))
    classpath = jpmsSmoke.runtimeClasspath
    mainModule.set("dev.mcdevmcp.mcp.tool.api.smoke")
    mainClass.set("dev.mcdevmcp.mcp.tool.api.smoke.JpmsSmokeMain")
    javaLauncher.set(testJavaLauncher)
}

tasks.named("check") {
    dependsOn(jpmsSmokeTest)
}
