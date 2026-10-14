package cz.p3kj.marp.directives

import com.intellij.openapi.vfs.LocalFileSystem
import cz.p3kj.marp.MarpLightTestCase
import cz.p3kj.marp.settings.MarpSettings
import cz.p3kj.marp.themes.MarpThemeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

/** `class` completion with the classes of the theme of the deck, in comments and in the front matter. */
class MarpClassCompletionTest : MarpLightTestCase() {
    private lateinit var base: Path
    private lateinit var service: MarpThemeService
    private lateinit var previousProjectDir: () -> Path?
    private lateinit var previousTrusted: () -> Boolean
    private lateinit var previousFetcher: (String) -> String
    private lateinit var previousThemes: List<String>
    private var previousUseMarprc = true

    override fun setUp() {
        super.setUp()
        base = Files.createTempDirectory("marp-class-completion").toRealPath()
        service = MarpThemeService.getInstance(project)
        previousProjectDir = service.projectDirProvider
        previousTrusted = service.trustedProvider
        previousFetcher = service.urlFetcher
        MarpSettings.getInstance(project).let {
            previousThemes = it.themes.toList()
            previousUseMarprc = it.useMarprcThemeSet
        }
        service.projectDirProvider = { base }
        service.trustedProvider = { true }
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base)
        write("themes/alpha.css", "/* @theme alpha */\n@import 'default';\nsection.one{}\nsection.two h1{}\nsection.lead{}")
        write("themes/beta.css", "/* @theme beta */\nsection.three{}")
        settings("themes/alpha.css", "themes/beta.css")
        runBlocking(Dispatchers.Default) { service.loadThemes() }
    }

    override fun tearDown() {
        try {
            service.trustedProvider = previousTrusted
            service.urlFetcher = previousFetcher
            MarpSettings.getInstance(project).update {
                themes.clear()
                themes.addAll(previousThemes)
                useMarprcThemeSet = previousUseMarprc
            }
            service.projectDirProvider = previousProjectDir
            base.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun write(rel: String, text: String) {
        val path = base.resolve(rel)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
    }

    private fun settings(vararg themes: String) {
        MarpSettings.getInstance(project).update {
            this.themes.clear()
            this.themes.addAll(themes)
            useMarprcThemeSet = false
        }
    }

    private fun complete(text: String): List<String>? {
        myFixture.configureByText("deck.md", text)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings
    }

    private fun front(theme: String? = null, extra: String = "") =
        "---\nmarp: true\n" + (theme?.let { "theme: $it\n" } ?: "") + extra + "---\n\n"

    fun testOffersTheClassesOfTheThemeFromTheFrontMatter() {
        assertEquals(listOf("lead", "invert", "one", "two"), complete(front("alpha") + "<!-- class: <caret> -->"))
    }

    fun testOffersThemeClassesInTheFrontMatter() {
        val items = complete(front(extra = "class: <caret>\n").replace("marp: true\n", "marp: true\ntheme: alpha\n"))!!
        assertContainsElements(items, "one", "two")
    }

    fun testOffersThemeClassesForASpotDirective() {
        assertContainsElements(complete(front("alpha") + "<!-- _class: <caret> -->")!!, "one", "two")
    }

    fun testAThemeInALaterCommentCounts() {
        val items = complete(front() + "<!-- theme: alpha -->\n\n---\n\n<!-- class: <caret> -->")!!
        assertContainsElements(items, "one", "two")
    }

    fun testTheLastKnownThemeWinsAndUnknownNamesAreIgnored() {
        val items = complete(front("alpha") + "<!-- theme: beta -->\n<!-- theme: nope -->\n<!-- class: <caret> -->")!!
        assertEquals(listOf("lead", "invert", "three"), items)
    }

    fun testABuiltInTheme() {
        assertEquals(listOf("lead", "invert", "gaia"), complete(front("gaia") + "<!-- class: <caret> -->"))
    }

    fun testWithoutAThemeOnlyTheCommonClasses() {
        assertEquals(listOf("lead", "invert"), complete(front() + "<!-- class: <caret> -->"))
    }

    fun testAnUnknownThemeOffersTheClassesOfAllCustomThemes() {
        val items = complete(front("nope") + "<!-- class: <caret> -->")!!
        assertContainsElements(items, "one", "two", "three")
    }

    fun testASecondClassSkipsTheOnesAlreadyWritten() {
        val items = complete(front("alpha") + "<!-- class: lead <caret> -->")!!
        assertDoesntContain(items, "lead")
        assertContainsElements(items, "invert", "one", "two")
    }

    fun testASecondClassIsCompletedByPrefix() {
        complete(front("alpha") + "<!-- class: lead tw<caret> -->")
        myFixture.checkResult(front("alpha") + "<!-- class: lead two -->")
    }

    fun testNoClassesForAnotherDirectiveAfterAWord() {
        val items = complete(front("alpha") + "<!-- paginate: true <caret> -->")
        assertTrue(items.isNullOrEmpty())
        myFixture.checkResult(front("alpha") + "<!-- paginate: true  -->")
    }
}
