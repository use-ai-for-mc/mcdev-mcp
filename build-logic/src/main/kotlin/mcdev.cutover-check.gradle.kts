import groovy.json.JsonSlurper
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.collections.ArrayDeque

plugins {
    base
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
    val allowedPackageMetadata = setOf(
        "packaging/mcpb/package.json", "packaging/mcpb/package-lock.json"
    )
    val forbiddenMetadataFiles = setOf(
        "tsconfig.json", "jest.config.js", "eslint.config.js"
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
        ).joinToString("|"), RegexOption.IGNORE_CASE
    )
    doLast {
        val trackedFiles = if (syntheticFiles.isEmpty()) {
            val git =
                ProcessBuilder("git", "ls-files", "-z").directory(repositoryRoot).redirectErrorStream(true).start()
            val trackedOutput = git.inputStream.readBytes()
            check(git.waitFor() == 0) {
                "Unable to list tracked files for cutoverCheck: ${trackedOutput.toString(StandardCharsets.UTF_8)}"
            }
            trackedOutput.toString(StandardCharsets.UTF_8).split('\u0000').filter(String::isNotEmpty)
        } else {
            syntheticFiles.keys.toList()
        }
        val violations = mutableListOf<String>()

        trackedFiles.forEach { path ->
            val normalizedPath = path.replace('\\', '/')
            val lowercasePath = normalizedPath.lowercase()
            val fileName = lowercasePath.substringAfterLast('/')
            if (lowercasePath.endsWith(".ts") || lowercasePath.endsWith(".tsx") || lowercasePath.endsWith(".js") || lowercasePath.endsWith(
                    ".mjs"
                ) || lowercasePath.endsWith(".cjs") || ((fileName == "package.json" || fileName == "package-lock.json") && normalizedPath !in allowedPackageMetadata) || fileName in forbiddenMetadataFiles || lowercasePath.startsWith(
                    "java-worker/"
                )
            ) {
                violations += "forbidden tracked file: $normalizedPath"
            }

            val isPackagingLockfile = normalizedPath == "packaging/mcpb/package-lock.json"
            val mustInspectContents =
                (lowercasePath.endsWith(".json") && normalizedPath != "contracts/node-oracle.json" && !isPackagingLockfile && !normalizedPath.startsWith(
                    "src/test/resources/contracts/"
                ) && !normalizedPath.startsWith("src/test/resources/oracle/")) || normalizedPath == "build.gradle.kts" || normalizedPath == "settings.gradle.kts" || normalizedPath.endsWith(
                    ".gradle"
                ) || normalizedPath.endsWith(".gradle.kts") || normalizedPath.endsWith(".toml") || normalizedPath.endsWith(
                    ".properties"
                ) || normalizedPath.startsWith(".github/") || normalizedPath.startsWith("build-logic/") || normalizedPath.startsWith("scripts/") || normalizedPath.startsWith(
                    "src/main/"
                )
            if (mustInspectContents) {
                val file = repositoryRoot.toPath().resolve(path)
                val syntheticContents = syntheticFiles[path]
                if (syntheticContents != null || Files.isRegularFile(file)) {
                    val contents = syntheticContents ?: Files.readString(file)
                    val forbiddenNodeMetadata = if (lowercasePath.endsWith(".json")) {
                        val parsedJson = runCatching { JsonSlurper().parseText(contents) }.getOrElse { cause ->
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
                        fun normalizedMetadataKey(key: String) = key.lowercase().replace("_", "").replace("-", "")

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
                                                nestedValue to (normalizedKey in environmentKeys && (nestedValue is String || nestedValue is Iterable<*>))
                                            )
                                        }
                                    }

                                    is Iterable<*> -> value.filterNotNull().forEach { nestedValue ->
                                        pendingEnvironmentValues.add(
                                            nestedValue to (assignmentList && (nestedValue is String || nestedValue is Iterable<*>))
                                        )
                                    }

                                    is String -> {
                                        val assignment = value.indexOf('=')
                                        if (assignmentList && assignment > 0 && normalizedMetadataKey(
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

                        val javascriptEntrypoint = Regex(
                            """(?i)\b[^\s"';|&()]*\.(?:js|mjs|cjs|ts|tsx)\b"""
                        )

                        fun normalizedExecutableName(value: String) =
                            value.trim().replace('\\', '/').substringAfterLast('/').lowercase().removeSuffix(".exe")

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
                                        if (character == '\r' && index + 1 < value.length && value[index + 1] == '\n') {
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
                                    character == '\\' && index + 1 < value.length && (value[index + 1].isWhitespace() || value[index + 1] in setOf(
                                        '\'',
                                        '"',
                                        ';',
                                        '&',
                                        '|'
                                    )) -> {
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
                                    hasNodeOptions = hasNodeOptions || normalizedMetadataKey(name) == "nodeoptions"
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
                                        } else if (executableIndex < words.size && words[executableIndex].startsWith("-")) {
                                            return null
                                        }
                                        consumeAssignments()
                                    }

                                    "exec", "command" -> {
                                        executableIndex++
                                        if (executableIndex < words.size && words[executableIndex] == "--") {
                                            executableIndex++
                                        } else if (executableIndex < words.size && words[executableIndex].startsWith("-")) {
                                            return null
                                        }
                                    }

                                    else -> break
                                }
                            }
                            if (executableIndex < words.size && words[executableIndex].contains('=')) {
                                return null
                            }
                            return executableIndex.takeIf { it < words.size }?.let { it to hasNodeOptions }
                        }

                        val nodeToken = Regex("""(?i)\bnode(?:\.exe)?\b""")
                        val nodeOptionsToken = Regex("""(?i)\bNODE_OPTIONS\b""")

                        fun hasForbiddenCommandWords(words: List<String>): Boolean {
                            val (executableIndex, hasNodeOptions) = resolveCommandSegment(words)
                                ?: return words.any { word ->
                                    nodeToken.containsMatchIn(word) || nodeOptionsToken.containsMatchIn(word) || javascriptEntrypoint.containsMatchIn(
                                        word
                                    )
                                }
                            return hasNodeOptions || words.any(nodeOptionsToken::containsMatchIn) || words.drop(executableIndex).any { word ->
                                nodeToken.containsMatchIn(word) || javascriptEntrypoint.containsMatchIn(word)
                            }
                        }

                        fun hasForbiddenNodeCommandText(value: String): Boolean {
                            val segments = parseCommandSegments(value)
                                ?: return nodeToken.containsMatchIn(value) || nodeOptionsToken.containsMatchIn(value) || javascriptEntrypoint.containsMatchIn(
                                    value
                                )
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
                                        val command = fields["command"] ?: listOf(
                                            "executable",
                                            "program"
                                        ).firstNotNullOfOrNull { fields[it] as? String }
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
                                                (commandParts.firstOrNull() as? String) to (stringArguments(
                                                    commandParts.drop(
                                                        1
                                                    )
                                                ) + stringArguments(explicitArguments))
                                            }

                                            is Map<*, *> -> {
                                                val commandFields = command.entries.associate { (key, fieldValue) ->
                                                    (key as? String)?.let(::normalizedMetadataKey) to fieldValue
                                                }
                                                val commandExecutable = listOf(
                                                    "command",
                                                    "executable",
                                                    "program"
                                                ).firstNotNullOfOrNull { commandFields[it] as? String }
                                                val commandArguments =
                                                    commandFields["args"] ?: commandFields["arguments"]
                                                commandExecutable to (stringArguments(commandArguments) + stringArguments(
                                                    explicitArguments
                                                ))
                                            }

                                            else -> null to emptyList()
                                        }
                                        if (executable != null && hasForbiddenCommandWords(listOf(executable) + arguments)) {
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
                            (normalizedKey == "runtime" || normalizedKey == "type") && value.equals(
                                "node",
                                ignoreCase = true
                            ) || normalizedKey == "command" && (value.equals("node", ignoreCase = true) || value.equals(
                                "node.exe",
                                ignoreCase = true
                            ))
                        }
                        val javascriptMetadata = jsonFields.any { (key, value) ->
                            val normalizedKey = normalizedMetadataKey(key)
                            val isEntrypoint = normalizedKey in entrypointKeys
                            val isCommand = normalizedKey == "command"
                            isEntrypoint && javascriptEntrypoint.containsMatchIn(value) || isCommand && hasForbiddenNodeCommandText(
                                value
                            )
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
                                    if (nestedValue is String && (parentKey in nestedMetadataKeys || normalizedKey == "command")) {
                                        nestedJavaScriptMetadata += parentKey in nestedEntrypointKeys && javascriptEntrypoint.containsMatchIn(
                                            nestedValue
                                        ) || (parentKey == "scripts" || normalizedKey == "command") && hasForbiddenNodeCommandText(
                                            nestedValue
                                        )
                                    }
                                    if (nestedValue != null) {
                                        pendingMetadataValues.add(nestedValue to (normalizedKey?.takeIf { it in nestedMetadataKeys }
                                            ?: parentKey))
                                    }
                                }

                                is Iterable<*> -> jsonValue.forEach { nestedValue ->
                                    if (nestedValue != null) {
                                        pendingMetadataValues.add(nestedValue to parentKey)
                                    }
                                }
                            }
                        }
                        nodeRuntime || javascriptMetadata || nestedJavaScriptMetadata.any { it } || parsedJson != null && (hasNodeOptionsMetadata(
                            parsedJson
                        ) || hasForbiddenStructuredNodeInvocation(parsedJson))
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
            val unreportedBypasses = syntheticFiles.keys.filterNot { it in allowedSyntheticFiles }
                .filter { path -> violations.none { path in it } }
            val rejectedAllowedFiles = allowedSyntheticFiles.filter { path -> violations.any { path in it } }
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
    ".cutover-fixtures/root-structured-command.json" to "{\"command\":\"no" + "de\",\"args\":[\"escape.cjs\"]}",
    ".cutover-fixtures/shell-wrapper.json" to "{\"command\":\"sh -c 'node escape.cjs'\"}",
    "packaging/mcpb/escape.json" to "{\"command\":\"no" + "de ../escape.cjs\"}",
    "packaging/mcpb/bare-node.json" to "{\"command\":\"node\"}",
    "packaging/mcpb/shell-wrapper.json" to "{\"command\":\"sh -c 'node bootstrap.cjs'\"}",
    "packaging/mcpb/structured-shell.json" to "{\"command\":\"sh\",\"args\":[\"-c\",\"node escape.cjs\"]}",
    "packaging/mcpb/nested-command.json" to "{\"server\":{\"command\":{\"executable\":\"node\",\"arguments\":[\"escape.cjs\"]}}}",
    "packaging/mcpb/nested-executable.json" to "{\"server\":{\"executable\":\"node\",\"arguments\":[\"escape.cjs\"]}}",
    "packaging/mcpb/nested-program.json" to "{\"server\":{\"program\":\"node\",\"args\":[\"escape.cjs\"]}}",
    "packaging/mcpb/node-options-map.json" to "{\"command\":\"node\",\"args\":[\"bootstrap.cjs\"],\"env\":{\"NODE_OPTIONS\":\"--require escape.cjs\"}}",
    "packaging/mcpb/node-options-list.json" to "{\"command\":\"node\",\"args\":[\"bootstrap.cjs\"],\"environment\":[\"NODE_OPTIONS=--require escape.cjs\"]}",
    "packaging/mcpb/node-options-inline.json" to "{\"command\":\"NODE_OPTIONS=--require=escape.cjs node bootstrap.cjs\"}",
    "packaging/mcpb/env-option-wrapper.json" to "{\"command\":\"env -S node bootstrap.cjs\"}",
    "packaging/mcpb/exec-option-wrapper.json" to "{\"command\":\"exec -a node bootstrap.cjs\"}",
    "packaging/mcpb/malformed-chain.json" to "{\"command\":\"node bootstrap.cjs &&\"}",
    "packaging/mcpb/unclosed-quote.json" to "{\"command\":\"sh -c 'node bootstrap.cjs\"}",
    "packaging/mcpb/pipeline.json" to "{\"command\":\"echo ok | node bootstrap.cjs\"}",
    "packaging/mcpb/semicolon.json" to "{\"command\":\"echo ok; node bootstrap.cjs\"}",
    "packaging/mcpb/direct-script.json" to "{\"command\":\"./bootstrap.cjs\"}",
    "packaging/mcpb/bootstrap.cjs" to "retired launcher",
    "packaging/mcpb/retired-launcher-structured.json" to "{\"command\":\"node\",\"args\":[\"bootstrap.cjs\"]}",
    "packaging/mcpb/retired-launcher-relative.json" to "{\"command\":[\"node\",\"./bootstrap.cjs\"]}",
    "packaging/mcpb/retired-launcher-executable.json" to "{\"server\":{\"executable\":\"node\",\"args\":[\"bootstrap.cjs\"]}}",
    "packaging/mcpb/retired-launcher-env-wrapper.json" to "{\"command\":\"env -- node bootstrap.cjs\"}",
    "packaging/mcpb/retired-launcher-exec-wrapper.json" to "{\"command\":\"exec -- node ./bootstrap.cjs\"}",
    "packaging/mcpb/retired-launcher-assignment-wrapper.json" to "{\"command\":\"MCPB=1 node bootstrap.cjs\"}"
).apply {
    retiredEnvironmentFixtures.forEachIndexed { index, value ->
        put("src/main/java/cutover/RetiredEnvironment$index.java", value)
    }
    retiredLibraryFixtures.forEachIndexed { index, value ->
        put("src/main/java/cutover/RetiredLibrary$index.java", value)
    }
    put("packaging/mcpb/package.json", "{\"devDependencies\":{\"@anthropic-ai/mcpb\":\"2.1.2\"}}")
    put("packaging/mcpb/package-lock.json", "{\"lockfileVersion\":3}")
    put(
        "packaging/mcpb/binary-server.json",
        $$"{\"server\":{\"type\":\"binary\",\"entry_point\":\"mcdev-mcp/mcdev-mcp.exe\",\"mcp_config\":{\"command\":\"${__dirname}/mcdev-mcp/mcdev-mcp.exe\",\"args\":[\"serve\"]}}}"
    )
    put("contracts/node-oracle.json", "{\"runtime\":\"no" + "de\"}")
    put("src/test/resources/contracts/cutover/frozen.json", "{\"runtime\":\"no" + "de\"}")
    put("src/test/resources/oracle/cutover/frozen.json", "{\"runtime\":\"no" + "de\"}")
    put(
        "src/main/resources/guides/package-json-transition.txt",
        "Legacy package JSON readers and writers are historical documentation, not production identifiers."
    )
}
val allowedCutoverFixtures = setOf(
    "packaging/mcpb/package.json",
    "packaging/mcpb/package-lock.json",
    "packaging/mcpb/binary-server.json",
    "contracts/node-oracle.json",
    "src/test/resources/contracts/cutover/frozen.json",
    "src/test/resources/oracle/cutover/frozen.json",
    "src/main/resources/guides/package-json-transition.txt"
)

val cutoverCheck = registerCutoverScan(
    "cutoverCheck", "Rejects tracked retired implementation surface."
)
val cutoverCheckBypassTest = registerCutoverScan(
    "cutoverCheckBypassTest",
    "Regression-tests cutoverCheck against forbidden and permitted synthetic tracked files.",
    cutoverBypassFixtures,
    allowedCutoverFixtures
)

tasks.named("check") {
    dependsOn(cutoverCheck)
    dependsOn(cutoverCheckBypassTest)
}
