import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

// Build logic runs inside the Gradle daemon, on whichever JDK launched it. Left
// alone, Java targets that JDK while Kotlin falls back to the newest target it
// knows, so the two disagree on JDKs newer than the bundled Kotlin. Pin both to
// the oldest JVM Gradle 9 runs on.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation("org.gradlex.extra-java-module-info:org.gradlex.extra-java-module-info.gradle.plugin:1.14.2")
}
