package cz.p3kj.marp.directives

import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
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
        for (directive in MarpDirectiveCatalog.ALL) {
            val html = MarpDirectiveDocs.html(directive)
            assertTrue(directive.name, html.contains(directive.name))
            assertFalse(directive.name, html.contains("directive.doc."))
            assertFalse(directive.name, html.contains("!!"))
        }
    }
}
