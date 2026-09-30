package cz.p3kj.marp.slides

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import cz.p3kj.marp.MarpLightTestCase
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownFile

/**
 * Checks the Markdown PSI adapter on the constructs that decide where Marpit starts a new slide: which `---` lines are
 * slide breaks, which are code, setext underlines, comments or front matter.
 */
class MarpSlideParserTest : MarpLightTestCase() {

    private val front = "---\nmarp: true\n---\n"

    private fun deck(text: String): MarpDeck {
        myFixture.configureByText("deck.md", text)
        return MarpSlideParser.deck(myFixture.file as MarkdownFile)
    }

    /** Slide titles, "-" for slides without one. */
    private fun titles(text: String): List<String> = deck(text).slides.map { it.title ?: "-" }

    fun testThematicBreaksSplit() {
        for (separator in listOf("---", "***", "___", "- - -", "  ---", "-----", "* * *")) {
            assertEquals("'$separator'", listOf("A", "B"), titles("$front# A\n\n$separator\n\n# B\n"))
        }
    }

    fun testSlideOffsets() {
        val text = "$front# A\n\nHello\n\n---\n\n# B\n"
        val deck = deck(text)
        assertEquals(2, deck.slides.size)
        val first = deck.slides[0]
        assertEquals(0, first.startOffset)
        assertEquals(front.length, first.bodyOffset)
        assertEquals(text.indexOf("# A"), first.contentOffset)
        val second = deck.slides[1]
        assertEquals(text.indexOf("---", front.length), second.startOffset)
        assertEquals(text.indexOf("# B"), second.contentOffset)
        assertEquals(text.length, second.endOffset)
        assertEquals(listOf(MarpHeading(1, "B", text.indexOf("# B"))), second.headings)
        assertEquals(1, deck.slideIndexAt(text.indexOf("---", front.length) + 1))
    }

    fun testBreaksInsideCodeDoNotSplit() {
        assertEquals(listOf("A"), titles("$front# A\n\n```\n---\n***\n```\n\n~~~md\n---\n~~~\n\ntext\n"))
        assertEquals(listOf("A"), titles("$front# A\n\n````\n```\n---\n```\n````\n"))
        assertEquals(listOf("A"), titles("$front# A\n\n    ---\n\n    ***\n\ntext\n"))
        assertEquals(listOf("A"), titles("$front# A\n\n`---`\n"))
    }

    fun testUnclosedFenceSwallowsTheRest() {
        assertEquals(listOf("A"), titles("$front# A\n\n```\n---\n# not a heading\n"))
    }

    fun testSetextHeadingsAreHeadingsNotBreaks() {
        val two = deck("${front}Title\n---\n\nText\n")
        assertEquals(1, two.slides.size)
        assertEquals("Title", two.slides[0].title)
        assertEquals(2, two.slides[0].headings.single().level)

        val one = deck("${front}Title\n===\n\nText\n")
        assertEquals(1, one.slides.size)
        assertEquals(1, one.slides[0].headings.single().level)
        assertEquals("Title", one.slides[0].title)

        // A blank line makes it a break again, and "Title" is a plain paragraph.
        assertEquals(listOf("-", "-"), titles("${front}Title\n\n---\nText\n"))
    }

    // The Markdown plugin builds a setext heading from any single line above `---`, markdown-it only from paragraph lines.

    fun testAtxHeadingDirectlyAboveABreakIsAHeadingAndABreak() {
        val text = "$front\n# A\n---\n# B\n"
        val deck = deck(text)
        assertEquals(listOf("A", "B"), deck.slides.map { it.title })
        assertEquals(listOf(1), deck.slides[0].headings.map { it.level })
        assertEquals(text.indexOf("---", front.length), deck.slides[1].startOffset)
        assertEquals(text.indexOf("# B"), deck.slides[1].contentOffset)
        assertEquals(listOf("A", "B"), titles("$front# A\n***\n# B\n"))
        assertEquals(listOf("A", "B"), titles("$front# A\n- - -\n# B\n"))
        // Closing markers and the level come from the ATX line, not from the setext underline.
        val closed = deck("$front## A ##\n---\n# B\n")
        assertEquals(listOf("A", "B"), closed.slides.map { it.title })
        assertEquals(listOf(2), closed.slides[0].headings.map { it.level })
    }

    fun testATitleWithMarkupAboveABreakKeepsTheTitleClean() {
        assertEquals(listOf("Bold title", "Link text"), titles("$front# **Bold** title\n---\n# [Link](x) text\n"))
        assertEquals(listOf("snake_case", "B"), titles("$front# snake_case\n---\n# B\n"))
        assertEquals(listOf("#hashtag"), titles("$front# #hashtag\n"))
    }

    fun testABreakDirectlyAboveABreakIsTwoBreaks() {
        val text = "$front\n# A\n\n---\n---\n\n# B\n"
        assertEquals(listOf("A", "-", "B"), titles(text))
        assertEquals(listOf("A", "-", "B"), titles("$front# A\n\n***\n---\n\n# B\n"))
        val deck = deck(text)
        assertEquals(text.indexOf("---", front.length), deck.slides[1].startOffset)
        assertEquals(text.indexOf("---", text.indexOf("---", front.length) + 1), deck.slides[2].startOffset)
    }

    fun testABreakDirectlyAfterTheFrontMatterAndAnotherBreakGiveTwoEmptySlides() {
        assertEquals(listOf("-", "-", "B"), titles("$front---\n---\n\n# B\n"))
    }

    fun testThreeBreaksInARowGiveFourSlides() {
        assertEquals(listOf("A", "-", "-", "B"), titles("$front\n# A\n\n---\n---\n---\n\n# B\n"))
    }

    fun testParagraphAboveABreakIsStillASetextHeading() {
        assertEquals(listOf("text"), titles("$front\ntext\n---\n# B\n"))
        assertEquals(1, deck("$front\ntext\n---\n\nB\n").slides.size)
    }

    fun testATxHeadingAboveASetextEqualsLineHasTheLevelOfTheAtxLine() {
        val one = deck("$front# A\n===\n\ntext\n")
        assertEquals(1, one.slides.size)
        assertEquals(listOf(MarpHeading(1, "A", front.length)), one.slides[0].headings)
        val two = deck("$front## A\n===\n")
        assertEquals(listOf(2), two.slides.single().headings.map { it.level })
        assertEquals("A", two.slides.single().title)
    }

    fun testHeadingDividerUsesTheLevelOfAnAtxLineAboveABreak() {
        val divider = "---\nmarp: true\nheadingDivider: 2\n---\n"
        // Marp: [text] [## A] [text] [## B], the break after "## A" starts the third slide.
        val deck = deck("${divider}text\n\n## A\n---\ntext\n\n## B\n")
        assertEquals(listOf("-", "A", "-", "B"), deck.slides.map { it.title ?: "-" })
        assertEquals(listOf("-", "A", "-", "B"), titles("${divider}text\n\n## A ##\n---\ntext\n\n## B\n"))
        // A level that is not a divider level starts no slide, the break still does.
        assertEquals(listOf("A", "-"), titles("${divider}text\n\n### A\n---\ntext\n"))
    }

    fun testMisreadSetextInsideAQuoteIsNotABreak() {
        val divider = "---\nmarp: true\nheadingDivider: 2\n---\n"
        // Marp: the quote holds a heading and a rule, neither is a top-level break.
        assertEquals(1, deck("$front> # A\n> ---\n\n# B\n").slides.size)
        assertEquals(1, deck("$front> ---\n> ---\n\n# B\n").slides.size)
        // The ATX line in the quote divides at its own level.
        assertEquals(listOf("-", "Quoted"), titles("${divider}text\n\n> ## Quoted\n> ---\n"))
        // A rule in the quote is no heading.
        assertEquals(listOf("-"), titles("${divider}text\n\n> ---\n> ---\n"))
    }

    fun testABreakAboveAnEqualsLineIsABreak() {
        // markdown-it: a rule, then the paragraph "===".
        assertEquals(listOf("A", "-"), titles("$front# A\n\n---\n===\n\ntext\n"))
    }

    fun testBreaksInBlockquotesAndListsDoNotSplit() {
        assertEquals(listOf("A"), titles("$front# A\n\n> ---\n\n> quote\n> ---\n"))
        assertEquals(listOf("A"), titles("$front# A\n\n- item\n\n  ---\n\n- other\n"))
    }

    fun testBreaksInsideHtmlDoNotSplit() {
        assertEquals(listOf("A"), titles("$front# A\n\n<!--\nnote\n---\n-->\n\ntext\n"))
        assertEquals(listOf("A"), titles("$front# A\n\n<style>\n---\n</style>\n"))
        assertEquals(listOf("A"), titles("$front# A\n\n<div>\n---\n</div>\n"))
    }

    fun testFrontMatterIsNeitherBreakNorHeading() {
        val text = "---\nmarp: true\ntheme: gaia\n---\n"
        val deck = deck(text)
        assertEquals(1, deck.slides.size)
        assertTrue(deck.slides[0].headings.isEmpty())
        assertEquals(text.length, deck.slides[0].endOffset)

        // Closed with `...`, and with text after the closing fence.
        assertEquals(listOf("A"), titles("---\nmarp: true\n...\n# A\n"))
        assertEquals(listOf("A", "B"), titles("---\nmarp: true\n---\n# A\n\n---\n\n# B\n"))
    }

    fun testFrontMatterCommentLikeTextIsIgnored() {
        // A `# comment` line in the YAML is not a heading.
        assertEquals(listOf("A"), titles("---\nmarp: true\n# not a heading\n---\n# A\n"))
    }

    fun testBreakRightAfterFrontMatterLeavesAnEmptyFirstSlide() {
        val deck = deck("$front---\n# B\n")
        assertEquals(listOf(null, "B"), deck.slides.map { it.title })
        assertEquals(front.length, deck.slides[1].startOffset)
    }

    fun testEmptyDeck() {
        assertEquals(1, deck("").slides.size)
        assertEquals(1, deck(front).slides.size)
        assertEquals(listOf("-", "-"), titles("$front\n---\n"))
    }

    fun testHeadingDividerInFrontMatter() {
        val text = "---\nmarp: true\nheadingDivider: 2\n---\n# A\n\ntext\n\n## B\n\n### C\n\n# D\n"
        val deck = deck(text)
        assertEquals(setOf(1, 2), deck.headingDivider)
        assertEquals(listOf("A", "B", "D"), deck.slides.map { it.title })
        assertEquals(listOf("B", "C"), deck.slides[1].headings.map { it.text })
        assertEquals(text.indexOf("## B"), deck.slides[1].startOffset)
    }

    fun testHeadingDividerList() {
        val text = "---\nmarp: true\nheadingDivider: [1, 3]\n---\n# A\n\n## B\n\n### C\n\n# D\n"
        assertEquals(listOf("A", "C", "D"), titles(text))
        // Block sequence and a quoted number (a YAML string) mean the same as Marpit reads them.
        assertEquals(listOf("A", "C", "D"), titles("---\nmarp: true\nheadingDivider:\n  - 1\n  - 3\n---\n# A\n\n## B\n\n### C\n\n# D\n"))
        assertEquals(listOf("A", "B"), titles("---\nmarp: true\nheadingDivider: \"2\"\n---\n# A\n\n## B\n\n### C\n"))
    }

    fun testHeadingDividerComment() {
        assertEquals(listOf("A", "B"), titles("$front# A\n\nText\n\n# B\n\n<!-- headingDivider: 1 -->\n"))
        assertEquals(listOf("A", "B"), titles("$front<!-- headingDivider: 1 -->\n# A\n\n# B\n"))
        assertEquals(listOf("A", "B"), titles("$front<!--\nheadingDivider: 1\n-->\n# A\n\n# B\n"))
    }

    fun testHeadingDividerFalseOverridesFrontMatter() {
        val text = "---\nmarp: true\nheadingDivider: 1\n---\n# A\n\n# B\n\n<!-- headingDivider: false -->\n"
        assertEquals(listOf("A"), titles(text))
        assertEquals(listOf("A", "B"), titles("---\nmarp: true\nheadingDivider: 1\n---\n# A\n\n# B\n"))
    }

    fun testHeadingDividerInlineComment() {
        assertEquals(listOf("A", "B"), titles("$front# A\n\nHello <!-- headingDivider: 1 --> world\n\n# B\n"))
    }

    fun testDividerHeadingStartsNoSlideWhenOnlyHiddenBlocksPrecedeIt() {
        val divider = "---\nmarp: true\nheadingDivider: 1\n---\n"
        assertEquals(listOf("One", "Two"), titles("$divider<!-- note -->\n\n# One\n\n# Two\n"))
        // Reference definitions produce no output in Marpit.
        assertEquals(listOf("One", "Two"), titles("$divider[a]: http://example.com\n\n# One\n\n# Two\n"))
        // Text does.
        assertEquals(listOf("-", "One"), titles("${divider}Intro\n\n# One\n"))
        // So does a real break.
        assertEquals(listOf("One", "-", "Two"), titles("$divider# One\n\n---\n# Two\n"))
    }

    fun testStyleBlocksAreNotVisibleContent() {
        val divider = "---\nmarp: true\nheadingDivider: 1\n---\n"
        // Marpit hides a block-level <style> (marpit_style), so the first heading after it starts no slide of its own.
        assertEquals(listOf("One", "Two"), titles("$divider<style>\nh1 { color: red }\n</style>\n\n# One\n\n# Two\n"))
        assertEquals(listOf("One"), titles("$divider<style scoped>\nh1 { color: red }\n</style>\n# One\n"))
        assertEquals(listOf("One"), titles("$divider<style>h1 { color: red }</style>\n\n# One\n"))
        // Other HTML is visible.
        assertEquals(listOf("-", "One"), titles("$divider<div>hi</div>\n\n# One\n"))
        assertEquals(listOf("-", "One"), titles("$divider<styled>hi</styled>\n\n# One\n"))
    }

    fun testHeadingsInBlockquotesDivideToo() {
        val divider = "---\nmarp: true\nheadingDivider: 2\n---\n"
        assertEquals(listOf("-", "Quoted"), titles("${divider}Text\n\n> ## Quoted\n"))
    }

    fun testHeadingTextIsPlainText() {
        val deck = deck("$front#   Spaced    out  #\n\n## Second\n")
        assertEquals(listOf("Spaced out", "Second"), deck.slides[0].headings.map { it.text })
    }

    fun testTitlesLeaveOutInlineMarkup() {
        val cases = mapOf(
            "# <!-- fit --> Title" to "Title",
            "# Title <!--fit-->" to "Title",
            "# Ti<!-- x -->tle" to "Title",
            "# **Bold** title" to "Bold title",
            "# *Em* and __strong__ and ~~gone~~" to "Em and strong and gone",
            "# _Em_ text" to "Em text",
            "# Use `code` here" to "Use code here",
            "# [Link](http://example.com) text" to "Link text",
            "# [Link **bold**](x \"title\") text" to "Link bold text",
            "# [Ref][r] text\n\n[r]: http://example.com" to "Ref text",
            "# ![image](x.png) Text" to "Text",
            "# Text <b>with</b> tags" to "Text with tags",
            "# snake_case_name" to "snake_case_name",
            "# 2 * 3 = 6" to "2 * 3 = 6",
            "Setext **bold**\n---" to "Setext bold",
        )
        for ((source, title) in cases) {
            assertEquals(source, listOf(title), titles("$front$source\n"))
        }
        assertEquals(listOf("-"), titles("$front# <!-- fit -->\n"))
        assertEquals(listOf("-"), titles("$front# ![only](x.png)\n"))
    }

    fun testEmptyHeadingHasNoTitle() {
        val deck = deck("$front#\n\ntext\n")
        assertEquals(1, deck.slides.size)
        assertNull(deck.slides[0].title)
        assertEquals(1, deck.slides[0].headings.size)
    }

    fun testDeckIsCachedUntilTheFileChanges() {
        val text = "$front# A\n"
        val first = deck(text)
        val file = myFixture.file as MarkdownFile
        assertSame(first, MarpSlideParser.deck(file))
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(text.length, "\n---\n\n# B\n") }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val second = MarpSlideParser.deck(file)
        assertNotSame(first, second)
        assertEquals(listOf("A", "B"), second.slides.map { it.title })
    }

    fun testLargeDeckIsParsedQuickly() {
        val slide = "# Slide\n\nSome text\n\n- a\n- b\n\n---\n\n"
        val text = front + slide.repeat(2_000)
        val started = System.nanoTime()
        val deck = deck(text)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(2_001, deck.slides.size)
        assertTrue("took $elapsedMs ms", elapsedMs < 5_000)
    }
}
