@file:Suppress("UnstableApiUsage")

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("com.gradleup.shadow") version "9.6.1"
    }
}

plugins {
    id("io.github.ben-manes.versions.settings") version "0.67.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/")
    }
}

rootProject.name = "mcdev-mcp"

include("mcp-tool-api")
include("storage-model")