package dev.mcdevmcp.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat

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
        // `sha256sum --check` takes a carriage return for part of the file name, so the line ends the same everywhere.
        val text = "${HexFormat.of().formatHex(digest.digest())}  ${input.fileName}\n"
        val output = checksumFile.get().asFile.toPath()
        Files.createDirectories(output.parent)
        Files.writeString(output, text, StandardCharsets.US_ASCII)
    }
}
