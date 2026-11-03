package dev.mcdevmcp.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.nio.file.Path
import javax.inject.Inject

/**
 * Links the JDK modules the application needs into a runtime of its own.
 */
abstract class RuntimeImageTask @Inject constructor(
    private val execOperations: ExecOperations, private val fileOperations: FileSystemOperations
) : DefaultTask() {
    @get:Input
    abstract val jdkHome: Property<String>

    @get:Input
    abstract val modules: ListProperty<String>

    @get:OutputDirectory
    abstract val image: DirectoryProperty

    @TaskAction
    fun link() {
        val output = image.get().asFile.toPath()
        fileOperations.delete { delete(output) }
        execOperations.exec {
            commandLine(
                PackagingTools.executable(Path.of(jdkHome.get()), "jlink"),
                "--add-modules", modules.get().joinToString(","),
                "--strip-debug", "--no-header-files", "--no-man-pages",
                "--compress", "zip-6",
                "--output", output.toAbsolutePath().toString()
            )
        }
        // jlink can only generate the base class-data archives of a non-preview launch. The
        // application always runs with preview features enabled and needs its own.
        execOperations.exec {
            commandLine(PackagingTools.executable(output, "java"), "--enable-preview", "-Xshare:dump")
        }
    }
}
