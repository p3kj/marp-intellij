package cz.p3kj.marp.slides

import cz.p3kj.marp.MarpLightTestCase
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownFile

/**
 * The slide swap and the move to any place on plain text. Every result is checked twice: the exact text, and a re-parse of it that must give the
 * expected slide order with the same number of slides (a lost or merged slide shows up there).
 */
class MarpSlideReorderTest : MarpLightTestCase() {

    private val front = "---\nmarp: true\n---\n"

    private fun deck(text: String): MarpDeck {
        myFixture.configureByText("deck.md", text)
        return MarpSlideParser.deck(myFixture.file as MarkdownFile)
    }

    private fun edit(text: String, index: Int, down: Boolean): MarpSlideReorder.Edit? =
        MarpSlideReorder.move(text, deck(text), index, down)

    /** [text] after moving slide [index], `null` when there is nothing to move. */
    private fun moved(text: String, index: Int, down: Boolean): String? =
        edit(text, index, down)?.let { text.replaceRange(it.start, it.end, it.text) }

    /** Slide titles, "-" for slides without one. */
    private fun titles(text: String): List<String> =
        deck(text).slides.map { it.title ?: "-" }

    private fun assertMoved(
        expected: String, text: String, index: Int, down: Boolean, expectedTitles: List<String>,
    ) {
        val result = moved(text, index, down)
        assertEquals(expected, result)
        assertEquals(expectedTitles, titles(result!!))
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
        assertMoved("$front# A\n\n---\n# C\n\n---\n# B\n", text, 1, down = true, listOf("A", "C", "B"))
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

    // Moving to any place -----------------------------------------------------------------------------------------------

    private fun movedTo(text: String, from: Int, to: Int): String? =
        MarpSlideReorder.moveTo(text, deck(text), from, to)?.let { text.replaceRange(it.start, it.end, it.text) }

    private fun assertMovedTo(expected: String, text: String, from: Int, to: Int, expectedTitles: List<String>) {
        val result = movedTo(text, from, to)
        assertEquals(expected, result)
        assertEquals(expectedTitles, titles(result!!))
    }

    fun testMovingByOnePlaceIsTheSameAsMove() {
        val tight = "$front# A\n---\n# B\n---\n# C\n---\n# D\n"
        val stars = "$front\n# One\n\n***\n\n# Two\n\n***\n\n# Three\n\n***\n\n# Four\n"
        for (text in listOf(fourSlides, tight, stars, fourSlides.trimEnd('\n'))) {
            for (index in 0..3) {
                assertEquals("$index down in $text", moved(text, index, down = true), movedTo(text, index, index + 1))
                assertEquals("$index up in $text", moved(text, index, down = false), movedTo(text, index, index - 1))
            }
        }
    }

    fun testMovesASlideOverSeveralSlides() {
        assertMovedTo(
            "$front\n# One\n\n---\n\n# Three\n\n---\n\n# Four\n\n---\n\n# Two\n",
            fourSlides, 1, 3, listOf("One", "Three", "Four", "Two"),
        )
        assertMovedTo(
            "$front\n# One\n\n---\n\n# Four\n\n---\n\n# Two\n\n---\n\n# Three\n",
            fourSlides, 3, 1, listOf("One", "Four", "Two", "Three"),
        )
    }

    fun testMovesOverTheFirstSlideAndKeepsTheFrontMatterAtTheTop() {
        assertMovedTo(
            "$front\n# Two\n\n---\n\n# Three\n\n---\n\n# Four\n\n---\n\n# One\n",
            fourSlides, 0, 3, listOf("Two", "Three", "Four", "One"),
        )
        assertMovedTo(
            "$front\n# Four\n\n---\n\n# One\n\n---\n\n# Two\n\n---\n\n# Three\n",
            fourSlides, 3, 0, listOf("Four", "One", "Two", "Three"),
        )
    }

    fun testMixedSeparatorsTravelWithTheirSlidesOverSeveralSlides() {
        val text = "$front\n# One\n\n***\n\n# Two\n\n---\n\n# Three\n"
        assertMovedTo("$front\n# Two\n\n---\n\n# Three\n\n***\n\n# One\n", text, 0, 2, listOf("Two", "Three", "One"))
        assertMovedTo("$front\n# Three\n\n---\n\n# One\n\n***\n\n# Two\n", text, 2, 0, listOf("Three", "One", "Two"))
    }

    fun testATightDeckKeepsItsSlidesWhenMovedOverSeveralSlides() {
        val text = "$front# A\n---\n# B\n---\n# C\n---\n# D\n"
        assertMovedTo("$front# B\n---\n# C\n---\n# D\n\n---\n# A\n", text, 0, 3, listOf("B", "C", "D", "A"))
        assertMovedTo("$front# A\n\n---\n# D\n\n---\n# B\n---\n# C\n", text, 3, 1, listOf("A", "D", "B", "C"))
        assertMovedTo("$front# A\n\n---\n# C\n---\n# D\n\n---\n# B\n", text, 1, 3, listOf("A", "C", "D", "B"))
    }

    fun testAParagraphAboveASeparatorDoesNotMergeSlidesMovedOverSeveralSlides() {
        // `***` can follow a paragraph line directly, a moved-in `---` would turn that line into a heading.
        val text = "$front\nOne para\n***\n\n# Two\n\n---\n\n# Three\n\n---\n\n# Four\n"
        assertEquals(listOf("-", "Two", "Three", "Four"), titles(text))
        assertMovedTo(
            "$front\nOne para\n\n---\n\n# Three\n\n---\n\n# Four\n\n***\n\n# Two\n",
            text, 1, 3, listOf("-", "Three", "Four", "Two"),
        )
        assertMovedTo(
            "$front\nOne para\n\n---\n\n# Four\n\n***\n\n# Two\n\n---\n\n# Three\n",
            text, 3, 1, listOf("-", "Four", "Two", "Three"),
        )
    }

    fun testAnEmptyFirstSlideMovesOverSeveralSlides() {
        val text = "$front---\n\n# Two\n\n---\n\n# Three\n"
        assertEquals(listOf("-", "Two", "Three"), titles(text))
        val result = movedTo(text, 0, 2)
        assertEquals(listOf("Two", "Three", "-"), titles(result!!))
        assertEquals(listOf("-", "Two", "Three"), titles(movedTo(result, 2, 0)!!))
    }

    fun testMovesTheLastSlideUpOverSeveralSlidesWithoutATrailingNewline() {
        val text = fourSlides.trimEnd('\n')
        assertMovedTo(
            "$front\n# One\n\n---\n\n# Four\n\n---\n\n# Two\n\n---\n\n# Three",
            text, 3, 1, listOf("One", "Four", "Two", "Three"),
        )
    }

    fun testMovesRoundTripToTheOriginalText() {
        for (ending in listOf("\n", "", "\n\n")) {
            val text = fourSlides.trimEnd('\n') + ending
            for ((from, to) in listOf(1 to 3, 0 to 3, 0 to 2, 1 to 2, 2 to 3)) {
                val there = movedTo(text, from, to)!!
                assertEquals("$from to $to, ending '${ending.replace("\n", "\\n")}'", text, movedTo(there, to, from))
            }
        }
    }

    fun testMoveToReportsWhereTheMovedSlideLands() {
        val edit = MarpSlideReorder.moveTo(fourSlides, deck(fourSlides), 1, 3)!!
        val result = fourSlides.replaceRange(edit.start, edit.end, edit.text)
        // A slide after the first one starts with its separator line, which is also its first non-blank line.
        assertEquals(result.indexOf("---\n\n# Two"), edit.movedTo)
        assertEquals(result.indexOf("---\n\n# Two"), edit.contentTo)
        val up = MarpSlideReorder.moveTo(fourSlides, deck(fourSlides), 3, 1)!!
        val upResult = fourSlides.replaceRange(up.start, up.end, up.text)
        assertEquals(upResult.indexOf("---\n\n# Four"), up.movedTo)
        assertEquals(upResult.indexOf("---\n\n# Four"), up.contentTo)
        // The first slide has no separator line: its body lands last and the caret goes to the content.
        val first = MarpSlideReorder.moveTo(fourSlides, deck(fourSlides), 0, 3)!!
        val firstResult = fourSlides.replaceRange(first.start, first.end, first.text)
        assertEquals(firstResult.indexOf("# One"), first.contentTo)
        assertEquals(firstResult.indexOf("# One"), first.caretAfter(fourSlides.indexOf("# One")))
        assertEquals(firstResult.indexOf("# One"), first.caretAfter(0))
    }

    fun testMoveToNeedsTwoDifferentSlidesOfASupportedDeck() {
        val deck = deck(fourSlides)
        assertNull(MarpSlideReorder.moveTo(fourSlides, deck, 2, 2))
        assertNull(MarpSlideReorder.moveTo(fourSlides, deck, -1, 2))
        assertNull(MarpSlideReorder.moveTo(fourSlides, deck, 1, 4))
        assertNull(MarpSlideReorder.moveTo(fourSlides, deck, 4, 1))
        assertNull(MarpSlideReorder.moveTo(fourSlides, deck, 1, -1))
        val heading = "---\nmarp: true\nheadingDivider: 2\n---\n\n# A\n\n## B\n\n## C\n"
        assertNull(MarpSlideReorder.moveTo(heading, deck(heading), 0, 2))
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
