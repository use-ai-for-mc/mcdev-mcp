plugins {
    `java-library`
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
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(26))
    }
}

dependencies {
    implementation(project(":"))
    implementation(project(":mcp-tool-api"))
    implementation("io.modelcontextprotocol.sdk:mcp-core:2.0.1")
    implementation("io.modelcontextprotocol.sdk:mcp-json-jackson3:2.0.1")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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

tasks.jar {
    manifest {
        attributes["Automatic-Module-Name"] = "dev.mcdevmcp.benchmark"
    }
}
