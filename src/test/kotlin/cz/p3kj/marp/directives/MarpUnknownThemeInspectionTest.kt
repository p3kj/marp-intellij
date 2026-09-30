package cz.p3kj.marp.directives

import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import cz.p3kj.marp.MarpLightTestCase
import cz.p3kj.marp.settings.MarpSettings
import cz.p3kj.marp.themes.MarpThemeService
import cz.p3kj.marp.themes.MarprcParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MarpUnknownThemeInspectionTest : MarpLightTestCase() {
    private lateinit var base: Path
    private lateinit var service: MarpThemeService
    private lateinit var previousProjectDir: () -> Path?
    private lateinit var previousTrusted: () -> Boolean
    private lateinit var previousFetcher: (String) -> String
    private var trusted = true
    private val defaultChooser = MarpThemeChooser.choose

    private val deck = "---\nmarp: true\n---\n\n"
    private val addToSettings = "Add theme file or folder to Marp settings…"
    private val createMarprc = "Create .marprc.yml with themeSet…"
    private val openMarprc = "Open .marprc.yml"
    private val openSettings = "Open Marp settings"

    override fun setUp() {
        super.setUp()
        base = Files.createTempDirectory("marp-unknown-theme").toRealPath()
        service = MarpThemeService.getInstance(project)
        previousProjectDir = service.projectDirProvider
        previousTrusted = service.trustedProvider
        previousFetcher = service.urlFetcher
        service.projectDirProvider = { base }
        trusted = true
        service.trustedProvider = { trusted }
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base)
        write("themes/alpha.css", "/* @theme alpha */")
        settings(useMarprc = true, "themes/alpha.css")
        load()
        myFixture.enableInspections(MarpUnknownThemeInspection::class.java)
    }

    override fun tearDown() {
        try {
            MarpThemeChooser.choose = defaultChooser
            service.trustedProvider = previousTrusted
            service.urlFetcher = previousFetcher
            settings(useMarprc = true)
            service.projectDirProvider = previousProjectDir
            base.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    /** Writes a file and tells the virtual file system, before the themes are loaded (a VFS event drops the cached set). */
    private fun write(rel: String, text: String): VirtualFile {
        val path = base.resolve(rel)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
    }

    private fun settings(useMarprc: Boolean, vararg themes: String) {
        MarpSettings.getInstance(project).update {
            this.themes.clear()
            this.themes.addAll(themes)
            useMarprcThemeSet = useMarprc
        }
    }

    private fun load() {
        runBlocking(Dispatchers.Default) { service.loadThemes() }
    }

    private fun check(text: String) {
        myFixture.configureByText("deck.md", text)
        myFixture.checkHighlighting(true, false, true)
    }

    private fun fixesAt(text: String): List<String> {
        myFixture.configureByText("deck.md", text)
        return myFixture.availableIntentions.map { it.text }
    }

    private fun unknown(name: String) = "<warning descr=\"Unknown Marp theme '$name'\">$name</warning>"

    fun testIsRegisteredWithADescription() {
        val ep = LocalInspectionEP.LOCAL_INSPECTION.extensionList.firstOrNull { it.shortName == "MarpUnknownTheme" }
        assertNotNull(ep)
        assertEquals(MarpUnknownThemeInspection::class.java.name, ep!!.implementationClass)
        assertEquals("Unknown Marp theme", ep.getDisplayName())
        assertEquals("Marp", ep.getGroupDisplayName())
        assertNotNull(MarpUnknownThemeInspection::class.java.getResource("/inspectionDescriptions/MarpUnknownTheme.html"))
    }

    fun testKnownThemesAreClean() {
        check("---\nmarp: true\ntheme: alpha\n---\n\n<!-- theme: gaia -->\n<!-- _theme: uncover -->\n<!-- theme: 'default' -->\n")
    }

    fun testUnknownThemeInTheFrontMatter() {
        check("---\nmarp: true\ntheme: ${unknown("nope")}\n---\n")
    }

    fun testUnknownThemeInAComment() {
        check("${deck}<!-- theme: ${unknown("nope")} -->\n")
    }

    fun testQuotedName() {
        check("---\nmarp: true\ntheme: <warning descr=\"Unknown Marp theme 'nope'\">'nope'</warning>\n---\n")
    }

    fun testQuotedNameKeepsItsSpaces() {
        check("${deck}<!-- theme: <warning descr=\"Unknown Marp theme ' alpha '\">' alpha '</warning> -->\n")
    }

    fun testThemeNamesAreCaseSensitive() {
        check("${deck}<!-- theme: ${unknown("Gaia")} -->\n")
    }

    fun testSpotDirectiveOfAGlobalIsNotThisInspectionsBusiness() {
        check("${deck}<!-- _theme: nope -->\n")
    }

    fun testOtherDirectivesAndPlainMarkdownAreLeftAlone() {
        check("${deck}<!-- class: nope -->\n")
        check("---\ntheme: nope\n---\n\n<!-- theme: nope -->\n")
    }

    fun testQuietWhileTheThemesAreNotLoaded() {
        // A download that has not finished keeps the set from loading (a changed setting starts loading it right away).
        val release = CountDownLatch(1)
        service.urlFetcher = { release.await(30, TimeUnit.SECONDS); "/* @theme remote */" }
        try {
            settings(useMarprc = false, "themes/alpha.css", "https://example.com/remote.css")
            check("---\nmarp: true\ntheme: nope\n---\n")
        } finally {
            release.countDown()
        }
        load()
        check("---\nmarp: true\ntheme: ${unknown("nope")}\n---\n")
        check("---\nmarp: true\ntheme: remote\n---\n")
    }

    fun testHighlightingOfOpenMarkdownFilesCanBeRestartedOffTheEdt() {
        myFixture.configureByText("deck.md", "---\nmarp: true\ntheme: nope\n---\n")
        myFixture.doHighlighting()
        runBlocking(Dispatchers.Default) { service.restartMarkdownHighlighting() }
    }

    fun testQuietInAnUntrustedProject() {
        trusted = false
        check("${deck}<!-- theme: nope -->\n")
    }

    fun testQuietWhileAThemeSourceHasAnError() {
        settings(useMarprc = true, "themes/alpha.css", "missing.css")
        load()
        check("${deck}<!-- theme: nope -->\n")
    }

    fun testOffersTheFixesWithoutAMarprc() {
        val fixes = fixesAt("${deck}<!-- theme: no<caret>pe -->\n")
        assertTrue(fixes.toString(), fixes.containsAll(listOf(addToSettings, createMarprc, openSettings)))
        assertFalse(fixes.toString(), fixes.contains(openMarprc))
    }

    fun testOffersToOpenAnExistingMarprc() {
        write("themes/beta.css", "/* @theme beta */")
        write(".marprc.yml", "themeSet: themes\n")
        load()
        val fixes = fixesAt("${deck}<!-- theme: no<caret>pe -->\n")
        assertTrue(fixes.toString(), fixes.containsAll(listOf(addToSettings, openMarprc, openSettings)))
        assertFalse(fixes.toString(), fixes.contains(createMarprc))
    }

    fun testNoMarprcFixesWhenTheSettingIsOff() {
        settings(useMarprc = false, "themes/alpha.css")
        load()
        val fixes = fixesAt("${deck}<!-- theme: no<caret>pe -->\n")
        assertTrue(fixes.toString(), fixes.containsAll(listOf(addToSettings, openSettings)))
        assertFalse(fixes.toString(), fixes.contains(createMarprc) || fixes.contains(openMarprc))
    }

    fun testAddingAFileToTheSettings() {
        val beta = write("themes/beta.css", "/* @theme beta */")
        MarpThemeChooser.choose = { _, _, _ -> beta }
        myFixture.configureByText("deck.md", "${deck}<!-- theme: no<caret>pe -->\n")
        myFixture.launchAction(myFixture.findSingleIntention(addToSettings))
        assertEquals(listOf("themes/alpha.css", "themes/beta.css"), MarpSettings.getInstance(project).themes)
        load()
        assertTrue(runBlocking(Dispatchers.Default) { service.loadThemes() }.themes.any { it.css.contains("@theme beta") })
        assertEquals(listOf("alpha", "beta"), service.cachedThemeNames())
        check("${deck}<!-- theme: beta -->\n")
    }

    fun testAddingTheSameFileAgainKeepsTheListAsItIs() {
        val beta = write("themes/beta.css", "/* @theme beta */")
        MarpThemeChooser.choose = { _, _, _ -> beta }
        myFixture.configureByText("deck.md", "${deck}<!-- theme: no<caret>pe -->\n")
        myFixture.launchAction(myFixture.findSingleIntention(addToSettings))
        load()
        myFixture.configureByText("deck.md", "${deck}<!-- theme: no<caret>pe -->\n")
        myFixture.launchAction(myFixture.findSingleIntention(addToSettings))
        assertEquals(listOf("themes/alpha.css", "themes/beta.css"), MarpSettings.getInstance(project).themes)
    }

    fun testCancellingTheChooserChangesNothing() {
        MarpThemeChooser.choose = { _, _, _ -> null }
        myFixture.configureByText("deck.md", "${deck}<!-- theme: no<caret>pe -->\n")
        myFixture.launchAction(myFixture.findSingleIntention(addToSettings))
        assertEquals(listOf("themes/alpha.css"), MarpSettings.getInstance(project).themes)
    }

    fun testCreatingAMarprc() {
        val themes = write("themes/beta.css", "/* @theme beta */").parent
        MarpThemeChooser.choose = { _, _, _ -> themes }
        myFixture.configureByText("deck.md", "${deck}<!-- theme: no<caret>pe -->\n")
        myFixture.launchAction(myFixture.findSingleIntention(createMarprc))
        val marprc = base.resolve(".marprc.yml")
        assertTrue(Files.isRegularFile(marprc))
        assertEquals(listOf("themes"), MarprcParser.parseThemeSet(".marprc.yml", Files.readString(marprc)))
    }

    fun testCreatingAMarprcForTheProjectFolderUsesADot() {
        val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base)!!
        MarpThemeChooser.choose = { _, _, _ -> root }
        myFixture.configureByText("deck.md", "${deck}<!-- theme: no<caret>pe -->\n")
        myFixture.launchAction(myFixture.findSingleIntention(createMarprc))
        assertEquals(listOf("."), MarprcParser.parseThemeSet(".marprc.yml", Files.readString(base.resolve(".marprc.yml"))))
    }
}
