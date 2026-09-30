import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "marp-intellij"

pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.4.20"
        id("org.jetbrains.changelog") version "2.5.0"
        id("org.jetbrains.kotlinx.kover") version "0.9.11"
    }
}

// Patched versions of libraries that the IntelliJ Platform Gradle Plugin brings along (Dependabot alerts). They are
// build tooling only and never part of the plugin. Drop a line once the plugin itself ships the fixed version.
buildscript {
    dependencies {
        constraints {
            classpath("com.fasterxml.jackson.core:jackson-databind:2.21.7")
            classpath("org.jsoup:jsoup:1.23.1")
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.19.0"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenCentral()

        // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
        intellijPlatform {
            defaultRepositories()
        }
    }
}
