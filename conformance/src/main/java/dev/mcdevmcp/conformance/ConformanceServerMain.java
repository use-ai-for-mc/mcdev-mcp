package dev.mcdevmcp.conformance;

import dev.mcdevmcp.mcp.McpServerFactory;
import dev.mcdevmcp.mcp.ServerDefinition;
import dev.mcdevmcp.mcp.transport.McpSdkAdapter;
import dev.mcdevmcp.support.AppEnvironment;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.*;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ConformanceServerMain {

    private static final Logger logger = LoggerFactory.getLogger(ConformanceServerMain.class);

    private static final int PORT = 3000;

    private static final String MCP_ENDPOINT = "/mcp";

    private static final String SHUTDOWN_FILE_PROPERTY = "dev.mcdevmcp.conformance.shutdownFile";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private static final Duration EXECUTOR_STOP_TIMEOUT = Duration.ofSeconds(5);

    private static final Map<String, Object> EMPTY_JSON_SCHEMA = Map.of("type", "object", "properties", Collections.emptyMap());

    // Minimal 1x1 red pixel PNG (base64 encoded)
    private static final String RED_PIXEL_PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==";

    // Minimal WAV file (base64 encoded) - 1 sample at 8kHz
    private static final String MINIMAL_WAV = "UklGRiQAAABXQVZFZm10IBAAAAABAAEAQB8AAAB9AAACABAAZGF0YQAAAAA=";

    void main() throws Exception {
        logger.info("Starting MCP Conformance Tests - Servlet Server");
        Path shutdownFile = configuredShutdownFile();
        try (var runtime = ConformanceRuntime.open(shutdownFile)) {
            try {
                runtime.tomcat().start();
                logger.info("Conformance MCP Servlet Server started on port {} with endpoint {}", PORT, MCP_ENDPOINT);
                logger.info("Server URL: http://127.0.0.1:{}{}", PORT, MCP_ENDPOINT);
                awaitShutdown(runtime.tomcat(), shutdownFile);
            } catch (LifecycleException e) {
                logger.error("Failed to start Tomcat server", e);
                throw e;
            } finally {
                logger.info("Shutting down MCP server...");
            }
        }
    }

    private static Path configuredShutdownFile() {
        String configuredPath = System.getProperty(SHUTDOWN_FILE_PROPERTY);
        return configuredPath == null || configuredPath.isBlank() ? null : Path.of(configuredPath);
    }

    private static void awaitShutdown(Tomcat tomcat, Path shutdownFile) throws IOException, InterruptedException {
        if (shutdownFile == null) {
            tomcat.getServer().await();
            return;
        }

        Path absoluteShutdownFile = shutdownFile.toAbsolutePath();
        Path shutdownDirectory = absoluteShutdownFile.getParent();
        if (shutdownDirectory == null || !Files.isDirectory(shutdownDirectory)) {
            throw new IOException("Conformance shutdown directory does not exist: " + shutdownDirectory);
        }
        logger.info("Waiting for conformance shutdown signal at {}", absoluteShutdownFile);
        try (WatchService watchService = absoluteShutdownFile.getFileSystem().newWatchService()) {
            shutdownDirectory.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);
            while (!Files.exists(absoluteShutdownFile)) {
                WatchKey watchKey = watchService.take();
                watchKey.pollEvents();
                if (!watchKey.reset()) {
                    throw new IOException("Conformance shutdown directory is no longer watchable");
                }
            }
        }
        logger.info("Conformance shutdown signal received");
    }

    private static Path createTomcatBaseDirectory(Path shutdownFile) throws IOException {
        if (shutdownFile == null) {
            return Files.createTempDirectory("mcdev-mcp-conformance-tomcat-");
        }

        Path shutdownDirectory = shutdownFile.toAbsolutePath().normalize().getParent();
        if (shutdownDirectory == null) {
            throw new IOException("Conformance shutdown file has no parent directory");
        }
        return Files.createDirectories(shutdownDirectory.resolve("tomcat"));
    }

    private static void configureEmbeddedTomcat(Tomcat tomcat, ConformanceServlet servlet, Path baseDirectory) {
        tomcat.setPort(PORT);
        tomcat.setHostname("127.0.0.1");

        String baseDir = baseDirectory.toString();
        tomcat.setBaseDir(baseDir);

        Context context = tomcat.addContext("", baseDir);

        // Add the MCP servlet to Tomcat
        org.apache.catalina.Wrapper wrapper = context.createWrapper();
        wrapper.setName("mcpServlet");
        wrapper.setServlet(servlet);
        wrapper.setLoadOnStartup(1);
        wrapper.setAsyncSupported(true);
        context.addChild(wrapper);
        context.addServletMapping("/*", "mcpServlet");

        var connector = tomcat.getConnector();
        connector.setProperty("address", "127.0.0.1");
        connector.setAsyncTimeout(30000);
    }

    @SuppressWarnings("deprecation")
    private static List<McpServerFeatures.AsyncToolSpecification> createToolSpecs() {
        return List.of(McpServerFeatures.AsyncToolSpecification.builder().tool(Tool.builder("test_simple_text", EMPTY_JSON_SCHEMA).description("Returns simple text content for testing").build()).callHandler((_, _) -> {
            logger.info("Tool 'test_simple_text' called");
            return Mono.just(CallToolResult.builder().content(List.of(TextContent.builder("This is a simple text response for testing.").build())).isError(false).build());
        }).build(), McpServerFeatures.AsyncToolSpecification.builder().tool(Tool.builder("test_image_content", EMPTY_JSON_SCHEMA).description("Returns image content for testing").build()).callHandler((_, _) -> {
            logger.info("Tool 'test_image_content' called");
            return Mono.just(CallToolResult.builder().content(List.of(ImageContent.builder(RED_PIXEL_PNG, "image/png").build())).isError(false).build());
        }).build(), McpServerFeatures.AsyncToolSpecification.builder().tool(Tool.builder("test_audio_content", EMPTY_JSON_SCHEMA).description("Returns audio content for testing").build()).callHandler((_, _) -> {
            logger.info("Tool 'test_audio_content' called");
            return Mono.just(CallToolResult.builder().content(List.of(AudioContent.builder(MINIMAL_WAV, "audio/wav").build())).isError(false).build());
        }).build(), McpServerFeatures.AsyncToolSpecification.builder().tool(Tool.builder("test_embedded_resource", EMPTY_JSON_SCHEMA).description("Returns embedded resource content for testing").build()).callHandler((_, _) -> {
            logger.info("Tool 'test_embedded_resource' called");
            TextResourceContents contents = TextResourceContents.builder("test://embedded-resource", "This is an embedded resource content.").mimeType("text/plain").build();
            return Mono.just(CallToolResult.builder().content(List.of(EmbeddedResource.builder(contents).build())).isError(false).build());
        }).build(), McpServerFeatures.AsyncToolSpecification.builder().tool(Tool.builder("test_multiple_content_types", EMPTY_JSON_SCHEMA).description("Returns multiple content types for testing").build()).callHandler((_, _) -> {
            logger.info("Tool 'test_multiple_content_types' called");
            TextResourceContents contents = TextResourceContents.builder("test://mixed-content-resource", "{\"test\":\"data\",\"value\":123}").mimeType("application/json").build();
            return Mono.just(CallToolResult.builder().content(List.of(TextContent.builder("Multiple content types test:").build(), ImageContent.builder(RED_PIXEL_PNG, "image/png").build(), EmbeddedResource.builder(contents).build())).isError(false).build());
        }).build(), McpServerFeatures.AsyncToolSpecification.builder().tool(Tool.builder("test_tool_with_logging", EMPTY_JSON_SCHEMA).description("Tool that sends log messages during execution").build()).callHandler((exchange, _) -> {
            logger.info("Tool 'test_tool_with_logging' called");
            var result = CallToolResult.builder().content(List.of(TextContent.builder("Tool execution completed with logging").build())).isError(false).build();
            return exchange.loggingNotification(LoggingMessageNotification.builder(LoggingLevel.INFO, "Tool execution started").build()).then(exchange.loggingNotification(LoggingMessageNotification.builder(LoggingLevel.INFO, "Tool processing data").build())).then(exchange.loggingNotification(LoggingMessageNotification.builder(LoggingLevel.INFO, "Tool execution completed").build())).thenReturn(result);
        }).build(), McpServerFeatures.AsyncToolSpecification.builder().tool(Tool.builder("test_error_handling", EMPTY_JSON_SCHEMA).description("Tool that returns an error for testing error handling").build()).callHandler((_, _) -> {
            logger.info("Tool 'test_error_handling' called");
            return Mono.just(CallToolResult.builder().content(List.of(TextContent.builder("This tool intentionally returns an error for testing").build())).isError(true).build());
        }).build(), McpServerFeatures.AsyncToolSpecification.builder().tool(Tool.builder("test_tool_with_progress", EMPTY_JSON_SCHEMA).description("Tool that reports progress notifications").build()).callHandler((exchange, request) -> {
            logger.info("Tool 'test_tool_with_progress' called");
            Object progressToken = request.meta() == null ? null : request.meta().get("progressToken");
            if (progressToken == null) {
                return Mono.just(CallToolResult.builder().content(List.of(TextContent.builder("Tool execution completed without progress").build())).isError(false).build());
            }
            var result = CallToolResult.builder().content(List.of(TextContent.builder("Tool execution completed with progress").build())).isError(false).build();
            return exchange.progressNotification(ProgressNotification.builder(progressToken, 0.0).total(100.0).build()).then(exchange.progressNotification(ProgressNotification.builder(progressToken, 50.0).total(100.0).build())).then(exchange.progressNotification(ProgressNotification.builder(progressToken, 100.0).total(100.0).build())).thenReturn(result);
        }).build(), McpServerFeatures.AsyncToolSpecification.builder().tool(Tool.builder("test_sampling", Map.of("type", "object", "properties", Map.of("prompt", Map.of("type", "string", "description", "The prompt to send to the LLM")), "required", List.of("prompt"))).description("Tool that requests LLM sampling from client").build()).callHandler((exchange, request) -> {
            logger.info("Tool 'test_sampling' called");
            String prompt = (String) request.arguments().get("prompt");
            CreateMessageRequest samplingRequest = CreateMessageRequest.builder(List.of(SamplingMessage.builder(Role.USER, TextContent.builder(prompt).build()).build()), 100).build();
            return exchange.createMessage(samplingRequest).map(response -> {
                String responseText = "LLM response: " + ((TextContent) response.content()).text();
                return CallToolResult.builder().content(List.of(TextContent.builder(responseText).build())).isError(false).build();
            });
        }).build(), McpServerFeatures.AsyncToolSpecification.builder().tool(Tool.builder("test_elicitation", Map.of("type", "object", "properties", Map.of("message", Map.of("type", "string", "description", "The message to show the user")), "required", List.of("message"))).description("Tool that requests user input from client").build()).callHandler((exchange, request) -> {
            logger.info("Tool 'test_elicitation' called");
            String message = (String) request.arguments().get("message");
            Map<String, Object> requestedSchema = Map.of("type", "object", "properties", Map.of("username", Map.of("type", "string", "description", "User's response"), "email", Map.of("type", "string", "description", "User's email address")), "required", List.of("username", "email"));
            ElicitRequest elicitRequest = ElicitRequest.builder(message, requestedSchema).build();
            return exchange.createElicitation(elicitRequest).map(response -> {
                String responseText = "User response: action=" + response.action() + ", content=" + response.content();
                return CallToolResult.builder().content(List.of(TextContent.builder(responseText).build())).isError(false).build();
            });
        }).build(), McpServerFeatures.AsyncToolSpecification.builder().tool(Tool.builder("test_elicitation_sep1034_defaults", EMPTY_JSON_SCHEMA).description("Tool that requests elicitation with default values for all primitive types").build()).callHandler((exchange, _) -> {
            logger.info("Tool 'test_elicitation_sep1034_defaults' called");
            Map<String, Object> requestedSchema = Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string", "default", "John Doe"), "age", Map.of("type", "integer", "default", 30), "score", Map.of("type", "number", "default", 95.5), "status", Map.of("type", "string", "enum", List.of("active", "inactive", "pending"), "default", "active"), "verified", Map.of("type", "boolean", "default", true)), "required", List.of("name", "age", "score", "status", "verified"));
            ElicitRequest elicitRequest = ElicitRequest.builder("Please provide your information with defaults", requestedSchema).build();
            return exchange.createElicitation(elicitRequest).map(response -> {
                String responseText = "Elicitation completed: action=" + response.action() + ", content=" + response.content();
                return CallToolResult.builder().content(List.of(TextContent.builder(responseText).build())).isError(false).build();
            });
        }).build(), McpServerFeatures.AsyncToolSpecification.builder().tool(Tool.builder("json_schema_2020_12_tool", Map.of("$schema", McpSchema.JSON_SCHEMA_DIALECT_2020_12, "type", "object", "$defs", Map.of("address", Map.of("type", "object", "properties", Map.of("street", Map.of("type", "string"), "city", Map.of("type", "string")))), "properties", Map.of("name", Map.of("type", "string"), "address", Map.of("$ref", "#/$defs/address")), "additionalProperties", false)).description("Tool with JSON Schema 2020-12 features (SEP-1613)").build()).callHandler((_, _) -> {
            logger.info("Tool 'json_schema_2020_12_tool' called");
            return Mono.just(CallToolResult.builder().content(List.of(TextContent.builder("ok").build())).isError(false).build());
        }).build(), McpServerFeatures.AsyncToolSpecification.builder().tool(Tool.builder("test_elicitation_sep1330_enums", EMPTY_JSON_SCHEMA).description("Tool that requests elicitation with enum schema improvements").build()).callHandler((exchange, _) -> {
            logger.info("Tool 'test_elicitation_sep1330_enums' called");
            TypeRef<Map<String, Object>> mapType = new TypeRef<>() {
            };
            var mapper = McpJsonDefaults.getMapper();
            var untitledSingle = UntitledSingleSelectEnumSchema.builder().enumValues("option1", "option2", "option3").build();
            var titledSingle = TitledSingleSelectEnumSchema.builder().oneOf(new EnumSchemaOption("value1", "First Option"), new EnumSchemaOption("value2", "Second Option"), new EnumSchemaOption("value3", "Third Option")).build();
            var legacyEnum = LegacyTitledEnumSchema.builder().enumValues("opt1", "opt2", "opt3").enumNames("Option One", "Option Two", "Option Three").build();
            var untitledMulti = UntitledMultiSelectEnumSchema.builder(UntitledMultiSelectItems.builder().enumValues("option1", "option2", "option3").build()).build();
            var titledMulti = TitledMultiSelectEnumSchema.builder(TitledMultiSelectItems.builder().anyOf(new EnumSchemaOption("value1", "First Choice"), new EnumSchemaOption("value2", "Second Choice"), new EnumSchemaOption("value3", "Third Choice")).build()).build();
            Map<String, Object> requestedSchema = Map.of("type", "object", "properties", Map.of("untitledSingle", mapper.convertValue(untitledSingle, mapType), "titledSingle", mapper.convertValue(titledSingle, mapType), "legacyEnum", mapper.convertValue(legacyEnum, mapType), "untitledMulti", mapper.convertValue(untitledMulti, mapType), "titledMulti", mapper.convertValue(titledMulti, mapType)), "required", List.of("untitledSingle", "titledSingle", "legacyEnum", "untitledMulti", "titledMulti"));
            ElicitRequest elicitRequest = ElicitRequest.builder("Select your preferences", requestedSchema).build();
            return exchange.createElicitation(elicitRequest).map(response -> {
                String responseText = "Elicitation completed: action=" + response.action() + ", content=" + response.content();
                return CallToolResult.builder().content(List.of(TextContent.builder(responseText).build())).isError(false).build();
            });
        }).build());
    }

    private static List<McpServerFeatures.AsyncPromptSpecification> createPromptSpecs() {
        return List.of(new McpServerFeatures.AsyncPromptSpecification(Prompt.builder("test_simple_prompt").description("A simple prompt for testing").arguments(List.of()).build(), (_, _) -> {
            logger.info("Prompt 'test_simple_prompt' requested");
            return Mono.just(GetPromptResult.builder(List.of(PromptMessage.builder(Role.USER, TextContent.builder("This is a simple prompt for testing.").build()).build())).build());
        }), new McpServerFeatures.AsyncPromptSpecification(Prompt.builder("test_prompt_with_arguments").description("A prompt with arguments for testing").arguments(List.of(PromptArgument.builder("arg1").description("First test argument").required(true).build(), PromptArgument.builder("arg2").description("Second test argument").required(true).build())).build(), (_, request) -> {
            logger.info("Prompt 'test_prompt_with_arguments' requested");
            String arg1 = (String) request.arguments().get("arg1");
            String arg2 = (String) request.arguments().get("arg2");
            String text = String.format("Prompt with arguments: arg1='%s', arg2='%s'", arg1, arg2);
            return Mono.just(GetPromptResult.builder(List.of(PromptMessage.builder(Role.USER, TextContent.builder(text).build()).build())).build());
        }), new McpServerFeatures.AsyncPromptSpecification(Prompt.builder("test_prompt_with_embedded_resource").description("A prompt with embedded resource for testing").arguments(List.of(PromptArgument.builder("resourceUri").description("URI of the resource to embed").required(true).build())).build(), (_, request) -> {
            logger.info("Prompt 'test_prompt_with_embedded_resource' requested");
            String resourceUri = (String) request.arguments().get("resourceUri");
            TextResourceContents contents = TextResourceContents.builder(resourceUri, "Embedded resource content for testing.").mimeType("text/plain").build();
            return Mono.just(GetPromptResult.builder(List.of(PromptMessage.builder(Role.USER, EmbeddedResource.builder(contents).build()).build(), PromptMessage.builder(Role.USER, TextContent.builder("Please process the embedded resource above.").build()).build())).build());
        }), new McpServerFeatures.AsyncPromptSpecification(Prompt.builder("test_prompt_with_image").description("A prompt with image content for testing").arguments(List.of()).build(), (_, _) -> {
            logger.info("Prompt 'test_prompt_with_image' requested");
            return Mono.just(GetPromptResult.builder(List.of(PromptMessage.builder(Role.USER, ImageContent.builder(RED_PIXEL_PNG, "image/png").build()).build(), PromptMessage.builder(Role.USER, TextContent.builder("Please analyze the image above.").build()).build())).build());
        }));
    }

    private static List<McpServerFeatures.AsyncResourceSpecification> createResourceSpecs() {
        return List.of(new McpServerFeatures.AsyncResourceSpecification(Resource.builder("test://static-text", "Static Text Resource").description("A static text resource for testing").mimeType("text/plain").build(), (_, _) -> {
            logger.info("Resource 'test://static-text' requested");
            return Mono.just(ReadResourceResult.builder(List.of(TextResourceContents.builder("test://static-text", "This is the content of the static text resource.").mimeType("text/plain").build())).build());
        }), new McpServerFeatures.AsyncResourceSpecification(Resource.builder("test://static-binary", "Static Binary Resource").description("A static binary resource for testing").mimeType("image/png").build(), (_, _) -> {
            logger.info("Resource 'test://static-binary' requested");
            return Mono.just(ReadResourceResult.builder(List.of(BlobResourceContents.builder("test://static-binary", RED_PIXEL_PNG).mimeType("image/png").build())).build());
        }), new McpServerFeatures.AsyncResourceSpecification(Resource.builder("test://watched-resource", "Watched Resource").description("A resource that can be subscribed to for updates").mimeType("text/plain").build(), (_, _) -> {
            logger.info("Resource 'test://watched-resource' requested");
            return Mono.just(ReadResourceResult.builder(List.of(TextResourceContents.builder("test://watched-resource", "This is a watched resource content.").mimeType("text/plain").build())).build());
        }));
    }

    private static List<McpServerFeatures.AsyncResourceTemplateSpecification> createResourceTemplateSpecs() {
        return List.of(new McpServerFeatures.AsyncResourceTemplateSpecification(ResourceTemplate.builder("test://template/{id}/data", "Template Resource").description("A resource template for testing parameter substitution").mimeType("application/json").build(), (_, request) -> {
            logger.info("Resource template 'test://template/{{id}}/data' requested for URI: {}", request.uri());
            String uri = request.uri();
            String prefix = "test://template/";
            String suffix = "/data";
            String id = uri.substring(prefix.length(), uri.length() - suffix.length());
            String jsonContent = String.format("{\"id\":\"%s\",\"templateTest\":true,\"data\":\"Data for ID: %s\"}", id, id);
            return Mono.just(ReadResourceResult.builder(List.of(TextResourceContents.builder(uri, jsonContent).mimeType("application/json").build())).build());
        }));
    }

    private static List<McpServerFeatures.AsyncCompletionSpecification> createCompletionSpecs() {
        return List.of(new McpServerFeatures.AsyncCompletionSpecification(new PromptReference("test_prompt_with_arguments"), (_, request) -> {
            logger.info("Completion requested for prompt 'test_prompt_with_arguments', argument: {}", request.argument().name());
            return Mono.just(new CompleteResult(new CompleteResult.CompleteCompletion(List.of(), 0, false)));
        }));
    }

    private static McpSdkAdapter.AsyncServerExtensions createExtensions() {
        var tools = createToolSpecs();
        var resources = createResourceSpecs();
        if (tools.stream().noneMatch(specification -> specification.tool().name().equals("test_simple_text"))) {
            throw new IllegalStateException("Missing conformance tool: test_simple_text");
        }
        if (resources.stream().noneMatch(specification -> specification.resource().uri().equals("test://static-text"))) {
            throw new IllegalStateException("Missing conformance resource: test://static-text");
        }
        return new McpSdkAdapter.AsyncServerExtensions(tools, resources, createResourceTemplateSpecs(), createPromptSpecs(), createCompletionSpecs(), McpSchema.ServerCapabilities.builder().completions().logging().resources(true, false).tools(false).prompts(false).build(), REQUEST_TIMEOUT);
    }

    private static void requireProductionDefinition(ServerDefinition definition) {
        if (definition.tools().enabledDefinitions().stream().noneMatch(tool -> tool.name().equals("mc_version"))) {
            throw new IllegalStateException("Missing production tool: mc_version");
        }
        for (String uri : List.of("mcdev://guides/python-scripting", "mcdev://guides/dev-loop")) {
            if (definition.resources().definitions().stream().noneMatch(resource -> resource.uri().toString().equals(uri))) {
                throw new IllegalStateException("Missing production resource: " + uri);
            }
        }
    }

    private static Throwable closeResource(Throwable failure, AutoCloseable closeable) {
        if (closeable == null) {
            return failure;
        }
        try {
            closeable.close();
        } catch (Throwable closeFailure) {
            if (failure == null) {
                return closeFailure;
            }
            failure.addSuppressed(closeFailure);
        }
        return failure;
    }

    private static void closeResourceAfterFailure(Throwable failure, AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static Throwable closeTomcat(Throwable failure, Tomcat tomcat) {
        if (tomcat == null) {
            return failure;
        }
        try {
            tomcat.stop();
        } catch (Throwable closeFailure) {
            failure = addFailure(failure, closeFailure);
        }
        try {
            tomcat.destroy();
        } catch (Throwable closeFailure) {
            failure = addFailure(failure, closeFailure);
        }
        return failure;
    }

    private static Throwable closeReactorSchedulers(Throwable failure) {
        try {
            Schedulers.shutdownNow();
        } catch (Throwable closeFailure) {
            return addFailure(failure, closeFailure);
        }
        return failure;
    }

    private static void closeTomcatAfterFailure(Throwable failure, Tomcat tomcat) {
        if (tomcat == null) {
            return;
        }
        try {
            tomcat.stop();
        } catch (Throwable closeFailure) {
            failure.addSuppressed(closeFailure);
        }
        try {
            tomcat.destroy();
        } catch (Throwable closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static Throwable addFailure(Throwable failure, Throwable closeFailure) {
        if (failure == null) {
            return closeFailure;
        }
        failure.addSuppressed(closeFailure);
        return failure;
    }

    private static Throwable deleteDirectory(Throwable failure, Path directory) {
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                @SuppressWarnings("NullableProblems")
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                @SuppressWarnings("NullableProblems")
                public FileVisitResult postVisitDirectory(Path visitedDirectory, IOException exception) throws IOException {
                    if (exception != null) {
                        throw exception;
                    }
                    Files.deleteIfExists(visitedDirectory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Throwable closeFailure) {
            return addFailure(failure, closeFailure);
        }
        return failure;
    }

    private static Throwable closeExecutor(Throwable failure, ExecutorService executor) {
        executor.shutdown();
        boolean interrupted = false;
        try {
            if (!executor.awaitTermination(EXECUTOR_STOP_TIMEOUT.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(EXECUTOR_STOP_TIMEOUT.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS)) {
                    failure = addFailure(failure, new IllegalStateException("Conformance executor did not stop"));
                }
            }
        } catch (InterruptedException exception) {
            interrupted = true;
            executor.shutdownNow();
            failure = addFailure(failure, new IllegalStateException("Interrupted while stopping conformance executor", exception));
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return failure;
    }

    private static void closeExecutorAfterFailure(Throwable failure, ExecutorService executor) {
        Throwable closeFailure = closeExecutor(null, executor);
        if (closeFailure != null) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static void throwFailure(Throwable failure) {
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new IllegalStateException("Unexpected conformance shutdown failure", failure);
        }
    }

    private record ConformanceRuntime(ExecutorService executor, McpServerFactory factory, McpSdkAdapter.StreamableServer server, Tomcat tomcat, Path tomcatBaseDirectory) implements AutoCloseable {
        private static ConformanceRuntime open(Path shutdownFile) throws IOException {
            Path tomcatBaseDirectory = createTomcatBaseDirectory(shutdownFile);
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            McpServerFactory factory = null;
            McpSdkAdapter.StreamableServer server = null;
            Tomcat tomcat = null;
            try {
                factory = new McpServerFactory(AppEnvironment.system());
                ServerDefinition definition = factory.loadServerDefinition(executor);
                requireProductionDefinition(definition);
                var mapper = McpJsonDefaults.getMapper();
                var extensions = createExtensions();
                HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider.builder().jsonMapper(mapper).mcpEndpoint(MCP_ENDPOINT).keepAliveInterval(REQUEST_TIMEOUT).securityValidator(DefaultServerTransportSecurityValidator.builder().allowedOrigin("http://127.0.0.1:*").allowedHost("127.0.0.1:*").build()).build();
                server = McpSdkAdapter.startStreamable(mapper, transport, definition, executor, extensions);
                var servlet = new ConformanceServlet(transport);
                tomcat = new Tomcat();
                configureEmbeddedTomcat(tomcat, servlet, tomcatBaseDirectory);
                return new ConformanceRuntime(executor, factory, server, tomcat, tomcatBaseDirectory);
            } catch (RuntimeException | Error failure) {
                closeTomcatAfterFailure(failure, tomcat);
                closeResourceAfterFailure(failure, server);
                closeExecutorAfterFailure(failure, executor);
                closeResourceAfterFailure(failure, factory);
                Throwable directoryFailure = deleteDirectory(null, tomcatBaseDirectory);
                if (directoryFailure != null) {
                    failure.addSuppressed(directoryFailure);
                }
                throw failure;
            }
        }

        @Override
        public void close() {
            Throwable failure = closeResource(null, server);
            failure = closeReactorSchedulers(failure);
            failure = closeTomcat(failure, tomcat);
            failure = closeExecutor(failure, executor);
            failure = closeResource(failure, factory);
            failure = deleteDirectory(failure, tomcatBaseDirectory);
            throwFailure(failure);
        }
    }

}
