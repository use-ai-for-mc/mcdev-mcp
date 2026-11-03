package dev.mcdevmcp.buildlogic

import java.nio.file.Path
import java.util.Locale

/**
 * Platform facts shared by the tasks that drive the JDK's packaging tools.
 */
object PackagingTools {
    private val osName = System.getProperty("os.name").lowercase(Locale.ROOT)

    val windows: Boolean = osName.contains("win")

    /** The operating systems a bundle is built for, by the names their manifests are generated under. */
    val operatingSystems: List<String> = listOf("windows", "linux", "macos")

    val operatingSystem: String = when {
        windows -> "windows"
        osName.contains("mac") || osName.contains("darwin") -> "macos"
        else -> "linux"
    }

    /** The platform part of a bundle's file name, such as `windows-x64`. */
    val platform: String = run {
        val arch = when (val reported = System.getProperty("os.arch").lowercase(Locale.ROOT)) {
            "amd64", "x86_64" -> "x64"
            "arm64" -> "aarch64"
            else -> reported
        }
        "$operatingSystem-$arch"
    }

    fun executable(home: Path, name: String): String = home.resolve("bin").resolve(if (windows) "$name.exe" else name).toAbsolutePath().toString()

    /** npm is a batch file on Windows, which a process can only start by its full name. */
    val npm: String = if (windows) "npm.cmd" else "npm"
}
