package cz.p3kj.marp.images

import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.platform.backend.documentation.DocumentationResult
import cz.p3kj.marp.MarpLightTestCase

class MarpImageDocumentationTest : MarpLightTestCase() {

    private val deck = "---\nmarp: true\n---\n\n"
    private val provider = MarpImageDocumentationTargetProvider()

    /** The keyword names documented at the caret, or an empty list. */
    private fun targetsAt(textWithCaret: String): List<String> {
        myFixture.configureByText("deck.md", textWithCaret)
        return provider.documentationTargets(myFixture.file, myFixture.caretOffset)
            .map { (it as MarpImageDocumentationTarget).keyword.name }
    }

    fun testInsideABackgroundKeyword() {
        assertEquals(listOf("bg"), targetsAt("$deck![b<caret>g](x.png)"))
    }

    fun testASizeOnAKeywordWithASize() {
        assertEquals(listOf("left"), targetsAt("$deck![bg left:4<caret>0%](x.png)"))
        assertEquals(listOf("left"), targetsAt("$deck![bg le<caret>ft:40%](x.png)"))
    }

    fun testAPercentage() {
        assertEquals(listOf("N%"), targetsAt("$deck![bg 8<caret>0%](x.png)"))
    }

    fun testASizeKeyword() {
        assertEquals(listOf("w"), targetsAt("$deck![w:4<caret>00](x.png)"))
        assertEquals(listOf("height"), targetsAt("$deck![height:<caret>auto](x.png)"))
    }

    fun testAFilter() {
        assertEquals(listOf("drop-shadow"), targetsAt("$deck![drop-shadow:0,5px,<caret>10px](x.png)"))
    }

    fun testAtTheEdgesOfTheWord() {
        assertEquals(listOf("bg"), targetsAt("$deck![bg<caret>](x.png)"))
        assertEquals(listOf("bg"), targetsAt("$deck![<caret>bg](x.png)"))
        assertEquals(listOf("blur"), targetsAt("$deck![bg <caret>blur](x.png)"))
    }

    fun testNoneOnADescription() {
        assertEmpty(targetsAt("$deck![A pho<caret>to](x.png)"))
    }

    fun testNoneOutsideTheAltText() {
        assertEmpty(targetsAt("$deck![bg](x<caret>.png)"))
        assertEmpty(targetsAt("${deck}bg<caret>"))
        assertEmpty(targetsAt("$deck[bg<caret>](x.png)"))
    }

    fun testNoneInCodeAndHtml() {
        assertEmpty(targetsAt("$deck```md\n![b<caret>g](x.png)\n```"))
        assertEmpty(targetsAt("$deck`![b<caret>g](x.png)`"))
        assertEmpty(targetsAt("$deck<!-- ![b<caret>g](x.png) -->"))
        assertEmpty(targetsAt("$deck    ![b<caret>g](x.png)"))
    }

    fun testNoneInTheFrontMatter() {
        assertEmpty(targetsAt("---\nmarp: true\ntitle: ![b<caret>g](x.png)\n---\n"))
    }

    fun testNoneInPlainMarkdown() {
        assertEmpty(targetsAt("# Not a deck\n\n![b<caret>g](x.png)"))
    }

    fun testTheIdeAsksTheRegisteredProvider() {
        myFixture.configureByText("deck.md", "$deck![bg blu<caret>r](x.png)")
        val targets = IdeDocumentationTargetProvider.getInstance(project).documentationTargets(myFixture.editor, myFixture.file, myFixture.caretOffset)
        assertEquals(listOf("blur"), targets.map { (it as MarpImageDocumentationTarget).keyword.name })
    }

    fun testTargetPresentsTheDocumentation() {
        val target = MarpImageDocumentationTarget(MarpImageKeywordCatalog.resolve("blur")!!)
        assertEquals("blur", target.computePresentation().presentableText)
        assertEquals("blur: filter", target.computeDocumentationHint())
        assertSame(target, target.createPointer().dereference())
        assertTrue(target.computeDocumentation() is DocumentationResult.Documentation)
        assertEquals(target, MarpImageDocumentationTarget(MarpImageKeywordCatalog.resolve("blur:5px")!!))
    }

    fun testHints() {
        assertEquals("bg: background", MarpImageDocs.hint(MarpImageKeywordCatalog.BG))
        assertEquals("w:: size", MarpImageDocs.hint(MarpImageKeywordCatalog.resolve("w")!!))
        assertEquals("N%: background", MarpImageDocs.hint(MarpImageKeywordCatalog.PERCENTAGE))
    }

    fun testHtmlOfAFilterShowsTheDefault() {
        val html = MarpImageDocs.html(MarpImageKeywordCatalog.resolve("blur")!!)
        assertTrue(html, html.contains("<code>10px</code>"))
        assertTrue(html, html.contains("CSS blur filter"))
        assertTrue(html, html.contains("https://marpit.marp.app/image-syntax"))
    }

    fun testHtmlOfASplitShowsTheDefaultSize() {
        val html = MarpImageDocs.html(MarpImageKeywordCatalog.resolve("left")!!)
        assertTrue(html, html.contains("<code>50%</code>"))
    }

    fun testHtmlOfAKeywordWithoutADefaultHasNoDefaultSection() {
        val html = MarpImageDocs.html(MarpImageKeywordCatalog.BG)
        assertFalse(html, html.contains("Default"))
        assertTrue(html, html.contains("slide background"))
    }
}
