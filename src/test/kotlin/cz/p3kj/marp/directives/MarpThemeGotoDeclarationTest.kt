package cz.p3kj.marp.directives

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import cz.p3kj.marp.MarpLightTestCase
import cz.p3kj.marp.settings.MarpSettings
import cz.p3kj.marp.themes.MarpThemeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MarpThemeGotoDeclarationTest : MarpLightTestCase() {
    private lateinit var base: Path
    private lateinit var service: MarpThemeService
    private lateinit var alpha: VirtualFile
    private lateinit var previousProjectDir: () -> Path?
    private lateinit var previousTrusted: () -> Boolean
    private lateinit var previousFetcher: (String) -> String
    private val handler = MarpThemeGotoDeclarationHandler()

    private val themeCss = "/* a */\n/* @theme alpha */\n"
    private val deck = "---\nmarp: true\n---\n\n"

    override fun setUp() {
        super.setUp()
        base = Files.createTempDirectory("marp-goto-theme").toRealPath()
        service = MarpThemeService.getInstance(project)
        previousProjectDir = service.projectDirProvider
        previousTrusted = service.trustedProvider
        previousFetcher = service.urlFetcher
        service.projectDirProvider = { base }
        service.trustedProvider = { true }
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base)
        alpha = write("themes/alpha.css", themeCss)
        settings("themes/alpha.css")
        load()
    }

    override fun tearDown() {
        try {
            service.trustedProvider = previousTrusted
            service.urlFetcher = previousFetcher
            settings()
            service.projectDirProvider = previousProjectDir
            base.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun write(rel: String, text: String): VirtualFile {
        val path = base.resolve(rel)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
    }

    private fun settings(vararg themes: String) {
        MarpSettings.getInstance(project).update {
            this.themes.clear()
            this.themes.addAll(themes)
            useMarprcThemeSet = false
        }
    }

    private fun load() {
        runBlocking(Dispatchers.Default) { service.loadThemes() }
    }

    /** Asks the way the IDE does for the Markdown file: the leaf at the caret and the caret offset in the document. */
    private fun targets(textWithCaret: String): List<PsiElement> {
        myFixture.configureByText("deck.md", textWithCaret)
        val (host, offset) = MarpFrontMatter.hostOf(myFixture.file, myFixture.caretOffset)
        return handler.getGotoDeclarationTargets(host.findElementAt(offset), offset, myFixture.editor).orEmpty().toList()
    }

    /** Asks about the injected YAML file of the front matter with the offset in it, `null` when there is no injection. */
    private fun injectedTargets(textWithCaret: String): List<PsiElement>? {
        myFixture.configureByText("deck.md", textWithCaret)
        val (host, offset) = MarpFrontMatter.hostOf(myFixture.file, myFixture.caretOffset)
        val manager = InjectedLanguageManager.getInstance(project)
        val leaf = manager.findInjectedElementAt(host, offset) ?: return null
        val injectedOffset = offset - manager.injectedToHost(leaf.containingFile, 0)
        return handler.getGotoDeclarationTargets(leaf, injectedOffset, myFixture.editor).orEmpty().toList()
    }

    /** The one target is in the theme file, at the `@theme` comment. */
    private fun assertOpensAlpha(targets: List<PsiElement>?) {
        assertNotNull(targets)
        assertEquals(1, targets!!.size)
        val target = targets.single()
        assertEquals(alpha, target.containingFile.virtualFile)
        // A comment token with the CSS plugin, the whole file without it: both contain the name.
        val nameOffset = themeCss.indexOf("alpha")
        assertTrue("$target does not contain the name", target.textRange.startOffset <= nameOffset && nameOffset < target.textRange.endOffset)
    }

    private fun assertOpensAlphaFromFrontMatter(textWithCaret: String) {
        assertOpensAlpha(targets(textWithCaret))
        assertOpensAlpha(injectedTargets(textWithCaret))
    }

    fun testIsRegistered() {
        assertTrue(GotoDeclarationHandler.EP_NAME.extensionList.any { it is MarpThemeGotoDeclarationHandler })
    }

    fun testFrontMatterIsInjectedInTests() {
        assertNotNull("YAML is injected into the front matter, is the YAML plugin loaded in tests?", injectedTargets("---\nmarp: true\nx<caret>y: 1\n---\n"))
    }

    fun testThemeNameInTheFrontMatter() {
        assertOpensAlphaFromFrontMatter("---\nmarp: true\ntheme: al<caret>pha\n---\n")
    }

    fun testEdgesOfTheThemeNameInTheFrontMatter() {
        assertOpensAlphaFromFrontMatter("---\nmarp: true\ntheme: <caret>alpha\n---\n")
        assertOpensAlphaFromFrontMatter("---\nmarp: true\ntheme: alpha<caret>\n---\n")
    }

    fun testQuotedThemeNameInTheFrontMatter() {
        assertOpensAlphaFromFrontMatter("---\nmarp: true\ntheme: \"al<caret>pha\"\n---\n")
        assertOpensAlphaFromFrontMatter("---\nmarp: true\ntheme: 'alpha'<caret>\n---\n")
    }

    fun testThemeNameInAComment() {
        assertOpensAlpha(targets("$deck<!-- theme: al<caret>pha -->"))
        assertOpensAlpha(targets("$deck<!-- theme: alpha<caret> -->"))
    }

    fun testThemeNameInAMultiLineComment() {
        assertOpensAlpha(targets("$deck<!--\npaginate: true\ntheme: al<caret>pha\n-->"))
    }

    fun testThemeNameInAnInlineComment() {
        assertOpensAlpha(targets("${deck}Text <!-- theme: al<caret>pha --> more"))
    }

    fun testNothingOnTheKeyOrOutsideTheValue() {
        assertEmpty(targets("---\nmarp: true\nth<caret>eme: alpha\n---\n"))
        assertEmpty(targets("---\nmarp: true\ntheme:<caret> alpha\n---\n"))
        assertEmpty(targets("${deck}<!-- th<caret>eme: alpha -->"))
        assertEmpty(targets("${deck}<!-- theme: alpha --> te<caret>xt"))
        assertEmpty(targets("${deck}te<caret>xt"))
    }

    fun testNothingOnOtherDirectives() {
        assertEmpty(targets("---\nmarp: true\nclass: al<caret>pha\n---\n"))
        assertEmpty(targets("${deck}<!-- class: al<caret>pha -->"))
    }

    fun testNothingOnASpotThemeThatMarpIgnores() {
        assertEmpty(targets("---\nmarp: true\n_theme: al<caret>pha\n---\n"))
        assertEmpty(targets("${deck}<!-- _theme: al<caret>pha -->"))
    }

    fun testNothingInACommentThatMarpReadsAsANote() {
        assertEmpty(targets("$deck<!--\ntheme: al<caret>pha\nSay hello\n-->"))
    }

    fun testNothingOnABlankValue() {
        assertEmpty(targets("---\nmarp: true\ntheme: <caret>\n---\n"))
    }

    fun testNothingOnABuiltInOrUnknownTheme() {
        assertEmpty(targets("---\nmarp: true\ntheme: ga<caret>ia\n---\n"))
        assertEmpty(targets("---\nmarp: true\ntheme: no<caret>pe\n---\n"))
        assertEmpty(targets("${deck}<!-- theme: uncov<caret>er -->"))
    }

    fun testThemeNamesAreCaseSensitive() {
        assertEmpty(targets("---\nmarp: true\ntheme: Al<caret>pha\n---\n"))
    }

    fun testNothingOnAThemeFromAUrl() {
        service.urlFetcher = { "/* @theme remote */" }
        settings("themes/alpha.css", "https://example.com/remote.css")
        load()
        assertEmpty(targets("---\nmarp: true\ntheme: rem<caret>ote\n---\n"))
        assertOpensAlpha(targets("---\nmarp: true\ntheme: al<caret>pha\n---\n"))
    }

    fun testNothingInPlainMarkdown() {
        assertEmpty(targets("---\ntheme: al<caret>pha\n---\n"))
        assertEmpty(targets("# Not a deck\n\n<!-- theme: al<caret>pha -->"))
    }

    fun testNothingWhileTheThemesAreNotLoaded() {
        // A download that has not finished keeps the set from loading (a changed setting starts loading it right away).
        val release = CountDownLatch(1)
        service.urlFetcher = { release.await(30, TimeUnit.SECONDS); "/* @theme remote */" }
        try {
            settings("themes/alpha.css", "https://example.com/remote.css")
            assertEmpty(targets("---\nmarp: true\ntheme: al<caret>pha\n---\n"))
        } finally {
            release.countDown()
        }
        load()
        assertOpensAlpha(targets("---\nmarp: true\ntheme: al<caret>pha\n---\n"))
    }

    fun testTheLastFileThatDeclaresTheNameWins() {
        val second = write("themes/alpha2.css", "/* @theme alpha */")
        settings("themes/alpha.css", "themes/alpha2.css")
        load()
        val targets = targets("---\nmarp: true\ntheme: al<caret>pha\n---\n")
        assertEquals(1, targets.size)
        assertEquals(second, targets.single().containingFile.virtualFile)
    }

    fun testACustomThemeNamedLikeABuiltInOneWins() {
        val gaia = write("themes/gaia.css", "/* @theme gaia */")
        settings("themes/gaia.css")
        load()
        val targets = targets("---\nmarp: true\ntheme: ga<caret>ia\n---\n")
        assertEquals(1, targets.size)
        assertEquals(gaia, targets.single().containingFile.virtualFile)
    }

    fun testNothingInAnUntrustedProject() {
        service.trustedProvider = { false }
        settings("themes/alpha.css")
        load()
        assertEmpty(targets("---\nmarp: true\ntheme: al<caret>pha\n---\n"))
    }
}
