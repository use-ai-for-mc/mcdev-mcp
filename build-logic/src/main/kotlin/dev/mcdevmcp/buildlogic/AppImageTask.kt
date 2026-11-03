package dev.mcdevmcp.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.inject.Inject

/**
 * Wraps the application JAR and its runtime in a jpackage application image: a directory with a
 * native launcher that needs no installed JDK and no command-line flags. The image is built aside
 * and replaces the old one only when finished, so a failed build leaves a working image in place.
 */
abstract class AppImageTask @Inject constructor(
    private val execOperations: ExecOperations, private val fileOperations: FileSystemOperations
) : DefaultTask() {
    @get:Input
    abstract val jdkHome: Property<String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val runtimeImage: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val mainJar: RegularFileProperty

    @get:Input
    abstract val applicationName: Property<String>

    @get:Input
    abstract val applicationVersion: Property<String>

    @get:Input
    abstract val mainClass: Property<String>

    @get:Input
    abstract val javaOptions: ListProperty<String>

    /** The directory that receives the image; the image itself is named after the application. */
    @get:OutputDirectory
    abstract val destination: DirectoryProperty

    @TaskAction
    fun pack() {
        val output = destination.get().asFile.toPath()
        val temporary = temporaryDir.toPath()
        val input = temporary.resolve("input")
        val staged = temporary.resolve("image")
        val previous = temporary.resolve("previous")
        fileOperations.delete { delete(input, staged, previous) }
        fileOperations.copy {
            from(mainJar)
            into(input)
        }
        val arguments = mutableListOf(
            PackagingTools.executable(Path.of(jdkHome.get()), "jpackage"),
            "--type", "app-image",
            "--name", applicationName.get(),
            "--app-version", applicationVersion.get(),
            "--input", input.toAbsolutePath().toString(),
            "--main-jar", mainJar.get().asFile.name,
            "--main-class", mainClass.get(),
            "--runtime-image", runtimeImage.get().asFile.absolutePath,
            "--dest", staged.toAbsolutePath().toString()
        )
        javaOptions.get().forEach { option -> arguments += listOf("--java-options", option) }
        if (PackagingTools.windows) {
            // Without a console launcher the server has no standard streams to speak MCP over.
            arguments += "--win-console"
        }
        execOperations.exec { commandLine(arguments) }
        replace(output, staged, previous)
    }

    /**
     * Swaps the finished image in by renaming. A delete of an image a running server holds files
     * in removes the files it can and leaves that server unable to start again; Windows refuses
     * the rename outright, so the image stays whole.
     */
    private fun replace(output: Path, staged: Path, previous: Path) {
        try {
            Files.move(output, previous, StandardCopyOption.ATOMIC_MOVE)
        } catch (failure: IOException) {
            throw GradleException("$output is in use and was left as it is. Stop what runs from it, such as a connected MCP server, and build again.", failure)
        }
        try {
            Files.move(staged, output, StandardCopyOption.ATOMIC_MOVE)
        } catch (failure: IOException) {
            Files.move(previous, output, StandardCopyOption.ATOMIC_MOVE)
            throw failure
        }
        fileOperations.delete { delete(previous) }
    }
}
