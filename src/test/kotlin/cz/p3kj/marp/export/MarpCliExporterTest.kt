package cz.p3kj.marp.export

import com.google.gson.JsonParser
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.EnvironmentUtil
import com.intellij.util.ui.UIUtil
import cz.p3kj.marp.MarpBundle
import cz.p3kj.marp.MarpLightTestCase
import cz.p3kj.marp.editor.MarpSplitEditor
import cz.p3kj.marp.editor.MarpSplitEditorProvider
import cz.p3kj.marp.settings.MarpAppSettings
import cz.p3kj.marp.settings.MarpHtmlMode
import cz.p3kj.marp.settings.MarpMathMode
import cz.p3kj.marp.settings.MarpSettings
import cz.p3kj.marp.themes.MarpThemeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

/** Exports with a shell script that stands in for Marp CLI: it records how it was called and writes the output file. */
class MarpCliExporterTest : MarpLightTestCase() {

    private lateinit var work: Path
    private lateinit var deck: Path
    private lateinit var binDir: Path
    private val notifications: MutableList<Notification> = Collections.synchronizedList(mutableListOf())
    private val opened: MutableList<Path> = Collections.synchronizedList(mutableListOf())
    private val realBrowser = MarpCliExporter.browser

    override fun setUp() {
        super.setUp()
        work = Files.createTempDirectory("marp-cli-test")
        deck = work.resolve("deck.md")
        Files.writeString(deck, "---\nmarp: true\n---\n# One\n")
        binDir = work.resolve("bin")
        MarpCliExporter.trustedProvider = { true }
        MarpCliExporter.projectBasePath = { null }
        // A presentation is read here: the file is deleted with the test folder only when the test says so, it is in the temp folder of the JDK.
        MarpCliExporter.browser = { opened.add(it) }
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                notifications += notification
            }
        })
    }

    override fun tearDown() {
        try {
            MarpCliExporter.trustedProvider = { true }
            MarpCliExporter.projectBasePath = { it.basePath }
            MarpCliExporter.browser = realBrowser
            opened.forEach { Files.deleteIfExists(it) }
            MarpAppSettings.getInstance().update {
                marpCliPath = null
                presentWithCli = true
            }
            MarpSettings.getInstance(project).update {
                themes.clear()
                html = MarpHtmlMode.DEFAULT
                math = MarpMathMode.MATHJAX
            }
            FileUtil.delete(work.toFile())
        } finally {
            super.tearDown()
        }
    }

    /**
     * A "marp" that records its arguments, working directory and config next to itself, and writes the file after `-o`.
     * It is the configured CLI unless [configure] is false.
     */
    private fun fakeMarp(afterwards: String = "", script: Path = work.resolve("bin").resolve("marp"), configure: Boolean = true): Path {
        binDir = script.parent
        Files.createDirectories(script.parent)
        Files.writeString(
            script,
            """
            #!/bin/sh
            dir=${'$'}(dirname "${'$'}0")
            printf '%s\n' "${'$'}@" > "${'$'}dir/args"
            pwd -P > "${'$'}dir/cwd"
            cp "${'$'}2" "${'$'}dir/config.json"
            echo "${'$'}2" > "${'$'}dir/config-path"
            cp "${'$'}(dirname "${'$'}2")"/url-theme-*.css "${'$'}dir/" 2>/dev/null
            while [ ${'$'}# -gt 0 ]; do
              if [ "${'$'}1" = "-o" ]; then out="${'$'}2"; fi
              if [ "${'$'}1" = "--images" ]; then images=1; fi
              shift
            done
            if [ -n "${'$'}images" ]; then out="${'$'}{out%.*}.001.${'$'}{out##*.}"; fi
            echo data > "${'$'}out"
            $afterwards
            """.trimIndent() + "\n",
        )
        assertTrue(script.toFile().setExecutable(true))
        if (configure) MarpAppSettings.getInstance().update { marpCliPath = script.toString() }
        return script
    }

    private fun export(format: MarpCliFormat, target: Path) {
        runBlocking(Dispatchers.Default) { MarpCliExporter.run(project, deck, format, target) }
    }

    private fun recorded(name: String): String = Files.readString(binDir.resolve(name))

    private fun onePlainNotification(): Notification {
        assertEquals(notifications.toString(), 1, notifications.size)
        return notifications.single()
    }

    fun testPptxExportCallsTheCliAndOffersToOpenTheFile() {
        if (SystemInfo.isWindows) return
        fakeMarp()
        val target = work.resolve("out").resolve("deck.pptx").also { Files.createDirectories(it.parent) }
        export(MarpCliFormat.PPTX, target)

        val configPath = recorded("config-path").trim()
        assertEquals(
            listOf("--config-file", configPath, "--pptx", "-o", target.toString(), "--", deck.toString()),
            recorded("args").lines().filter { it.isNotEmpty() },
        )
        // Everything the CLI gets is an absolute path, so it runs in its own temporary folder, not in the project.
        val cwd = Path.of(recorded("cwd").trim())
        assertEquals(Path.of(configPath).parent.fileName, cwd.fileName)
        assertFalse("not the folder of the deck", work.toRealPath() == cwd)
        assertTrue(Files.isRegularFile(target))
        assertFalse("the temporary config folder is removed", Files.exists(Path.of(configPath).parent))

        val notification = onePlainNotification()
        assertEquals(NotificationType.INFORMATION, notification.type)
        assertEquals(MarpBundle.message("export.done", "deck.pptx"), notification.content)
        assertEquals(listOf(MarpBundle.message("export.open")), notification.actions.map { it.templateText })
    }

    fun testImageExportOffersToShowTheFolder() {
        if (SystemInfo.isWindows) return
        // Like the CLI, the fake one writes deck.001.png, not the name it was given.
        fakeMarp()
        export(MarpCliFormat.PNG, work.resolve("deck.png"))

        assertEquals(listOf("--images", "png"), recorded("args").lines().drop(2).take(2))
        val notification = onePlainNotification()
        assertEquals(NotificationType.INFORMATION, notification.type)
        assertEquals(MarpBundle.message("export.cli.done.images", "deck.001.png"), notification.content)
        assertEquals(listOf(MarpBundle.message("export.cli.showFolder")), notification.actions.map { it.templateText })
    }

    fun testImageExportSucceedsWithoutTheChosenFileButNeedsTheFirstImage() {
        if (SystemInfo.isWindows) return
        fakeMarp()
        export(MarpCliFormat.JPEG, work.resolve("deck.jpg"))
        assertFalse("the CLI does not write the chosen name", Files.exists(work.resolve("deck.jpg")))
        assertTrue(Files.isRegularFile(work.resolve("deck.001.jpg")))
        assertEquals(NotificationType.INFORMATION, onePlainNotification().type)
    }

    fun testImageExportWithoutImagesIsAFailure() {
        if (SystemInfo.isWindows) return
        // Exit code 0, but nothing was written: the CLI said something else instead.
        fakeMarp(afterwards = "rm -f \"\$out\"; echo 'nothing to convert'")
        export(MarpCliFormat.PNG, work.resolve("deck.png"))
        val notification = onePlainNotification()
        assertEquals(NotificationType.ERROR, notification.type)
        assertEquals(MarpBundle.message("export.cli.failed", "deck.png", "nothing to convert"), notification.content)
    }

    fun testSettingsAndThemesAreHandedOverThroughTheConfig() {
        if (SystemInfo.isWindows) return
        fakeMarp()
        val theme = work.resolve("my.css")
        Files.writeString(theme, "/* @theme mine */\nsection { color: red; }\n")
        MarpThemeService.getInstance(project).urlFetcher = { "/* @theme remote */\nsection { color: blue; }\n" }
        MarpSettings.getInstance(project).update {
            themes.add(theme.toString())
            themes.add("https://example.com/remote.css")
            html = MarpHtmlMode.ALL
            math = MarpMathMode.KATEX
        }
        export(MarpCliFormat.PPTX, work.resolve("deck.pptx"))

        val config = JsonParser.parseString(recorded("config.json")).asJsonObject
        val themeSet = config.getAsJsonArray("themeSet").map { it.asString }
        assertEquals(2, themeSet.size)
        assertEquals(theme.toAbsolutePath().toString(), themeSet[0])
        val urlTheme = Path.of(themeSet[1])
        assertEquals("url-theme-1.css", urlTheme.fileName.toString())
        assertTrue("the theme from the URL is a file the CLI can read", recorded("url-theme-1.css").contains("color: blue"))
        assertTrue(config.get("html").asBoolean)
        assertEquals("katex", config.getAsJsonObject("options").get("math").asString)
        assertTrue("the project is trusted in tests", config.get("allowLocalFiles").asBoolean)
        assertEquals(NotificationType.INFORMATION, onePlainNotification().type)
    }

    fun testAFailingCliShowsItsOutputEscaped() {
        if (SystemInfo.isWindows) return
        fakeMarp(afterwards = "echo '[ERROR] <b>No suitable browser</b> found & more' >&2; rm -f \"\$out\"; exit 1")
        export(MarpCliFormat.PPTX, work.resolve("deck.pptx"))

        val notification = onePlainNotification()
        assertEquals(NotificationType.ERROR, notification.type)
        assertTrue(notification.content, notification.content.startsWith("Marp CLI could not export deck.pptx:<br>"))
        assertTrue(notification.content, notification.content.contains("[ERROR] &lt;b&gt;No suitable browser&lt;/b&gt; found &amp; more"))
        assertEquals(1, notification.content.split("<br>").size - 1)
    }

    fun testAFailureWithoutOutputNamesTheExitCode() {
        if (SystemInfo.isWindows) return
        fakeMarp(afterwards = "rm -f \"\$out\"; exit 2")
        export(MarpCliFormat.PPTX, work.resolve("deck.pptx"))
        assertEquals(MarpBundle.message("export.cli.failed.noOutput", "deck.pptx", "2"), onePlainNotification().content)
    }

    fun testSuccessWithoutAPptxFileIsAFailure() {
        if (SystemInfo.isWindows) return
        fakeMarp(afterwards = "rm -f \"\$out\"")
        export(MarpCliFormat.PPTX, work.resolve("deck.pptx"))
        val notification = onePlainNotification()
        assertEquals(NotificationType.ERROR, notification.type)
        assertEquals(MarpBundle.message("export.cli.failed.noOutput", "deck.pptx", "0"), notification.content)
    }

    fun testAMissingConfiguredCliOffersTheSettings() {
        val gone = work.resolve("gone").resolve("marp")
        MarpAppSettings.getInstance().update { marpCliPath = gone.toString() }
        export(MarpCliFormat.PPTX, work.resolve("deck.pptx"))

        val notification = onePlainNotification()
        assertEquals(NotificationType.WARNING, notification.type)
        assertEquals(MarpBundle.message("export.cli.missingPath", gone.toString()), notification.content)
        assertEquals(listOf(MarpBundle.message("export.cli.openSettings")), notification.actions.map { it.templateText })
        assertFalse(Files.exists(work.resolve("deck.pptx")))
    }

    fun testACliThatCannotBeStartedIsReported() {
        if (SystemInfo.isWindows) return
        val script = work.resolve("marp")
        Files.writeString(script, "#!/bin/sh\n")
        assertTrue(script.toFile().setExecutable(false))
        MarpAppSettings.getInstance().update { marpCliPath = script.toString() }
        export(MarpCliFormat.PPTX, work.resolve("deck.pptx"))

        val notification = onePlainNotification()
        assertEquals(NotificationType.ERROR, notification.type)
        assertTrue(notification.content, notification.content.startsWith("Marp CLI ($script) could not be started"))
        assertEquals(listOf(MarpBundle.message("export.cli.openSettings")), notification.actions.map { it.templateText })
    }

    fun testCancellingKillsTheCliAndRemovesTheTemporaryFiles() {
        if (SystemInfo.isWindows) return
        fakeMarp(afterwards = "echo \$\$ > \"\$dir/pid\"; exec sleep 30")
        val pidFile = work.resolve("bin").resolve("pid")
        runBlocking(Dispatchers.Default) {
            val job = launch { MarpCliExporter.run(project, deck, MarpCliFormat.PPTX, work.resolve("deck.pptx")) }
            withTimeout(30_000) { while (!Files.exists(pidFile) || Files.readString(pidFile).isBlank()) delay(50) }
            job.cancelAndJoin()
        }
        val pid = Files.readString(pidFile).trim().toLong()
        val deadline = System.nanoTime() + 10_000_000_000L
        while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false) && System.nanoTime() < deadline) Thread.sleep(50)
        assertFalse("the process $pid is still running", ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        assertFalse("the temporary config folder is removed", Files.exists(Path.of(recorded("config-path").trim()).parent))
        assertTrue("a cancelled export says nothing: $notifications", notifications.isEmpty())
    }

    fun testAProjectInstallIsUsedWhenNoPathIsConfigured() {
        if (SystemInfo.isWindows) return
        MarpCliExporter.projectBasePath = { work.toString() }
        // The install is in the project root, the deck two folders below it.
        fakeMarp(script = work.resolve("node_modules").resolve(".bin").resolve("marp"), configure = false)
        val nested = work.resolve("talks").resolve("2026").resolve("deck.md")
        Files.createDirectories(nested.parent)
        Files.writeString(nested, "---\nmarp: true\n---\n# One\n")
        val target = work.resolve("deck.pptx")
        runBlocking(Dispatchers.Default) { MarpCliExporter.run(project, nested, MarpCliFormat.PPTX, target) }

        assertEquals(listOf("--config-file"), recorded("args").lines().take(1))
        assertTrue(recorded("args").contains(nested.toString()))
        assertTrue(Files.isRegularFile(target))
        assertEquals(NotificationType.INFORMATION, onePlainNotification().type)
    }

    fun testAConfiguredCliWinsOverTheProjectInstall() {
        if (SystemInfo.isWindows) return
        MarpCliExporter.projectBasePath = { work.toString() }
        val local = fakeMarp(script = work.resolve("node_modules").resolve(".bin").resolve("marp"), configure = false)
        fakeMarp()
        export(MarpCliFormat.PPTX, work.resolve("deck.pptx"))

        assertTrue("the configured CLI ran", Files.exists(work.resolve("bin").resolve("args")))
        assertFalse("the project install must not run", Files.exists(local.resolveSibling("args")))
        assertEquals(NotificationType.INFORMATION, onePlainNotification().type)
    }

    fun testAnUntrustedProjectStartsNoProcess() {
        if (SystemInfo.isWindows) return
        fakeMarp()
        MarpCliExporter.trustedProvider = { false }
        export(MarpCliFormat.PPTX, work.resolve("deck.pptx"))

        val notification = onePlainNotification()
        assertEquals(NotificationType.WARNING, notification.type)
        assertEquals(MarpBundle.message("export.cli.untrusted"), notification.content)
        assertFalse("the CLI must not run", Files.exists(work.resolve("bin").resolve("args")))
        assertFalse(Files.exists(work.resolve("deck.pptx")))
    }

    fun testAnUntrustedProjectIsNotAskedWhereToSave() {
        // The save dialog would block this test, so nothing may be shown before the trust check.
        MarpCliExporter.trustedProvider = { false }
        val file = myFixture.addFileToProject("deck.md", "---\nmarp: true\n---\n# One\n").virtualFile
        for (format in MarpCliFormat.entries) MarpCliExporter.export(project, file, format)
        assertEquals(MarpCliFormat.entries.size, notifications.size)
        assertTrue(notifications.all { it.content == MarpBundle.message("export.cli.untrusted") })
    }

    fun testPathsAndMessagesAreEscapedInNotifications() {
        if (SystemInfo.isWindows) return
        val gone = work.resolve("<b>x</b>").resolve("marp")
        MarpAppSettings.getInstance().update { marpCliPath = gone.toString() }
        export(MarpCliFormat.PPTX, work.resolve("deck.pptx"))
        val content = onePlainNotification().content
        assertTrue(content, content.contains("&lt;b&gt;x&lt;/b&gt;"))
        assertFalse(content, content.contains("<b>"))
    }

    fun testTheExecutableIsEscapedWhenItCannotBeStarted() {
        if (SystemInfo.isWindows) return
        val script = work.resolve("a&b").resolve("marp")
        Files.createDirectories(script.parent)
        Files.writeString(script, "#!/bin/sh\n")
        assertTrue(script.toFile().setExecutable(false))
        MarpAppSettings.getInstance().update { marpCliPath = script.toString() }
        export(MarpCliFormat.PPTX, work.resolve("deck.pptx"))
        val content = onePlainNotification().content
        assertTrue(content, content.startsWith("Marp CLI (${work.resolve("a&amp;b").resolve("marp")}) could not be started"))
    }

    // Present Deck through the CLI ------------------------------------------------------------------------------------

    /** What Marp CLI writes for the bespoke template, as far as the plugin cares. */
    private val bespokePage = "printf '<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\"></head><body></body></html>' > \"\$out\""

    private fun present(script: Path, start: Int = 0, deckPath: Path = deck) {
        runBlocking(Dispatchers.Default) { MarpCliExporter.present(project, deckPath, script, deckPath.fileName.toString(), start) }
    }

    /** The file after `-o` in the recorded arguments. */
    private fun recordedOutput(): Path {
        val args = recorded("args").lines().filter { it.isNotEmpty() }
        return Path.of(args[args.indexOf("-o") + 1])
    }

    fun testPresentationRunsTheCliOnTheDeckAndOpensItsPage() {
        if (SystemInfo.isWindows) return
        val script = fakeMarp(afterwards = bespokePage)
        present(script, start = 2)

        val configPath = recorded("config-path").trim()
        val target = recordedOutput()
        assertEquals(
            listOf("--config-file", configPath, "-o", target.toString(), "--", deck.toString()),
            recorded("args").lines().filter { it.isNotEmpty() },
        )
        assertTrue(target.toString(), target.fileName.toString().let { it.startsWith("marp-present-") && it.endsWith(".html") })
        assertEquals("the temporary folder of the JDK, not the one that is removed", Path.of(System.getProperty("java.io.tmpdir")).toRealPath(), target.parent.toRealPath())
        val cwd = Path.of(recorded("cwd").trim())
        assertEquals(Path.of(configPath).parent.fileName, cwd.fileName)
        assertFalse("the temporary config folder is removed", Files.exists(Path.of(configPath).parent))

        assertEquals(listOf(target), opened.toList())
        val page = Files.readString(target)
        // After the charset, which has to stay among the first bytes of the file.
        assertTrue(page, page.startsWith("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\"><base href=\"${MarpPresentFiles.baseHref(work)}\"><script>"))
        assertTrue(page, page.contains("+\"#3\")"))
        assertTrue(page, page.endsWith("</script></head><body></body></html>"))
        assertTrue("a successful presentation says nothing: $notifications", notifications.isEmpty())
    }

    fun testPresentationAsksForBespokeAndFollowsTheSettingsOfTheProject() {
        if (SystemInfo.isWindows) return
        val script = fakeMarp(afterwards = bespokePage)
        val theme = work.resolve("my.css")
        Files.writeString(theme, "/* @theme mine */\nsection { color: red; }\n")
        MarpSettings.getInstance(project).update {
            themes.add(theme.toString())
            html = MarpHtmlMode.ALL
            math = MarpMathMode.KATEX
        }
        present(script)

        val config = JsonParser.parseString(recorded("config.json")).asJsonObject
        assertEquals("bespoke", config.get("template").asString)
        assertTrue(config.getAsJsonObject("bespoke").get("progress").asBoolean)
        assertEquals(listOf(theme.toAbsolutePath().toString()), config.getAsJsonArray("themeSet").map { it.asString })
        assertTrue(config.get("html").asBoolean)
        assertEquals("katex", config.getAsJsonObject("options").get("math").asString)
        assertTrue(config.get("allowLocalFiles").asBoolean)
        assertEquals(1, opened.size)
    }

    fun testAnExportIsNotAPresentation() {
        if (SystemInfo.isWindows) return
        fakeMarp()
        export(MarpCliFormat.PPTX, work.resolve("deck.pptx"))
        val config = JsonParser.parseString(recorded("config.json")).asJsonObject
        assertFalse(config.has("template"))
        assertFalse(config.has("bespoke"))
        assertTrue(opened.isEmpty())
    }

    fun testAFailingCliIsReportedWithItsOutputAndOpensNothing() {
        if (SystemInfo.isWindows) return
        val script = fakeMarp(afterwards = "echo '[ERROR] <b>Theme</b> not found & more' >&2; exit 1")
        present(script)

        val notification = onePlainNotification()
        assertEquals(NotificationType.ERROR, notification.type)
        assertTrue(notification.content, notification.content.startsWith("Marp CLI could not present deck.md:<br>"))
        assertTrue(notification.content, notification.content.contains("[ERROR] &lt;b&gt;Theme&lt;/b&gt; not found &amp; more"))
        assertTrue(opened.isEmpty())
        assertFalse("the file of the failed run is removed", Files.exists(recordedOutput()))
    }

    fun testASuccessfulRunThatWroteNothingNamesTheExitCode() {
        if (SystemInfo.isWindows) return
        val script = fakeMarp(afterwards = ": > \"\$out\"")
        present(script)

        val notification = onePlainNotification()
        assertEquals(NotificationType.ERROR, notification.type)
        assertEquals(MarpBundle.message("present.cli.failed.noOutput", "deck.md", "0"), notification.content)
        assertTrue(opened.isEmpty())
        assertFalse(Files.exists(recordedOutput()))
    }

    fun testAPresentationFailureWithoutOutputNamesTheExitCode() {
        if (SystemInfo.isWindows) return
        val script = fakeMarp(afterwards = "rm -f \"\$out\"; exit 2")
        present(script)
        assertEquals(MarpBundle.message("present.cli.failed.noOutput", "deck.md", "2"), onePlainNotification().content)
        assertTrue(opened.isEmpty())
    }

    fun testACliThatCannotBeStartedOffersTheSettingsAndDoesNotFallBack() {
        if (SystemInfo.isWindows) return
        val script = work.resolve("marp")
        Files.writeString(script, "#!/bin/sh\n")
        assertTrue(script.toFile().setExecutable(false))
        present(script)

        val notification = onePlainNotification()
        assertEquals(NotificationType.ERROR, notification.type)
        assertTrue(notification.content, notification.content.startsWith("Marp CLI ($script) could not be started"))
        assertEquals(listOf(MarpBundle.message("export.cli.openSettings")), notification.actions.map { it.templateText })
        assertTrue(opened.isEmpty())
    }

    fun testAPageWithoutAHeadIsOpenedAsItIs() {
        if (SystemInfo.isWindows) return
        val script = fakeMarp(afterwards = "printf '<p>no head</p>' > \"\$out\"")
        present(script, start = 1)
        assertEquals("<p>no head</p>", Files.readString(opened.single()))
        assertTrue(notifications.isEmpty())
    }

    fun testCancellingAPresentationKillsTheCliAndLeavesNothingBehind() {
        if (SystemInfo.isWindows) return
        val script = fakeMarp(afterwards = "echo \$\$ > \"\$dir/pid\"; exec sleep 30")
        val pidFile = work.resolve("bin").resolve("pid")
        runBlocking(Dispatchers.Default) {
            val job = launch { MarpCliExporter.present(project, deck, script, "deck.md", 0) }
            withTimeout(30_000) { while (!Files.exists(pidFile) || Files.readString(pidFile).isBlank()) delay(50) }
            job.cancelAndJoin()
        }
        val pid = Files.readString(pidFile).trim().toLong()
        val deadline = System.nanoTime() + 10_000_000_000L
        while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false) && System.nanoTime() < deadline) Thread.sleep(50)
        assertFalse("the process $pid is still running", ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        assertFalse("the temporary config folder is removed", Files.exists(Path.of(recorded("config-path").trim()).parent))
        assertFalse("the file of the cancelled run is removed", Files.exists(recordedOutput()))
        assertTrue("a cancelled presentation says nothing: $notifications", notifications.isEmpty())
        assertTrue(opened.isEmpty())
    }

    // Which CLI Present Deck uses ------------------------------------------------------------------------------------

    private fun cliLocation(): MarpCliLocation? = runBlocking(Dispatchers.Default) { MarpPresenter.cliLocation(project, deck) }

    private fun cliExecutable(): Path? = (cliLocation() as? MarpCliLocation.Located)?.executable

    fun testTheConfiguredCliIsUsedToPresent() {
        if (SystemInfo.isWindows) return
        val script = fakeMarp()
        assertEquals(script, cliExecutable())
    }

    fun testAProjectInstallIsUsedToPresentWhenNoPathIsConfigured() {
        if (SystemInfo.isWindows) return
        MarpCliExporter.projectBasePath = { work.toString() }
        val local = fakeMarp(script = work.resolve("node_modules").resolve(".bin").resolve("marp"), configure = false)
        assertEquals(local, cliExecutable())
    }

    fun testTheSettingTurnsTheCliOff() {
        if (SystemInfo.isWindows) return
        fakeMarp()
        MarpAppSettings.getInstance().update { presentWithCli = false }
        assertNull(cliExecutable())
    }

    fun testAnUntrustedProjectPresentsWithoutTheCli() {
        if (SystemInfo.isWindows) return
        fakeMarp()
        MarpCliExporter.trustedProvider = { false }
        assertNull(cliExecutable())
        assertFalse("nothing was started", Files.exists(work.resolve("bin").resolve("args")))
        assertTrue("and nothing was said: $notifications", notifications.isEmpty())
    }

    fun testAMissingCliIsMissingAndTheLookupSaysNothing() {
        val gone = work.resolve("gone").resolve("marp")
        MarpAppSettings.getInstance().update { marpCliPath = gone.toString() }
        assertEquals("a path that is set and wrong is told apart from no CLI at all", MarpCliLocation.Missing(gone.toString()), cliLocation())
        assertTrue(notifications.isEmpty())
    }

    fun testTheSettingAndTheTrustDecideBeforeAnythingIsLookedFor() {
        MarpAppSettings.getInstance().update {
            marpCliPath = work.resolve("gone").resolve("marp").toString()
            presentWithCli = false
        }
        assertNull(cliLocation())
        MarpAppSettings.getInstance().update { presentWithCli = true }
        MarpCliExporter.trustedProvider = { false }
        assertNull(cliLocation())
    }

    // The action ------------------------------------------------------------------------------------------------------

    private val threeSlides = "---\nmarp: true\n---\n\n# One\n\n---\n\n# Two\n\n---\n\n# Three\n"

    /** The split editor of the deck on the local file system, `null` where the preview has no page (no JCEF). */
    private fun localSplitEditor(): MarpSplitEditor? {
        Files.writeString(deck, threeSlides)
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(deck)!!
        val editor = MarpSplitEditorProvider().createEditor(project, file) as MarpSplitEditor
        // The split editor builds its floating toolbar in a later EDT event, which must not run after the dispose.
        Disposer.register(testRootDisposable) {
            UIUtil.dispatchAllInvocationEvents()
            Disposer.dispose(editor)
        }
        return editor.takeIf { it.preview.panel != null }
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        PlatformTestUtil.waitWithEventsDispatching("Timed out waiting for $what", condition, 30)
    }

    fun testPresentDeckSavesTheEditorsAndPresentsFromTheSlideUnderTheCaret() {
        if (SystemInfo.isWindows) return
        fakeMarp(afterwards = bespokePage)
        val split = localSplitEditor() ?: return
        val editor = split.textEditor.editor
        val text = editor.document.text
        editor.caretModel.moveToOffset(text.indexOf("# Two"))
        // Typed and not saved: the CLI reads the deck from disk.
        WriteCommandAction.runWriteCommandAction(project) { editor.document.insertString(editor.document.textLength, "\nUnsaved words\n") }
        assertFalse(Files.readString(deck).contains("Unsaved words"))

        val promise = MarpPresenter.present(project, split)
        assertNotNull("nothing waits for the preview page when the CLI is used", promise)
        PlatformTestUtil.waitForPromise(promise!!)
        waitFor("the presentation") { opened.isNotEmpty() }

        assertTrue("saved before the CLI ran", Files.readString(deck).contains("Unsaved words"))
        assertTrue(recorded("args").contains(deck.toString()))
        val page = Files.readString(opened.single())
        assertTrue(page, page.contains("<base href=\"${MarpPresentFiles.baseHref(work)}\">"))
        assertTrue(page, page.contains("+\"#2\")"))
        assertTrue(notifications.isEmpty())
    }

    fun testPresentDeckUsesTheBuiltInPageWhenTheSettingIsOff() {
        if (SystemInfo.isWindows) return
        fakeMarp(afterwards = bespokePage)
        MarpAppSettings.getInstance().update { presentWithCli = false }
        val split = localSplitEditor() ?: return
        val edited = "\nUnsaved words\n"
        val editor = split.textEditor.editor
        WriteCommandAction.runWriteCommandAction(project) { editor.document.insertString(editor.document.textLength, edited) }

        // The preview page of a test is not loaded, which is what the built-in page reports.
        if (split.preview.panel!!.isPageReady) return
        assertNull(MarpPresenter.present(project, split))
        assertEquals(MarpBundle.message("export.notReady"), onePlainNotification().content)
        assertFalse("the CLI must not run", Files.exists(work.resolve("bin").resolve("args")))
        assertFalse("nothing is saved for the built-in page", Files.readString(deck).contains("Unsaved words"))
        assertTrue(opened.isEmpty())
    }

    fun testPresentDeckWarnsAboutAWrongCliPathAndDoesNotFallBack() {
        if (SystemInfo.isWindows) return
        val gone = work.resolve("gone").resolve("marp")
        MarpAppSettings.getInstance().update { marpCliPath = gone.toString() }
        val split = localSplitEditor() ?: return
        if (split.preview.panel!!.isPageReady) return

        val promise = MarpPresenter.present(project, split)
        assertNotNull(promise)
        PlatformTestUtil.waitForPromise(promise!!)
        waitFor("the warning") { notifications.isNotEmpty() }
        // The user asked for that CLI: the same warning as the export, with the settings, and no built-in page (which would say export.notReady).
        val notification = onePlainNotification()
        assertEquals(NotificationType.WARNING, notification.type)
        assertEquals(MarpBundle.message("export.cli.missingPath", gone.toString()), notification.content)
        assertEquals(listOf(MarpBundle.message("export.cli.openSettings")), notification.actions.map { it.templateText })
        assertTrue(opened.isEmpty())
    }

    fun testPresentDeckFallsBackToTheBuiltInPageWhenNoCliIsFoundAtAll() {
        if (SystemInfo.isWindows) return
        // Nothing is configured and the project has no install: only a marp on the PATH of this machine could be found.
        if (MarpCliLocator.findExecutable("marp", EnvironmentUtil.getValue("PATH"), null) != null) return
        val split = localSplitEditor() ?: return
        if (split.preview.panel!!.isPageReady) return

        val promise = MarpPresenter.present(project, split)
        assertNotNull(promise)
        PlatformTestUtil.waitForPromise(promise!!)
        waitFor("the fallback") { notifications.isNotEmpty() }
        // No mention of the CLI: it is the built-in page that is not ready.
        assertEquals(MarpBundle.message("export.notReady"), onePlainNotification().content)
        assertTrue(opened.isEmpty())
    }
}
