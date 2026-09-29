import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
    id("org.jetbrains.kotlinx.kover")
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        // Compile against IntelliJ IDEA to prove only platform APIs are used, so the plugin runs in every IntelliJ-based IDE.
        intellijIdea("2026.2.3")
        bundledPlugin("org.intellij.plugins.markdown")
        bundledPlugin("com.intellij.modules.jcef")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }

    // Signing and publishing read CERTIFICATE_CHAIN, PRIVATE_KEY, PRIVATE_KEY_PASSWORD and PUBLISH_TOKEN from the environment.
    publishing {
        // A pre-release suffix picks the Marketplace channel: 1.2.0-beta.1 -> "beta", 1.2.0 -> "default".
        channels = provider {
            listOf(version.toString().substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}

kover {
    reports {
        total {
            xml {
                onCheck = true
            }
        }
    }
}

// Webview bundle (marp-core + preview scripts), built with npm/esbuild and packed into the plugin jar under /webview.
val webviewDir = layout.projectDirectory.dir("webview")
val webviewOutDir = layout.buildDirectory.dir("generated/webview")
val npm = if (System.getProperty("os.name").lowercase().contains("windows")) "npm.cmd" else "npm"

val webviewInstall = tasks.register<Exec>("webviewInstall") {
    group = "webview"
    description = "Installs webview npm dependencies."
    workingDir(webviewDir)
    inputs.files(webviewDir.file("package.json"), webviewDir.file("package-lock.json"))
    outputs.file(webviewDir.file("node_modules/.package-lock.json"))
    commandLine(npm, "ci", "--no-audit", "--no-fund")
}

val buildWebview = tasks.register<Exec>("buildWebview") {
    group = "webview"
    description = "Bundles the Marp preview webview with esbuild."
    dependsOn(webviewInstall)
    workingDir(webviewDir)
    inputs.dir(webviewDir.dir("src"))
    inputs.files(
        webviewDir.file("build.mjs"),
        webviewDir.file("package.json"),
        webviewDir.file("package-lock.json"),
        webviewDir.file("tsconfig.json"),
    )
    outputs.dir(webviewOutDir)
    val outDir = webviewOutDir.map { it.dir("webview").asFile.absolutePath }
    argumentProviders += CommandLineArgumentProvider { listOf("--outdir=${outDir.get()}") }
    commandLine(npm, "run", "build", "--")
}

// Verification tasks over the webview sources. They have no real output, so each writes a stamp file: that makes
// them UP-TO-DATE (and skips npm) when nothing they read has changed.
fun registerWebviewCheck(name: String, taskDescription: String, vararg npmArgs: String) =
    tasks.register<Exec>(name) {
        group = "verification"
        description = taskDescription
        dependsOn(webviewInstall)
        workingDir(webviewDir)
        inputs.dir(webviewDir.dir("src"))
        inputs.dir(webviewDir.dir("test"))
        inputs.dir(webviewDir.dir("scripts"))
        inputs.files(
            webviewDir.file("build.mjs"),
            webviewDir.file("package.json"),
            webviewDir.file("package-lock.json"),
            webviewDir.file("tsconfig.json"),
        )
        inputs.files(fileTree(webviewDir) { include("vitest.config.*", "vite.config.*") })
        // NOTICE is compared with the real bundle by the notices check, the others do not read it
        inputs.file(layout.projectDirectory.file("NOTICE"))
        val stamp = layout.buildDirectory.file("reports/$name.stamp")
        outputs.file(stamp)
        commandLine(listOf(npm) + npmArgs)
        doLast { stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n") }
    }

val testWebview = registerWebviewCheck("testWebview", "Runs the webview unit tests (vitest).", "test")
val typecheckWebview = registerWebviewCheck("typecheckWebview", "Type-checks the webview sources (tsc).", "run", "typecheck")
val checkNotices = registerWebviewCheck(
    "checkNotices", "Fails when the NOTICE package list does not match the bundled packages.", "run", "notices:check",
)

sourceSets {
    main {
        resources.srcDir(buildWebview)
    }
}

tasks {
    check {
        dependsOn(testWebview, typecheckWebview, checkNotices)
    }

    runIde {
        jvmArgumentProviders += CommandLineArgumentProvider {
            listOf("-Dide.browser.jcef.debug.port=9222", "-Didea.trust.all.projects=true")
        }
        argumentProviders += CommandLineArgumentProvider {
            listOf(layout.projectDirectory.dir("samples").asFile.absolutePath)
        }
    }
}

intellijPlatformTesting {
    runIde {
        // Runs the plugin in a locally installed PhpStorm: ./gradlew runPhpStorm [-PphpStormPath=/path/to/phpstorm]
        register("runPhpStorm") {
            localPath = file(
                providers.gradleProperty("phpStormPath")
                    .orElse(System.getProperty("user.home") + "/.local/share/JetBrains/Toolbox/apps/phpstorm")
                    .get()
            )
            task {
                jvmArgumentProviders += CommandLineArgumentProvider {
                    listOf("-Dide.browser.jcef.debug.port=9223", "-Didea.trust.all.projects=true")
                }
                argumentProviders += CommandLineArgumentProvider {
                    listOf(layout.projectDirectory.dir("samples").asFile.absolutePath)
                }
            }
        }
    }
}
