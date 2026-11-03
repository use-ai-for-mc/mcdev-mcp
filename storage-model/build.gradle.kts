plugins {
    `java-library`
    id("mcdev.java-preview")
    id("mcdev.mcp-sdk-modules")
}

dependencies {
    api(project(":mcp-tool-api"))
    // Jackson 3 databind loads com.fasterxml.jackson.annotation.JsonSerializeAs from the 2.x
    // annotations line. 3.0-rc5 sorts above 2.21 and does not contain that class.
    api(libs.jacksonAnnotations)

    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitPlatformLauncher)
}

tasks.withType<Test>().configureEach {
    filter.isFailOnNoMatchingTests = false
}