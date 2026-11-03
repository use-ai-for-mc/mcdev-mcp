plugins {
    id("org.gradlex.extra-java-module-info")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val jacksonBom = "tools.jackson:jackson-bom:${catalog.findVersion("jackson3").get().requiredVersion}"
val jsonSchemaValidator =
    "com.networknt:json-schema-validator:${catalog.findVersion("jsonSchemaValidator").get().requiredVersion}"

// The SDK declares a Jackson release with open security advisories and the schema
// validator built against it. Every source set resolves the releases the catalog names instead.
pluginManager.withPlugin("java") {
    extensions.getByType<SourceSetContainer>().configureEach {
        dependencies.add(implementationConfigurationName, dependencies.platform(jacksonBom))
        dependencies.constraints.add(implementationConfigurationName, jsonSchemaValidator)
    }
}

extraJavaModuleInfo {
    failOnMissingModuleInfo.set(false)
    failOnAutomaticModules.set(false)

    // The SDK already publishes these module names. The generated descriptors
    // exist so the jars require the modules their bytecode uses.
    module("io.modelcontextprotocol.sdk:mcp-core", "io.modelcontextprotocol.sdk.mcp.core") {
        exportAllPackages()
        requiresTransitive("com.fasterxml.jackson.annotation")
        requiresStatic("jakarta.servlet")
        requiresTransitive("java.net.http")
        requiresTransitive("org.reactivestreams")
        requiresTransitive("org.slf4j")
        requiresTransitive("reactor.core")
        uses("io.modelcontextprotocol.json.McpJsonMapperSupplier")
        uses("io.modelcontextprotocol.json.schema.JsonSchemaValidatorSupplier")
    }

    module("io.modelcontextprotocol.sdk:mcp-json-jackson3", "io.modelcontextprotocol.sdk.mcp.json.jackson3") {
        exports("io.modelcontextprotocol.json.jackson3")
        exports("io.modelcontextprotocol.json.schema.jackson3")
        requires("com.networknt.schema")
        requiresTransitive("io.modelcontextprotocol.sdk.mcp.core")
        requires("org.slf4j")
        requires("tools.jackson.core")
        requiresTransitive("tools.jackson.databind")
    }
}
