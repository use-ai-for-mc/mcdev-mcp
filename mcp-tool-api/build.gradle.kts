plugins {
    `java-library`
    id("mcdev.java-preview")
    id("mcdev.mcp-sdk-modules")
}

dependencies {
    api(libs.mcpSdkCore)
    compileOnly(libs.servletApi)

    testImplementation(libs.mcpSdkJsonJackson3)
    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitPlatformLauncher)
}

val jpmsSmoke = sourceSets.create("jpmsSmoke")

dependencies {
    add(jpmsSmoke.implementationConfigurationName, files(tasks.named("jar")))
    add(jpmsSmoke.compileOnlyConfigurationName, libs.servletApi)
    add(jpmsSmoke.implementationConfigurationName, libs.mcpSdkJsonJackson3)
}

tasks.withType<Test>().configureEach {
    filter.isFailOnNoMatchingTests = false
}

val jpmsSmokeTest = tasks.register<JavaExec>("jpmsSmokeTest") {
    group = "verification"
    description = "Compiles and runs the typed tool API and MCP SDK providers as named JPMS modules."
    dependsOn(tasks.named(jpmsSmoke.classesTaskName))
    classpath = jpmsSmoke.runtimeClasspath
    mainModule.set("dev.mcdevmcp.mcp.tool.api.smoke")
    mainClass.set("dev.mcdevmcp.mcp.tool.api.smoke.JpmsSmokeMain")
}

tasks.named("check") {
    dependsOn(jpmsSmokeTest)
}