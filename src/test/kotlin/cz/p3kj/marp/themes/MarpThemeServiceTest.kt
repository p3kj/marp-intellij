package cz.p3kj.marp.themes

import com.intellij.ide.trustedProjects.TrustedProjectsListener
import com.intellij.openapi.application.ApplicationManager
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

class MarpThemeServiceTest : MarpLightTestCase() {
    private lateinit var base: Path
    private lateinit var service: MarpThemeService
    private val fetched = CopyOnWriteArrayList<String>()
    private var trusted = true
    private var now = 0L

    override fun setUp() {
        super.setUp()
        base = Files.createTempDirectory("marp-themes").toRealPath()
        service = MarpThemeService.getInstance(project)
        service.projectDirProvider = { base }
        trusted = true
        now = 0L
        service.trustedProvider = { trusted }
        service.clock = { now }
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

    fun testCachedThemeNamesForCompletion() {
        write("a.css", "/* @theme alpha */")
        write("b.css", "/* @theme beta */")
        write("c.css", "/* @theme alpha */")
        write("d.css", "section {}")
        settings(false, "a.css", "b.css", "c.css", "d.css")
        // Nothing is cached yet: the call does not wait for the themes, it only starts loading them.
        assertEmpty(service.cachedThemeNames())
        load()
        assertEquals(listOf("alpha", "beta"), service.cachedThemeNames())
        // The names of a cached set are read once.
        assertSame(service.cachedThemeNames(), service.cachedThemeNames())
    }

    fun testCachedThemeFileForNavigation() {
        val alpha = write("a.css", "/* @theme alpha */")
        write("b.css", "section {}")
        settings(false, "a.css", "b.css", "https://example.com/remote.css")
        // Nothing is cached yet: the call does not wait for the themes, it only starts loading them.
        assertNull(service.cachedThemeFile("alpha"))
        load()
        assertEquals(alpha, service.cachedThemeFile("alpha"))
        // A URL theme has no file, an unknown name has none either.
        assertNull(service.cachedThemeFile("remote"))
        assertNull(service.cachedThemeFile("gaia"))
        assertNull(service.cachedThemeFile("beta"))
    }

    fun testCachedThemeFileOfADuplicateNameIsTheLastOne() {
        write("a.css", "/* @theme alpha */")
        val second = write("b.css", "/* @theme alpha */")
        settings(false, "a.css", "b.css")
        load()
        assertEquals(second, service.cachedThemeFile("alpha"))
    }

    fun testCachedThemeFileOfANameThatAUrlThemeRedeclaresIsNull() {
        write("a.css", "/* @theme remote */")
        settings(false, "a.css", "https://example.com/remote.css")
        load()
        assertNull(service.cachedThemeFile("remote"))
    }

    fun testThemeNamesForTheInspection() {
        write("a.css", "/* @theme alpha */")
        write("b.css", "/* @theme beta */")
        settings(false, "a.css", "b.css")
        // Nothing is cached yet: unknown, and the themes start loading.
        assertNull(service.themeNamesForInspection())
        load()
        assertEquals(setOf("alpha", "beta"), service.themeNamesForInspection())
    }

    fun testNoThemeNamesForTheInspectionWhenTheSetHasAnError() {
        write("a.css", "/* @theme alpha */")
        settings(false, "a.css", "missing.css")
        load()
        assertNull(service.themeNamesForInspection())
    }

    fun testNoThemeNamesForTheInspectionInAnUntrustedProject() {
        write("a.css", "/* @theme alpha */")
        settings(false, "a.css")
        load()
        assertNotNull(service.themeNamesForInspection())
        trusted = false
        assertNull(service.themeNamesForInspection())
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

    /** Invalidates the resolved set (like editing a theme) without a settings change, so the download cache stays. */
    private fun editTheme(path: Path) {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        val doc = FileDocumentManager.getInstance().getDocument(vf)!!
        awaitChange { WriteCommandAction.runWriteCommandAction(project) { doc.insertString(doc.textLength, " ") } }
    }

    fun testFailedDownloadIsCachedForAWhile() {
        val local = write("a.css", "/* @theme a */")
        settings(false, "a.css", "https://example.com/fail.css")
        assertEquals(1, load().errors.size)
        assertEquals(1, fetched.size)

        editTheme(local)
        now += MarpThemeService.FAILED_DOWNLOAD_TTL_MS - 1
        val again = load()
        assertEquals(1, again.errors.size)
        assertTrue(again.errors[0], again.errors[0].contains("boom"))
        assertEquals("the failure is served from the cache", 1, fetched.size)

        editTheme(local)
        now += 1
        assertEquals(1, load().errors.size)
        assertEquals("retried once the failure expired", 2, fetched.size)

        // A settings change retries right away.
        awaitChange { settings(false, "https://example.com/fail.css") }
        load()
        assertEquals(3, fetched.size)
    }

    fun testUrlsDownloadInParallelAndKeepEntryOrder() {
        val barrier = CyclicBarrier(2)
        service.urlFetcher = { url ->
            fetched += url
            // Both downloads have to be running at the same time to get past the barrier.
            barrier.await(5, TimeUnit.SECONDS)
            if (url.endsWith("first.css")) Thread.sleep(100)
            "/* @theme ${url.substringAfterLast('/').removeSuffix(".css")} */"
        }
        write("local.css", "/* @theme local */")
        settings(false, "https://example.com/first.css", "local.css", "https://example.com/second.css", "https://example.com/first.css")
        val set = load()
        assertEmpty(set.errors)
        assertEquals(listOf("https://example.com/first.css", "local.css", "https://example.com/second.css"), set.themes.map { it.source })
        assertEquals(2, fetched.size)
    }

    fun testUntrustedProjectLoadsNoCustomThemes() {
        write("a.css", "a")
        write("themes/b.css", "b")
        write(".marprc.yml", "themeSet: themes\n")
        trusted = false

        // The settings can come from the repository (.idea/marp.xml) as much as the .marprc: nothing is loaded.
        settings(true, "a.css", "https://example.com/t.css")
        val set = load()
        assertEmpty(set.themes)
        assertEquals(1, set.errors.size)
        assertTrue(set.errors[0], set.errors[0].contains("trust"))
        assertEmpty(fetched)

        // Themes named only by the .marprc: the same single line.
        settings(true)
        assertEquals(1, load().errors.size)

        // Nothing configured: nothing to report.
        settings(false)
        assertEmpty(load().errors)

        // Problems of an untrusted .marprc are not shown.
        Files.delete(base.resolve(".marprc.yml"))
        write(".marprc.json", "{\"themeSet\": [")
        settings(true)
        assertEmpty(load().errors)

        // Trusting the project reloads everything.
        Files.delete(base.resolve(".marprc.json"))
        write(".marprc.yml", "themeSet: themes\n")
        settings(true, "a.css", "https://example.com/t.css")
        trusted = true
        awaitChange { ApplicationManager.getApplication().messageBus.syncPublisher(TrustedProjectsListener.TOPIC).onProjectTrusted(project) }
        val trustedSet = load()
        assertEmpty(trustedSet.errors)
        assertEquals(listOf("a.css", "https://example.com/t.css", "themes/b.css"), trustedSet.themes.map { it.source })
    }

    fun testMarprcThemesMustStayInsideTheProject() {
        val outside = Files.createTempDirectory("marp-outside").toRealPath()
        try {
            val foreign = outside.resolve("x.css")
            Files.writeString(foreign, "/* @theme x */")
            write("themes/in.css", "in")
            Files.createSymbolicLink(base.resolve("themes/link.css"), foreign)
            write(".marprc.yml", "themeSet:\n  - ../${outside.fileName}/x.css\n  - $foreign\n  - themes\n")
            settings(true)
            val set = load()
            assertEquals(listOf("themes/in.css"), set.themes.map { it.source })
            assertEquals(3, set.errors.size)
            for (error in set.errors) assertTrue(error, error.contains("outside the project") && error.contains(".marprc.yml"))
            assertTrue(set.errors[2], set.errors[2].contains("themes/link.css"))

            // Entries the user adds in the settings may point anywhere.
            settings(false, foreign.toString())
            val fromSettings = load()
            assertEmpty(fromSettings.errors)
            assertEquals(listOf("/* @theme x */"), fromSettings.themes.map { it.css })
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    fun testRelativeEntriesNeedAProjectDirectory() {
        val absolute = write("a.css", "a")
        service.projectDirProvider = { null }
        settings(true, "a.css", absolute.toString())
        val set = load()
        assertEquals(listOf(absolute.toString().replace('\\', '/')), set.themes.map { it.source })
        assertEquals(1, set.errors.size)
        assertTrue(set.errors[0], set.errors[0].contains("a.css"))
    }

    fun testRejectedDownloadShowsItsOwnMessage() {
        service.urlFetcher = { url -> throw MarpThemeRejectedException("rejected $url") }
        settings(false, "https://example.com/big.css")
        assertEquals(listOf("rejected https://example.com/big.css"), load().errors)
    }

    fun testThemeResponsesAreCheckedForTypeAndSize() {
        val url = "https://example.com/t.css"
        fun read(contentType: String?, body: ByteArray, contentLength: Long = body.size.toLong()) =
            MarpThemeService.readThemeResponse(url, contentType, contentLength) { body.inputStream() }

        assertEquals("a{}", read("text/css; charset=utf-8", "a{}".toByteArray()))
        assertEquals("a{}", read("TEXT/PLAIN", "a{}".toByteArray()))
        assertEquals("a{}", read(null, "a{}".toByteArray()))
        assertEquals("caf\u00e9", read("text/css; charset=\"ISO-8859-1\"", "caf\u00e9".toByteArray(Charsets.ISO_8859_1)))
        assertEquals("unknown charset falls back to UTF-8", "caf\u00e9", read("text/css; charset=nope", "caf\u00e9".toByteArray()))

        val html = org.junit.Assert.assertThrows(MarpThemeRejectedException::class.java) { read("text/html", "<html>".toByteArray()) }
        assertTrue(html.userMessage, html.userMessage.contains("text/html") && html.userMessage.contains(url))

        // A declared length over the limit is refused before the body is read.
        org.junit.Assert.assertThrows(MarpThemeRejectedException::class.java) {
            MarpThemeService.readThemeResponse(url, "text/css", MarpThemeService.MAX_DOWNLOAD_BYTES + 1L) { error("body read") }
        }
        // Without a declared length the body is read up to one byte over the limit.
        val big = ByteArray(MarpThemeService.MAX_DOWNLOAD_BYTES + 1) { 'a'.code.toByte() }
        org.junit.Assert.assertThrows(MarpThemeRejectedException::class.java) { read("text/css", big, contentLength = -1) }
        assertEquals(MarpThemeService.MAX_DOWNLOAD_BYTES, read("text/css", big.copyOf(MarpThemeService.MAX_DOWNLOAD_BYTES), -1).length)
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
