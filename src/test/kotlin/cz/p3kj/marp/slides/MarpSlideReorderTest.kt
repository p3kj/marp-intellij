package cz.p3kj.marp.slides

import cz.p3kj.marp.MarpDetector
import cz.p3kj.marp.MarpLightTestCase
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownFile

/**
 * The slide swap on plain text. Every result is checked twice: the exact text, and a re-parse of it that must give the
 * expected slide order with the same number of slides (a lost or merged slide shows up there).
 */
class MarpSlideReorderTest : MarpLightTestCase() {

    private val front = "---\nmarp: true\n---\n"

    private fun deck(text: String): MarpDeck {
        myFixture.configureByText("deck.md", text)
        return MarpSlideParser.deck(myFixture.file as MarkdownFile)
    }

    /**
     * A deck for text that the Markdown parser does not read like CommonMark: it takes `# A` directly above `---` for a
     * setext heading and two `---` lines in a row for no break at all. Every `---` line after the front matter is a break here.
     */
    private fun handDeck(text: String): MarpDeck {
        val frontMatter = MarpDetector.findFrontMatter(text)
        val blocks = ArrayList<MarpBlock>()
        var offset = 0
        for (line in text.split("\n")) {
            when {
                offset < (frontMatter?.endOffset ?: 0) -> Unit
                line == "---" -> blocks += MarpBlock.Break(offset)
                line.startsWith("# ") -> blocks += MarpBlock.Heading(offset, 1, line.drop(2))
                line.isNotBlank() -> blocks += MarpBlock.Content(offset)
            }
            offset += line.length + 1
        }
        return MarpSlideSplitter.split(text, frontMatter, blocks)
    }

    private fun edit(text: String, index: Int, down: Boolean, parse: (String) -> MarpDeck = ::deck): MarpSlideReorder.Edit? =
        MarpSlideReorder.move(text, parse(text), index, down)

    /** [text] after moving slide [index], `null` when there is nothing to move. */
    private fun moved(text: String, index: Int, down: Boolean, parse: (String) -> MarpDeck = ::deck): String? =
        edit(text, index, down, parse)?.let { text.replaceRange(it.start, it.end, it.text) }

    /** Slide titles, "-" for slides without one. */
    private fun titles(text: String, parse: (String) -> MarpDeck = ::deck): List<String> =
        parse(text).slides.map { it.title ?: "-" }

    private fun assertMoved(
        expected: String, text: String, index: Int, down: Boolean, expectedTitles: List<String>,
        parse: (String) -> MarpDeck = ::deck,
    ) {
        val result = moved(text, index, down, parse)
        assertEquals(expected, result)
        assertEquals(expectedTitles, titles(result!!, parse))
    }

    private val threeSlides = "$front\n# One\n\n---\n\n# Two\n\n---\n\n# Three\n"
    private val fourSlides = "$front\n# One\n\n---\n\n# Two\n\n---\n\n# Three\n\n---\n\n# Four\n"

    // Slides after the first --------------------------------------------------------------------------------------------

    fun testSwapsTwoMiddleSlides() {
        assertMoved(
            "$front\n# One\n\n---\n\n# Three\n\n---\n\n# Two\n\n---\n\n# Four\n",
            fourSlides, 1, down = true, listOf("One", "Three", "Two", "Four"),
        )
        assertMoved(
            "$front\n# One\n\n---\n\n# Three\n\n---\n\n# Two\n\n---\n\n# Four\n",
            fourSlides, 2, down = false, listOf("One", "Three", "Two", "Four"),
        )
    }

    fun testSwapsTheLastTwoSlidesAndKeepsTheEndOfTheFile() {
        val expected = "$front\n# One\n\n---\n\n# Three\n\n---\n\n# Two\n"
        assertMoved(expected, threeSlides, 1, down = true, listOf("One", "Three", "Two"))
        assertMoved(expected, threeSlides, 2, down = false, listOf("One", "Three", "Two"))
    }

    fun testSeparatorsTravelWithTheirSlides() {
        val text = "$front\n# One\n\n***\n\n# Two\n\n___\n\n# Three\n\n---\n\n# Four\n"
        assertMoved(
            "$front\n# One\n\n___\n\n# Three\n\n***\n\n# Two\n\n---\n\n# Four\n",
            text, 1, down = true, listOf("One", "Three", "Two", "Four"),
        )
    }

    fun testDirectiveCommentsAndPresenterNotesMoveWithTheirSlide() {
        val text = "$front\n# One\n\n---\n\n<!-- _class: lead -->\n\n# Two\n\n<!-- say hello -->\n\n---\n\n# Three\n"
        assertMoved(
            "$front\n# One\n\n---\n\n# Three\n\n---\n\n<!-- _class: lead -->\n\n# Two\n\n<!-- say hello -->\n",
            text, 1, down = true, listOf("One", "Three", "Two"),
        )
    }

    fun testBreaksInsideCodeAreNotBoundaries() {
        val text = "$front\n# One\n\n---\n\n# Two\n\n```\n---\n```\n\n---\n\n# Three\n"
        assertMoved(
            "$front\n# One\n\n---\n\n# Three\n\n---\n\n# Two\n\n```\n---\n```\n",
            text, 1, down = true, listOf("One", "Three", "Two"),
        )
        assertMoved(
            "$front\n# One\n\n---\n\n# Three\n\n---\n\n# Two\n\n```\n---\n```\n",
            text, 2, down = false, listOf("One", "Three", "Two"),
        )
    }

    // The first slide and the front matter ------------------------------------------------------------------------------

    fun testFirstSlideDownKeepsTheFrontMatterAtTheTop() {
        val expected = "$front\n# Two\n\n---\n\n# One\n\n---\n\n# Three\n"
        assertMoved(expected, threeSlides, 0, down = true, listOf("Two", "One", "Three"))
        assertMoved(expected, threeSlides, 1, down = false, listOf("Two", "One", "Three"))
    }

    fun testSecondSlideUpWithTheLastSlide() {
        val text = "$front\n# One\n\n---\n\n# Two\n"
        assertMoved("$front\n# Two\n\n---\n\n# One\n", text, 1, down = false, listOf("Two", "One"))
    }

    fun testFirstSlideWithoutFrontMatterSwapsBodies() {
        // The blank line after the separator is part of the body and moves with it.
        assertMoved("\n# Two\n\n---\n# One\n", "# One\n\n---\n\n# Two\n", 0, down = true, listOf("Two", "One"))
    }

    fun testEmptyFirstSlideDown() {
        val text = "$front---\n\n# Two\n\n---\n\n# Three\n"
        assertEquals(listOf("-", "Two", "Three"), titles(text))
        assertMoved("$front\n# Two\n\n---\n\n---\n\n# Three\n", text, 0, down = true, listOf("Two", "-", "Three"))
        assertMoved("$front\n# Two\n\n---\n\n---\n\n# Three\n", text, 1, down = false, listOf("Two", "-", "Three"))
    }

    // The end of the file -----------------------------------------------------------------------------------------------

    fun testLastSlideWithoutTrailingNewlineStaysWithout() {
        val text = "$front\n# One\n\n---\n\n# Two\n\n---\n\n# Three"
        assertMoved("$front\n# One\n\n---\n\n# Three\n\n---\n\n# Two", text, 2, down = false, listOf("One", "Three", "Two"))
    }

    fun testLastSlideEndingInAParagraphGetsABlankLineBeforeTheNextSeparator() {
        val text = "$front\n# One\n\n---\n\n# Two\n\n---\n\n# Three\n\ntext\n"
        // Without the blank line "text" would turn the next `---` into a setext heading underline.
        assertMoved(
            "$front\n# One\n\n---\n\n# Three\n\ntext\n\n---\n\n# Two\n",
            text, 2, down = false, listOf("One", "Three", "Two"),
        )
    }

    fun testTightDeckGetsBlankLinesAroundMovedSlides() {
        val text = "$front# A\n---\n# B\n---\n# C\n"
        assertMoved("$front# A\n\n---\n# C\n\n---\n# B\n", text, 1, down = true, listOf("A", "C", "B"), ::handDeck)
    }

    fun testEmptyLastSlideMovesUp() {
        val text = "$front\n# One\n\n---\n\n# Two\n\n---\n"
        assertEquals(listOf("One", "Two", "-"), titles(text))
        assertMoved("$front\n# One\n\n---\n\n---\n\n# Two\n", text, 2, down = false, listOf("One", "-", "Two"))
    }

    fun testASeparatorMovedBelowAParagraphDoesNotBecomeASetextUnderline() {
        // `***` can follow a paragraph line directly, a moved-in `---` would make it a heading and merge the slides.
        val text = "$front\nOne para\n***\n\n# Two\n\n---\n\n# Three\n"
        assertEquals(listOf("-", "Two", "Three"), titles(text))
        val expected = "$front\nOne para\n\n---\n\n# Three\n\n***\n\n# Two\n"
        assertMoved(expected, text, 2, down = false, listOf("-", "Three", "Two"))
        assertMoved(expected, text, 1, down = true, listOf("-", "Three", "Two"))
        // The other way round the moved-in separator is `***` and the paragraph line is above it as well.
        val back = "$front\nOne para\n\n---\n\n# Three\n\n***\n\n# Two\n"
        assertMoved("$front\nOne para\n\n***\n\n# Two\n\n---\n\n# Three\n", back, 2, down = false, listOf("-", "Two", "Three"))
    }

    fun testNoBlankLineIsAddedBelowTheFrontMatterOrABlankLine() {
        val text = "$front---\n\n# Two\n\n---\n\n# Three\n"
        assertMoved("$front---\n\n# Three\n\n---\n\n# Two\n", text, 2, down = false, listOf("-", "Three", "Two"))
        assertMoved("$front---\n\n# Three\n\n---\n\n# Two\n", text, 1, down = true, listOf("-", "Three", "Two"))
    }

    fun testRoundTripsGiveTheOriginalText() {
        for (ending in listOf("\n", "", "\n\n")) {
            val text = fourSlides.trimEnd('\n') + ending
            for (index in 0..2) {
                val down = moved(text, index, down = true)!!
                assertEquals("index $index, ending '${ending.replace("\n", "\\n")}'", text, moved(down, index + 1, down = false))
            }
        }
    }

    // Nothing to do -----------------------------------------------------------------------------------------------------

    fun testFirstSlideCannotMoveUpAndLastCannotMoveDown() {
        assertNull(edit(threeSlides, 0, down = false))
        assertNull(edit(threeSlides, 2, down = true))
        assertNull(edit("$front\n# Only\n", 0, down = true))
        assertNull(edit("$front\n# Only\n", 0, down = false))
    }

    fun testIndexOutsideTheDeckMovesNothing() {
        assertNull(edit(threeSlides, 3, down = false))
        assertNull(edit(threeSlides, -1, down = true))
    }

    fun testHeadingDividerDecksAreNotSupported() {
        val text = "---\nmarp: true\nheadingDivider: 2\n---\n\n# A\n\n## B\n\n## C\n"
        val deck = deck(text)
        assertEquals(3, deck.slides.size)
        assertFalse(MarpSlideReorder.supports(deck))
        for (index in 0..2) {
            assertNull(MarpSlideReorder.move(text, deck, index, down = true))
            assertNull(MarpSlideReorder.move(text, deck, index, down = false))
        }
        assertTrue(MarpSlideReorder.supports(deck(threeSlides)))
    }

    // The caret ---------------------------------------------------------------------------------------------------------

    fun testCaretInsideTheMovedSlideKeepsItsPlace() {
        val text = threeSlides
        for (marker in listOf("# Two", "Two", "---\n\n# Two")) {
            val edit = edit(text, 1, down = true)!!
            val result = text.replaceRange(edit.start, edit.end, edit.text)
            assertEquals(marker, result.indexOf(marker, result.indexOf("# Three")), edit.caretAfter(text.indexOf(marker)))
        }
        val up = edit(text, 2, down = false)!!
        val result = text.replaceRange(up.start, up.end, up.text)
        assertEquals(result.indexOf("# Three") + 4, up.caretAfter(text.indexOf("# Three") + 4))
    }

    fun testCaretOutsideTheMovedPieceGoesToItsFirstNonBlankLine() {
        // First slide down: the front matter is outside the moved body, the caret goes to its content, inside it keeps its place.
        val down = edit(threeSlides, 0, down = true)!!
        val downResult = threeSlides.replaceRange(down.start, down.end, down.text)
        assertEquals(downResult.indexOf("# One"), down.caretAfter(0))
        assertEquals(downResult.indexOf("# One"), down.caretAfter(threeSlides.indexOf("# One")))
        // Slide 2 up onto slide 1: the separator line of slide 2 stays, the caret on it goes to the content of the moved body.
        val up = edit(threeSlides, 1, down = false)!!
        val upResult = threeSlides.replaceRange(up.start, up.end, up.text)
        assertEquals(upResult.indexOf("# Two"), up.caretAfter(threeSlides.indexOf("---\n\n# Two")))
        assertEquals(upResult.indexOf("# Two") + 1, up.caretAfter(threeSlides.indexOf("# Two") + 1))
    }

    fun testCaretFollowsTheMovedSlideAfterABlankLineWasAdded() {
        val text = "$front\nOne para\n***\n\n# Two\n\n---\n\n# Three\n"
        val edit = edit(text, 2, down = false)!!
        val result = text.replaceRange(edit.start, edit.end, edit.text)
        assertEquals(result.indexOf("---\n\n# Three"), edit.caretAfter(text.indexOf("---\n\n# Three")))
        assertEquals(result.indexOf("# Three") + 2, edit.caretAfter(text.indexOf("# Three") + 2))
    }
}
