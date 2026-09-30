package cz.p3kj.marp.directives

import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.platform.backend.documentation.DocumentationResult
import cz.p3kj.marp.MarpLightTestCase

class MarpDirectiveDocumentationTest : MarpLightTestCase() {

    private val deck = "---\nmarp: true\n---\n\n"
    private val provider = MarpDirectiveDocumentationTargetProvider()

    /** The directive names documented at the caret, or an empty list. */
    private fun targetsAt(textWithCaret: String): List<String> {
        myFixture.configureByText("deck.md", textWithCaret)
        return provider.documentationTargets(myFixture.file, myFixture.caretOffset)
            .map { (it as MarpDirectiveDocumentationTarget).directive.name }
    }

    fun testInsideASpotDirectiveKey() {
        assertEquals(listOf("paginate"), targetsAt("$deck<!-- _pag<caret>inate: true -->"))
    }

    fun testInsideAGlobalDirectiveKey() {
        assertEquals(listOf("theme"), targetsAt("$deck<!-- th<caret>eme: gaia -->"))
    }

    fun testAtTheEdgesOfTheKey() {
        assertEquals(listOf("class"), targetsAt("$deck<!-- <caret>class: lead -->"))
        assertEquals(listOf("class"), targetsAt("$deck<!-- class<caret>: lead -->"))
    }

    fun testGlobalDirectiveWithUnderscoreIsDocumentedToo() {
        assertEquals(listOf("theme"), targetsAt("$deck<!-- _th<caret>eme: gaia -->"))
    }

    fun testSecondKeyOfAMultiLineComment() {
        assertEquals(listOf("paginate"), targetsAt("$deck<!--\n_class: lead\npagi<caret>nate: true\n-->"))
    }

    fun testInlineCommentInAParagraph() {
        assertEquals(listOf("header"), targetsAt("${deck}Text <!-- hea<caret>der: Hi --> more"))
    }

    fun testNoneOnTheValue() {
        assertEmpty(targetsAt("$deck<!-- paginate: tr<caret>ue -->"))
    }

    fun testNoneOnUnknownKeysAndNotes() {
        assertEmpty(targetsAt("$deck<!-- No<caret>te: hello -->"))
        assertEmpty(targetsAt("$deck<!-- Say he<caret>llo, then paginate -->"))
    }

    fun testNoneOutsideAComment() {
        assertEmpty(targetsAt("${deck}pagi<caret>nate: true"))
    }

    fun testNoneInPlainMarkdown() {
        assertEmpty(targetsAt("# Not a deck\n\n<!-- _pagi<caret>nate: true -->"))
    }

    // Front matter ----------------------------------------------------------------------------------------------------
    //
    // With the caret in the front matter the fixture hands out the injected YAML file and an offset in it, so
    // `targetsAt` above already asks the way the IDE does for the injected file. These go through the host file and
    // the injected file on purpose, whatever the fixture returns.

    private fun hostOf(textWithCaret: String): Pair<com.intellij.psi.PsiFile, Int> {
        myFixture.configureByText("deck.md", textWithCaret)
        return MarpFrontMatter.hostOf(myFixture.file, myFixture.caretOffset)
    }

    private fun namesAt(file: com.intellij.psi.PsiFile, offset: Int): List<String> =
        provider.documentationTargets(file, offset).map { (it as MarpDirectiveDocumentationTarget).directive.name }

    /** Asks about the Markdown file with the offset in the document. */
    private fun hostTargetsAt(textWithCaret: String): List<String> {
        val (host, offset) = hostOf(textWithCaret)
        return namesAt(host, offset)
    }

    /** Asks about the injected YAML file with the offset in it, `null` when there is no injection. */
    private fun injectedTargetsAt(textWithCaret: String): List<String>? {
        val (host, offset) = hostOf(textWithCaret)
        val injected = InjectedLanguageManager.getInstance(project).findInjectedElementAt(host, offset)?.containingFile ?: return null
        val injectedOffset = offset - InjectedLanguageManager.getInstance(project).injectedToHost(injected, 0)
        return namesAt(injected, injectedOffset)
    }

    private fun assertFrontMatterTargets(expected: List<String>, textWithCaret: String) {
        assertEquals("host file", expected, hostTargetsAt(textWithCaret))
        assertEquals("injected file", expected, injectedTargetsAt(textWithCaret))
        assertEquals("fixture file", expected, targetsAt(textWithCaret))
    }

    fun testFrontMatterIsInjectedInTests() {
        assertNotNull("YAML is injected into the front matter, is the YAML plugin loaded in tests?", injectedTargetsAt("---\nmarp: true\nx<caret>y: 1\n---\n"))
    }

    fun testFrontMatterKey() {
        assertFrontMatterTargets(listOf("paginate"), "---\nmarp: true\npag<caret>inate: true\n---\n")
        assertFrontMatterTargets(listOf("theme"), "---\nmarp: true\ntheme<caret>: gaia\n---\n")
        assertFrontMatterTargets(listOf("class"), "---\nmarp: true\n<caret>class: lead\n---\n")
    }

    fun testFrontMatterMarpKey() {
        assertFrontMatterTargets(listOf("marp"), "---\nma<caret>rp: true\n---\n")
    }

    fun testFrontMatterSpotAndUnderscoreKeys() {
        assertFrontMatterTargets(listOf("class"), "---\nmarp: true\n_cl<caret>ass: lead\n---\n")
        assertFrontMatterTargets(listOf("theme"), "---\nmarp: true\n_th<caret>eme: gaia\n---\n")
    }

    fun testFrontMatterKeyAfterAnEntryWithAList() {
        assertFrontMatterTargets(listOf("paginate"), "---\nmarp: true\nimages:\n  - a.png\npagi<caret>nate: true\n---\n")
    }

    fun testNoneOnFrontMatterValuesAndOtherKeys() {
        assertFrontMatterTargets(emptyList(), "---\nmarp: true\npaginate: tr<caret>ue\n---\n")
        assertFrontMatterTargets(emptyList(), "---\nmarp: true\nti<caret>tle: Deck\n---\n")
        assertFrontMatterTargets(emptyList(), "---\nmarp: true\nimages:\n  - pagi<caret>nate\n---\n")
    }

    fun testFrontMatterKeysAreNotDocumentedInAComment() {
        assertEmpty(targetsAt("---\nmarp: true\n---\n\n<!-- ma<caret>rp: true -->"))
    }

    fun testNoneInTheFrontMatterOfAPlainMarkdownFile() {
        assertFrontMatterTargets(emptyList(), "---\ntitle: Post\npagi<caret>nate: true\n---\n")
        assertFrontMatterTargets(emptyList(), "---\nmarp: false\npagi<caret>nate: true\n---\n")
    }

    fun testNoneOnTheFenceLinesAndAfterTheFrontMatter() {
        assertEmpty(targetsAt("---\nmarp: true\n--<caret>-\n"))
        assertEmpty(targetsAt("---\nmarp: true\n---\n\npagi<caret>nate: true\n"))
    }

    fun testTheIdeAsksTheRegisteredProviderInTheFrontMatter() {
        myFixture.configureByText("deck.md", "---\nmarp: true\npag<caret>inate: true\n---\n")
        val targets = IdeDocumentationTargetProvider.getInstance(project).documentationTargets(myFixture.editor, myFixture.file, myFixture.caretOffset)
        assertEquals("paginate", (targets.first() as MarpDirectiveDocumentationTarget).directive.name)
    }

    fun testHtmlOfTheMarpKey() {
        val html = MarpDirectiveDocs.html(MarpDirectiveCatalog.MARP)
        assertTrue(html, html.contains("Marp for VS Code"))
        assertTrue(html, html.contains("Front matter only"))
        assertTrue(html, html.contains("https://github.com/marp-team/marp-vscode"))
        assertTrue(html, html.contains("<code>true</code>"))
        assertEquals("marp: front matter", MarpDirectiveDocs.hint(MarpDirectiveCatalog.MARP))
    }

    fun testTheIdeAsksTheRegisteredProvider() {
        myFixture.configureByText("deck.md", "$deck<!-- _pag<caret>inate: true -->")
        val targets = IdeDocumentationTargetProvider.getInstance(project).documentationTargets(myFixture.editor, myFixture.file, myFixture.caretOffset)
        assertEquals(listOf("paginate"), targets.map { (it as MarpDirectiveDocumentationTarget).directive.name })
    }

    fun testTargetPresentsTheDocumentation() {
        val target = MarpDirectiveDocumentationTarget(MarpDirectiveCatalog.find("paginate")!!)
        assertEquals("paginate", target.computePresentation().presentableText)
        assertEquals("paginate: local", target.computeDocumentationHint())
        assertSame(target, target.createPointer().dereference())
        assertTrue(target.computeDocumentation() is DocumentationResult.Documentation)
    }

    fun testHtmlOfALocalDirective() {
        val html = MarpDirectiveDocs.html(MarpDirectiveCatalog.find("paginate")!!)
        assertTrue(html, html.contains("<code>hold</code> shows it without counting the slide"))
        assertTrue(html, html.contains("Local directive"))
        assertTrue(html, html.contains("<code>skip</code>"))
        assertTrue(html, html.contains("https://marpit.marp.app/directives"))
    }

    fun testHtmlOfAGlobalMarpCoreDirective() {
        val html = MarpDirectiveDocs.html(MarpDirectiveCatalog.find("math")!!)
        assertTrue(html, html.contains("Global directive"))
        assertTrue(html, html.contains("<code>katex</code>"))
        assertTrue(html, html.contains("marp-core"))
    }

    fun testHtmlOfThemeListsTheBuiltInThemes() {
        val html = MarpDirectiveDocs.html(MarpDirectiveCatalog.find("theme")!!)
        assertTrue(html, html.contains("<code>gaia</code>"))
        assertTrue(html, html.contains("<code>uncover</code>"))
    }

    fun testEveryDirectiveHasDocumentationWithoutRawKeys() {
        for (directive in MarpDirectiveCatalog.ALL + MarpDirectiveCatalog.FRONT_MATTER_ONLY) {
            val html = MarpDirectiveDocs.html(directive)
            assertTrue(directive.name, html.contains(directive.name))
            assertFalse(directive.name, html.contains("directive.doc."))
            assertFalse(directive.name, html.contains("!!"))
        }
    }
}
