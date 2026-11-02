package dev.mcdevmcp.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject
import kotlin.io.path.invariantSeparatorsPathString

/**
 * Packs an application image and its manifest with the official MCPB packer, then unpacks the
 * bundle the way a client does and requires the image to come back complete and unchanged.
 */
abstract class McpbBundleTask @Inject constructor(
    private val execOperations: ExecOperations, private val fileOperations: FileSystemOperations
) : DefaultTask() {
    /** The directory jpackage wrote the image into. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val image: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    /** The command-line entry point of the installed packer. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val packer: RegularFileProperty

    @get:OutputFile
    abstract val bundle: RegularFileProperty

    /** The bundle as a client unpacks it. */
    @get:OutputDirectory
    abstract val unpacked: DirectoryProperty

    @TaskAction
    fun pack() {
        val stage = temporaryDir.toPath().resolve("stage")
        val archive = bundle.get().asFile.toPath()
        val extracted = unpacked.get().asFile.toPath()
        fileOperations.delete { delete(stage, archive, extracted) }
        fileOperations.copy {
            from(image)
            from(manifest) { rename { MANIFEST } }
            into(stage)
        }
        run("pack", stage, archive)
        run("unpack", archive, extracted)

        // The packer leaves out files it takes for development leftovers, and only a Unix packer
        // records which files are executable. Neither may cost the image a file it needs.
        val source = image.get().asFile.toPath()
        val packaged = files(source)
        val returned = files(extracted)
        val expected = packaged + MANIFEST
        check(expected == returned) {
            "The bundle does not hold the image and its manifest: missing ${expected - returned}, unexpected ${returned - expected}"
        }
        requireSameContents(MANIFEST, manifest.get().asFile.toPath(), extracted.resolve(MANIFEST))
        packaged.forEach { path ->
            val original = source.resolve(path)
            val copy = extracted.resolve(path)
            requireSameContents(path, original, copy)
            check(Files.isExecutable(original) == Files.isExecutable(copy)) { "The bundle changed whether $path is executable" }
        }
    }

    private fun requireSameContents(path: String, original: Path, copy: Path) {
        check(Files.mismatch(original, copy) == -1L) { "The bundle changed the contents of $path" }
    }

    private fun run(verb: String, from: Path, to: Path) {
        execOperations.exec {
            commandLine("node", packer.get().asFile.absolutePath, verb, from.toAbsolutePath().toString(), to.toAbsolutePath().toString())
        }
    }

    private fun files(root: Path): Set<String> = Files.walk(root).use { paths ->
        paths.filter { path -> Files.isRegularFile(path) }.map { path -> root.relativize(path).invariantSeparatorsPathString }.toList().toSortedSet()
    }

    private companion object {
        const val MANIFEST = "manifest.json"
    }
}
