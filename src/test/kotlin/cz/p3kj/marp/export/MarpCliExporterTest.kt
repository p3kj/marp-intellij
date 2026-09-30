package cz.p3kj.marp.export

import com.google.gson.JsonParser
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import cz.p3kj.marp.MarpBundle
import cz.p3kj.marp.MarpLightTestCase
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
    private val notifications: MutableList<Notification> = Collections.synchronizedList(mutableListOf())

    override fun setUp() {
        super.setUp()
        work = Files.createTempDirectory("marp-cli-test")
        deck = work.resolve("deck.md")
        Files.writeString(deck, "---\nmarp: true\n---\n# One\n")
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                notifications += notification
            }
        })
    }

    override fun tearDown() {
        try {
            MarpAppSettings.getInstance().update { marpCliPath = null }
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

    /** A "marp" that records its arguments, working directory and config, and writes the file after `-o`. */
    private fun fakeMarp(afterwards: String = ""): Path {
        val script = work.resolve("bin").resolve("marp")
        Files.createDirectories(script.parent)
        Files.writeString(
            script,
            """
            #!/bin/sh
            dir=${'$'}(dirname "${'$'}0")
            printf '%s\n' "${'$'}@" > "${'$'}dir/args"
            pwd > "${'$'}dir/cwd"
            cp "${'$'}2" "${'$'}dir/config.json"
            echo "${'$'}2" > "${'$'}dir/config-path"
            cp "${'$'}(dirname "${'$'}2")"/url-theme-*.css "${'$'}dir/" 2>/dev/null
            while [ ${'$'}# -gt 0 ]; do
              if [ "${'$'}1" = "-o" ]; then out="${'$'}2"; fi
              shift
            done
            echo data > "${'$'}out"
            $afterwards
            """.trimIndent() + "\n",
        )
        assertTrue(script.toFile().setExecutable(true))
        MarpAppSettings.getInstance().update { marpCliPath = script.toString() }
        return script
    }

    private fun export(format: MarpCliFormat, target: Path) {
        runBlocking(Dispatchers.Default) { MarpCliExporter.run(project, deck, format, target) }
    }

    private fun recorded(name: String): String = Files.readString(work.resolve("bin").resolve(name))

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
        assertEquals("the CLI runs in the folder of the deck", work.toRealPath(), Path.of(recorded("cwd").trim()).toRealPath())
        assertTrue(Files.isRegularFile(target))
        assertFalse("the temporary config folder is removed", Files.exists(Path.of(configPath).parent))

        val notification = onePlainNotification()
        assertEquals(NotificationType.INFORMATION, notification.type)
        assertEquals(MarpBundle.message("export.done", "deck.pptx"), notification.content)
        assertEquals(listOf(MarpBundle.message("export.open")), notification.actions.map { it.templateText })
    }

    fun testImageExportOffersToShowTheFolder() {
        if (SystemInfo.isWindows) return
        // The CLI writes deck.001.png, ..., not the name it was given, but the fake one writes that name: it must not matter.
        fakeMarp()
        export(MarpCliFormat.PNG, work.resolve("deck.png"))

        assertEquals(listOf("--images", "png"), recorded("args").lines().drop(2).take(2))
        val notification = onePlainNotification()
        assertEquals(NotificationType.INFORMATION, notification.type)
        assertEquals(MarpBundle.message("export.cli.done.images", "deck.001.png"), notification.content)
        assertEquals(listOf(MarpBundle.message("export.cli.showFolder")), notification.actions.map { it.templateText })
    }

    fun testImageExportSucceedsWithoutTheChosenFile() {
        if (SystemInfo.isWindows) return
        fakeMarp(afterwards = "rm -f \"\$out\"")
        export(MarpCliFormat.JPEG, work.resolve("deck.jpg"))
        assertEquals(NotificationType.INFORMATION, onePlainNotification().type)
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
        assertEquals(MarpBundle.message("export.cli.failed.noOutput", "deck.pptx", 2), onePlainNotification().content)
    }

    fun testSuccessWithoutAPptxFileIsAFailure() {
        if (SystemInfo.isWindows) return
        fakeMarp(afterwards = "rm -f \"\$out\"")
        export(MarpCliFormat.PPTX, work.resolve("deck.pptx"))
        val notification = onePlainNotification()
        assertEquals(NotificationType.ERROR, notification.type)
        assertEquals(MarpBundle.message("export.cli.failed.noOutput", "deck.pptx", 0), notification.content)
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
}
