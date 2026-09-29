package cz.p3kj.marp.themes

import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import cz.p3kj.marp.MarpLightTestCase
import cz.p3kj.marp.settings.MarpSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class MarpThemeServiceTest : MarpLightTestCase() {
    private lateinit var base: Path
    private lateinit var service: MarpThemeService
    private val fetched = mutableListOf<String>()

    override fun setUp() {
        super.setUp()
        base = Files.createTempDirectory("marp-themes").toRealPath()
        service = MarpThemeService.getInstance(project)
        service.projectDirProvider = { base }
        service.urlFetcher = { url ->
            fetched += url
            if (url.contains("fail")) throw java.io.IOException("boom")
            "/* @theme remote */ from $url"
        }
    }

    override fun tearDown() {
        try {
            base.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun write(rel: String, text: String): Path {
        val p = base.resolve(rel)
        Files.createDirectories(p.parent)
        Files.writeString(p, text)
        return p
    }

    private fun settings(useMarprc: Boolean = false, vararg themes: String) {
        MarpSettings.getInstance(project).update {
            this.themes.clear()
            this.themes.addAll(themes)
            useMarprcThemeSet = useMarprc
        }
    }

    private fun load(): MarpThemeSet = runBlocking(Dispatchers.Default) { service.loadThemes() }

    private fun awaitChange(action: () -> Unit) {
        val future = CompletableFuture<Unit>()
        val connection = project.messageBus.connect(testRootDisposable)
        connection.subscribe(MarpThemeListener.TOPIC, MarpThemeListener { future.complete(Unit) })
        action()
        future.get(10, TimeUnit.SECONDS)
        connection.disconnect()
    }

    fun testRelativeAndAbsolutePaths() {
        write("themes/a.css", "/* @theme a */")
        write("b.css", "/* @theme b */")
        val abs = write("elsewhere/c.css", "/* @theme c */").toAbsolutePath().toString()
        settings(false, "./themes/a.css", "b.css", abs)
        val set = load()
        assertEmpty(set.errors)
        assertEquals(listOf("themes/a.css", "b.css"), set.themes.take(2).map { it.source })
        assertEquals("/* @theme c */", set.themes[2].css)
        assertEquals(3, set.themes.size)
    }

    fun testFolderExpansionIsRecursiveAndSorted() {
        write("t/b.css", "b")
        write("t/a.css", "a")
        write("t/sub/c.css", "c")
        write("t/node_modules/x.css", "x")
        write("t/readme.md", "no")
        settings(false, "t")
        assertEquals(listOf("t/a.css", "t/b.css", "t/sub/c.css"), load().themes.map { it.source })
    }

    fun testMarprcVariants() {
        write("themes/a.css", "a")
        write("more/b.css", "b")

        write(".marprc.yml", "themeSet: themes\n")
        settings(true)
        assertEquals(listOf("themes/a.css"), load().themes.map { it.source })

        write(".marprc.yml", "themeSet:\n  - themes\n  - ./more/b.css\n")
        settings(true)
        assertEquals(listOf("themes/a.css", "more/b.css"), load().themes.map { it.source })

        Files.delete(base.resolve(".marprc.yml"))
        write(".marprc.json", """{"themeSet": ["more"]}""")
        settings(true)
        assertEquals(listOf("more/b.css"), load().themes.map { it.source })

        settings(false)
        assertEmpty(load().themes)
    }

    fun testDedupeBetweenSettingsAndMarprc() {
        write("themes/a.css", "a")
        write("themes/b.css", "b")
        write(".marprc.yml", "themeSet: themes\n")
        settings(true, "./themes/a.css", "themes/../themes/a.css")
        val set = load()
        assertEquals(listOf("themes/a.css", "themes/b.css"), set.themes.map { it.source })
    }

    fun testMissingAndUnsupported() {
        write("ok.css", "ok")
        settings(false, "missing.css", "ok.css", "ftp://host/x.css", "nodir/")
        val set = load()
        assertEquals(listOf("ok.css"), set.themes.map { it.source })
        assertEquals(3, set.errors.size)
        assertTrue(set.errors[0], set.errors[0].contains("missing.css"))
        assertTrue(set.errors[1], set.errors[1].contains("ftp://host/x.css"))
    }

    fun testUrlEntriesAndCache() {
        settings(false, "https://example.com/t.css", "https://example.com/fail.css")
        val set = load()
        assertEquals(listOf("https://example.com/t.css"), set.themes.map { it.source })
        assertEquals("/* @theme remote */ from https://example.com/t.css", set.themes[0].css)
        assertEquals(1, set.errors.size)
        assertTrue(set.errors[0], set.errors[0].contains("fail.css") && set.errors[0].contains("boom"))

        val before = fetched.size
        assertSame(set, load()) // resolved set is cached
        assertEquals(before, fetched.size)

        // Settings change clears the URL cache: the URL is fetched again.
        awaitChange { settings(false, "https://example.com/t.css") }
        load()
        assertEquals(2, fetched.count { it == "https://example.com/t.css" })
    }

    fun testSettingsChangeInvalidatesAndPublishes() {
        write("a.css", "a")
        settings(false, "a.css")
        assertEquals(1, load().themes.size)
        awaitChange { settings(false, "a.css", "a.css") }
        assertEquals(1, load().themes.size)
        write("b.css", "b")
        awaitChange { settings(false, "a.css", "b.css") }
        assertEquals(2, load().themes.size)
    }

    fun testVfsChangeInvalidatesAndPublishes() {
        val a = write("a.css", "old")
        settings(false, "a.css")
        assertEquals("old", load().themes[0].css)

        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(a)!!
        awaitChange { runWriteAction { VfsUtil.saveText(vf, "new") } }
        assertEquals("new", load().themes[0].css)

        // A missing entry appearing later is picked up too.
        settings(false, "a.css", "later.css")
        assertEquals(1, load().errors.size)
        awaitChange {
            val dir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base)!!
            runWriteAction { VfsUtil.saveText(dir.createChildData(this, "later.css"), "l") }
        }
        assertEquals(2, load().themes.size)
        assertEmpty(load().errors)
    }

    fun testMarprcChangePublishes() {
        write("themes/a.css", "a")
        write(".marprc.yml", "themeSet: nothing\n")
        settings(true)
        assertEquals(1, load().errors.size)
        awaitChange {
            write(".marprc.yml", "themeSet: themes\n")
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base.resolve(".marprc.yml"))?.let { VfsUtil.markDirtyAndRefresh(false, false, false, it) }
        }
        assertEquals(listOf("themes/a.css"), load().themes.map { it.source })
    }

    fun testUnsavedDocumentWinsAndDocumentChangesPublish() {
        val a = write("a.css", "disk")
        settings(false, "a.css")
        assertEquals("disk", load().themes[0].css)

        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(a)!!
        val doc = FileDocumentManager.getInstance().getDocument(vf)!!
        awaitChange {
            WriteCommandAction.runWriteCommandAction(project) { doc.setText("editor") }
        }
        assertEquals("editor", load().themes[0].css)
        assertEquals("disk", Files.readString(a))
    }
}
