package cz.p3kj.marp.slides

import cz.p3kj.marp.MarpDetector
import cz.p3kj.marp.slides.MarpBlock.Break
import cz.p3kj.marp.slides.MarpBlock.Comment
import cz.p3kj.marp.slides.MarpBlock.Content
import cz.p3kj.marp.slides.MarpBlock.Heading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The blocks are built by hand here, the way a Markdown parser would report them, so that these tests pin down the Marp
 * rules alone. The expectations follow Marpit (`slide.js`, `heading_divider.js`).
 */
class MarpSlideSplitterTest {

    private val front = "---\nmarp: true\n---\n"

    private fun split(text: String, vararg blocks: MarpBlock): MarpDeck =
        MarpSlideSplitter.split(text, MarpDetector.findFrontMatter(text), blocks.toList()).also { assertPartition(text, it) }

    /** The slides cover the whole text, in order, without gaps or overlaps. */
    private fun assertPartition(text: String, deck: MarpDeck) {
        assertTrue(deck.slides.isNotEmpty())
        assertEquals(0, deck.slides.first().startOffset)
        assertEquals(text.length, deck.slides.last().endOffset)
        deck.slides.forEachIndexed { index, slide ->
            assertEquals(index, slide.index)
            assertTrue("body of slide $index", slide.startOffset <= slide.bodyOffset && slide.bodyOffset <= slide.endOffset)
            assertTrue("content of slide $index", slide.startOffset <= slide.contentOffset && slide.contentOffset <= slide.endOffset)
            if (index > 0) assertEquals("slide $index starts where the previous one ends", deck.slides[index - 1].endOffset, slide.startOffset)
        }
    }

    private fun String.at(marker: String, from: Int = 0): Int {
        val index = indexOf(marker, from)
        assertTrue("'$marker' not found", index >= 0)
        return index
    }

    private fun titles(deck: MarpDeck): List<String?> = deck.slides.map { it.title }

    @Test
    fun emptyTextIsOneEmptySlide() {
        val deck = split("")
        assertEquals(1, deck.slides.size)
        val slide = deck.slides.single()
        assertEquals(listOf(0, 0, 0, 0, 0), listOf(slide.index, slide.startOffset, slide.endOffset, slide.bodyOffset, slide.contentOffset))
        assertNull(slide.title)
        assertTrue(deck.headingDivider.isEmpty())
    }

    @Test
    fun textWithoutBreaksIsOneSlide() {
        val text = "# Title\n\nSome text\n"
        val deck = split(text, Heading(0, 1, "Title"), Content(text.at("Some")))
        assertEquals(1, deck.slides.size)
        assertEquals("Title", deck.slides[0].title)
        assertEquals(0, deck.slides[0].contentOffset)
    }

    @Test
    fun frontMatterAndTwoBreaksMakeThreeSlides() {
        val text = front + "\n# Intro\n\nHello\n\n---\n\nSecond\n\n---\n\nThird\n"
        val firstBreak = text.at("---", front.length)
        val secondBreak = text.at("---", firstBreak + 3)
        val deck = split(
            text,
            Heading(text.at("# Intro"), 1, "Intro"), Content(text.at("Hello")),
            Break(firstBreak), Content(text.at("Second")),
            Break(secondBreak), Content(text.at("Third")),
        )
        assertEquals(3, deck.slides.size)

        val first = deck.slides[0]
        assertEquals(0, first.startOffset)
        assertEquals(firstBreak, first.endOffset)
        // The front matter belongs to the first slide, navigation skips it and the blank line after it.
        assertEquals(front.length, first.bodyOffset)
        assertEquals(text.at("# Intro"), first.contentOffset)
        assertEquals("Intro", first.title)

        val second = deck.slides[1]
        assertEquals(firstBreak, second.startOffset)
        assertEquals(secondBreak, second.endOffset)
        assertEquals(text.at("\n", firstBreak) + 1, second.bodyOffset)
        assertEquals(text.at("Second"), second.contentOffset)
        assertNull(second.title)

        val third = deck.slides[2]
        assertEquals(secondBreak, third.startOffset)
        assertEquals(text.length, third.endOffset)
        assertEquals(text.at("Third"), third.contentOffset)
    }

    @Test
    fun indentedSeparatorStartsAtTheLineStart() {
        val text = "Hello\n  ---\nSecond\n"
        val deck = split(text, Content(0), Break(text.at("---")), Content(text.at("Second")))
        assertEquals(text.at("  ---"), deck.slides[1].startOffset)
        assertEquals(text.at("Second"), deck.slides[1].contentOffset)
    }

    @Test
    fun breakRightAfterFrontMatterLeavesAnEmptyFirstSlide() {
        val text = front + "---\nSecond\n"
        val deck = split(text, Break(front.length), Content(text.at("Second")))
        assertEquals(2, deck.slides.size)
        val first = deck.slides[0]
        assertEquals(0, first.startOffset)
        assertEquals(front.length, first.endOffset)
        assertEquals(front.length, first.bodyOffset)
        // An empty slide must not point into the next one.
        assertEquals(0, first.contentOffset)
        assertEquals(front.length, deck.slides[1].startOffset)
        assertEquals(text.at("Second"), deck.slides[1].contentOffset)
    }

    @Test
    fun leadingBreakWithoutFrontMatterLeavesAnEmptyFirstSlide() {
        val text = "---\nSecond\n"
        val deck = split(text, Break(0), Content(text.at("Second")))
        assertEquals(2, deck.slides.size)
        assertEquals(0, deck.slides[0].endOffset)
        assertEquals(0, deck.slides[1].startOffset)
        assertEquals(text.at("Second"), deck.slides[1].contentOffset)
        // The separator line belongs to the slide it starts.
        assertEquals(1, deck.slideIndexAt(0))
    }

    @Test
    fun trailingBreakLeavesAnEmptyLastSlide() {
        val withNewline = "Hello\n---\n"
        val deck = split(withNewline, Content(0), Break(withNewline.at("---")))
        assertEquals(2, deck.slides.size)
        val last = deck.slides[1]
        assertEquals(6, last.startOffset)
        assertEquals(withNewline.length, last.endOffset)
        assertEquals(withNewline.length, last.bodyOffset)
        assertEquals(6, last.contentOffset)

        val withoutNewline = "Hello\n---"
        val other = split(withoutNewline, Content(0), Break(withoutNewline.at("---")))
        assertEquals(2, other.slides.size)
        assertEquals(6, other.slides[1].startOffset)
        assertEquals(withoutNewline.length, other.slides[1].bodyOffset)
        assertEquals(6, other.slides[1].contentOffset)
    }

    @Test
    fun consecutiveBreaksMakeEmptySlides() {
        val text = "A\n---\n---\nB\n"
        val deck = split(text, Content(0), Break(text.at("---")), Break(text.at("---", text.at("---") + 3)), Content(text.at("B")))
        assertEquals(3, deck.slides.size)
        val empty = deck.slides[1]
        assertEquals(2, empty.startOffset)
        assertEquals(6, empty.endOffset)
        assertEquals(6, empty.bodyOffset)
        assertEquals(2, empty.contentOffset)
        assertEquals(text.at("B"), deck.slides[2].contentOffset)
    }

    @Test
    fun blankLinesOnlySlidePointsAtItsFirstBlankLine() {
        val text = "A\n---\n\n\n---\nB\n"
        val second = text.at("---", text.at("---") + 3)
        val deck = split(text, Content(0), Break(text.at("---")), Break(second), Content(text.at("B")))
        assertEquals(3, deck.slides.size)
        assertEquals(6, deck.slides[1].contentOffset)
    }

    @Test
    fun crlfLineBreaks() {
        val text = "Hello\r\n---\r\n\r\nSecond\r\n"
        val deck = split(text, Content(0), Break(text.at("---")), Content(text.at("Second")))
        assertEquals(2, deck.slides.size)
        assertEquals(text.at("---"), deck.slides[1].startOffset)
        assertEquals(text.at("---") + 5, deck.slides[1].bodyOffset)
        assertEquals(text.at("Second"), deck.slides[1].contentOffset)
    }

    @Test
    fun headingDividerSplitsAtTheConfiguredLevels() {
        val text = "---\nmarp: true\nheadingDivider: 2\n---\n<!-- note -->\n# Title\n\nText\n\n## Part\n\n### Detail\n\nMore\n\n## Other\n"
        val deck = split(
            text,
            Comment(text.at("<!--"), "<!-- note -->"),
            Heading(text.at("# Title"), 1, "Title"), Content(text.at("Text")),
            Heading(text.at("## Part"), 2, "Part"), Heading(text.at("### Detail"), 3, "Detail"), Content(text.at("More")),
            Heading(text.at("## Other"), 2, "Other"),
        )
        assertEquals(setOf(1, 2), deck.headingDivider)
        // Only a comment precedes the first h1, so it does not start a slide of its own. The h3 does not divide.
        assertEquals(listOf<String?>("Title", "Part", "Other"), titles(deck))
        assertEquals(listOf("Part", "Detail"), deck.slides[1].headings.map { it.text })
        assertEquals(listOf(2, 3), deck.slides[1].headings.map { it.level })
        assertEquals(text.at("<!--"), deck.slides[0].contentOffset)

        val part = deck.slides[1]
        assertEquals(text.at("## Part"), part.startOffset)
        assertEquals(part.startOffset, part.bodyOffset)
        assertEquals(part.startOffset, part.contentOffset)
        assertEquals(text.at("## Part"), part.headings[0].offset)
        assertEquals(text.at("### Detail"), part.headings[1].offset)
        assertEquals(text.at("## Other"), deck.slides[2].startOffset)
    }

    @Test
    fun headingDividerDoesNotSplitBeforeTheFirstVisibleBlock() {
        val text = "<!-- headingDivider: 1 -->\n\n# One\n\n# Two\n"
        val deck = split(
            text,
            Comment(0, "<!-- headingDivider: 1 -->"),
            Heading(text.at("# One"), 1, "One"), Heading(text.at("# Two"), 1, "Two"),
        )
        // "# One" follows a comment only, "# Two" follows a visible heading.
        assertEquals(listOf<String?>("One", "Two"), titles(deck))
        assertEquals(text.at("# Two"), deck.slides[1].startOffset)
    }

    @Test
    fun headingAfterContentSplits() {
        val text = "<!-- headingDivider: 1 -->\nIntro text\n\n# One\n"
        val deck = split(text, Comment(0, "<!-- headingDivider: 1 -->"), Content(text.at("Intro")), Heading(text.at("# One"), 1, "One"))
        assertEquals(2, deck.slides.size)
        assertNull(deck.slides[0].title)
        assertEquals("One", deck.slides[1].title)
        assertEquals(text.at("# One"), deck.slides[1].startOffset)
    }

    @Test
    fun breakDirectlyBeforeADividerHeadingLeavesAnEmptySlideBetween() {
        val text = "---\nheadingDivider: 1\n---\n# A\n\n---\n# B\n"
        val separator = text.at("---", text.at("# A"))
        val deck = split(text, Heading(text.at("# A"), 1, "A"), Break(separator), Heading(text.at("# B"), 1, "B"))
        // A real separator is visible, so the divider heading after it still starts a slide.
        assertEquals(listOf<String?>("A", null, "B"), titles(deck))
        val empty = deck.slides[1]
        assertEquals(separator, empty.startOffset)
        assertEquals(text.at("# B"), empty.endOffset)
        assertEquals(separator, empty.contentOffset)
        assertEquals(text.at("# B"), deck.slides[2].contentOffset)
    }

    @Test
    fun breakThenHeadingWithoutDividerIsOneSlide() {
        val text = "A\n---\n# B\n"
        val deck = split(text, Content(0), Break(text.at("---")), Heading(text.at("# B"), 1, "B"))
        assertEquals(listOf<String?>(null, "B"), titles(deck))
    }

    @Test
    fun linkDefinitionsAndBlankSpaceAreNotBlocksHere() {
        // A parser reports nothing for them, so a divider heading after them is still the first visible block.
        val text = "<!-- headingDivider: 1 -->\n[a]: http://x\n\n# One\n"
        val deck = split(text, Comment(0, "<!-- headingDivider: 1 -->"), Heading(text.at("# One"), 1, "One"))
        assertEquals(1, deck.slides.size)
    }

    @Test
    fun nestedHeadingsDivideToo() {
        // Marpit inserts the hidden break before any heading_open token, also inside a blockquote.
        val text = "<!-- headingDivider: 2 -->\nText\n\n> ## Quoted\n"
        val deck = split(text, Comment(0, "<!-- headingDivider: 2 -->"), Content(text.at("Text")), Content(text.at("> ##")), Heading(text.at("## Quoted"), 2, "Quoted"))
        assertEquals(2, deck.slides.size)
        assertEquals(text.at("> ##"), deck.slides[1].startOffset)
        assertEquals("Quoted", deck.slides[1].title)
    }

    @Test
    fun headingDividerFromALaterCommentAppliesToEarlierHeadings() {
        val text = "# A\n\nText\n\n## B\n\n<!-- headingDivider: 2 -->\n"
        val deck = split(
            text,
            Heading(0, 1, "A"), Content(text.at("Text")), Heading(text.at("## B"), 2, "B"),
            Comment(text.at("<!--"), "<!-- headingDivider: 2 -->"),
        )
        assertEquals(setOf(1, 2), deck.headingDivider)
        assertEquals(listOf<String?>("A", "B"), titles(deck))
    }

    @Test
    fun headingDividerFalseInACommentOverridesTheFrontMatter() {
        val text = "---\nheadingDivider: 1\n---\n# A\n\n# B\n\n<!-- headingDivider: false -->\n"
        val deck = split(
            text,
            Heading(text.at("# A"), 1, "A"), Heading(text.at("# B"), 1, "B"),
            Comment(text.at("<!--"), "<!-- headingDivider: false -->"),
        )
        assertTrue(deck.headingDivider.isEmpty())
        assertEquals(1, deck.slides.size)
    }

    @Test
    fun titleIsTheFirstHeadingWithCollapsedWhitespace() {
        val text = "#   Hello \t  big\n   world  \n\n## Second\n"
        val deck = split(text, Heading(0, 1, "  Hello \t  big\n   world  "), Heading(text.at("## Second"), 2, "Second"))
        assertEquals("Hello big world", deck.slides[0].title)
        assertEquals(listOf("Hello big world", "Second"), deck.slides[0].headings.map { it.text })
    }

    @Test
    fun blankOrMissingHeadingTextIsNoTitle() {
        val text = "#\n\nText\n"
        assertNull(split(text, Heading(0, 1, ""), Content(text.at("Text"))).slides[0].title)
        assertNull(split(text, Heading(0, 1, "  \n "), Content(text.at("Text"))).slides[0].title)
        assertNull(split(text, Content(0)).slides[0].title)
    }

    @Test
    fun slideIndexAtFollowsTheSlideRanges() {
        val text = front + "\n# One\n\n---\n\nTwo\n\n---\nThree\n"
        val firstBreak = text.at("---", front.length)
        val secondBreak = text.at("---", firstBreak + 3)
        val deck = split(
            text,
            Heading(text.at("# One"), 1, "One"),
            Break(firstBreak), Content(text.at("Two")),
            Break(secondBreak), Content(text.at("Three")),
        )
        assertEquals(3, deck.slides.size)
        // Front matter and everything up to the first separator: slide 1.
        assertEquals(0, deck.slideIndexAt(0))
        assertEquals(0, deck.slideIndexAt(front.length - 1))
        assertEquals(0, deck.slideIndexAt(firstBreak - 1))
        // The separator line belongs to the slide it starts.
        assertEquals(1, deck.slideIndexAt(firstBreak))
        assertEquals(1, deck.slideIndexAt(firstBreak + 2))
        assertEquals(1, deck.slideIndexAt(secondBreak - 1))
        assertEquals(2, deck.slideIndexAt(secondBreak))
        assertEquals(2, deck.slideIndexAt(text.length - 1))
        // Outside the text.
        assertEquals(2, deck.slideIndexAt(text.length))
        assertEquals(2, deck.slideIndexAt(text.length + 100))
        assertEquals(0, deck.slideIndexAt(-5))
    }

    @Test
    fun slideIndexAtWithASingleSlide() {
        val deck = split("Hello\n", Content(0))
        assertEquals(0, deck.slideIndexAt(-1))
        assertEquals(0, deck.slideIndexAt(3))
        assertEquals(0, deck.slideIndexAt(100))
    }

    @Test
    fun manySlidesAreSplitInLinearTime() {
        val count = 50_000
        val text = "A\n---\n".repeat(count)
        val blocks = ArrayList<MarpBlock>()
        for (i in 0 until count) {
            blocks += Content(i * 6)
            blocks += Break(i * 6 + 2)
        }
        val started = System.nanoTime()
        val deck = MarpSlideSplitter.split(text, null, blocks)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $elapsedMs ms", elapsedMs < 2_000)
        assertEquals(count + 1, deck.slides.size)
        assertPartition(text, deck)
        assertEquals(count, deck.slideIndexAt(text.length))
    }
}
