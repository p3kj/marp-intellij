package cz.p3kj.marp.folding

import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.lang.folding.LanguageFolding
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.project.DumbService
import com.intellij.testFramework.EditorTestUtil
import cz.p3kj.marp.MarpBundle
import cz.p3kj.marp.MarpLightTestCase
import org.intellij.plugins.markdown.lang.MarkdownLanguage

class MarpSlideFoldingBuilderTest : MarpLightTestCase() {

    private val builder = MarpSlideFoldingBuilder()

    /** A region as zero-based first line, last line and the folded text. */
    private data class Region(val startLine: Int, val endLine: Int, val placeholder: String)

    private fun descriptorsOf(text: String, name: String = "deck.md"): List<FoldingDescriptor> {
        myFixture.configureByText(name, text)
        return runReadActionBlocking {
            builder.buildFoldRegions(myFixture.file, myFixture.editor.document, false).toList()
        }
    }

    /** The regions the builder returns for [text], after checking what every region has to satisfy. */
    private fun regionsOf(text: String, name: String = "deck.md"): List<Region> {
        val descriptors = descriptorsOf(text, name)
        val document = myFixture.editor.document
        var previousEnd = 0
        return descriptors.map {
            val range = it.range
            // The region starts at the line break of the visible first line and ends on the last visible character.
            assertEquals("\n", document.getText(range).take(1))
            assertFalse(document.getText(range).last().isWhitespace())
            assertTrue("regions are ascending and do not overlap", range.startOffset >= previousEnd)
            previousEnd = range.endOffset
            assertEquals(false, it.isCollapsedByDefault())
            Region(document.getLineNumber(range.startOffset), document.getLineNumber(range.endOffset), it.placeholderText!!)
        }
    }

    fun testEachSlideFoldsFromTheEndOfItsFirstLine() {
        val regions = regionsOf(
            """
            ---
            marp: true
            ---

            # Title

            Intro paragraph

            ---

            ## Agenda

            - one
            - two
            - three


            ---

            ## Last
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                Region(2, 6, "Slide 1: Title"),
                Region(8, 14, "Slide 2: Agenda"),
                Region(17, 19, "Slide 3: Last"),
            ),
            regions,
        )
    }

    fun testTheRegionEndsBeforeTheBlankLinesOfTheSlide() {
        val text = "---\nmarp: true\n---\n\n# One\n\ntext\n\n\n\n---\n\n# Two\n"
        val descriptors = descriptorsOf(text)
        val first = descriptors.first().range
        assertEquals("\n\n# One\n\ntext", myFixture.editor.document.getText(first))
        assertEquals("\n\n\n\n", text.substring(first.endOffset, text.indexOf("---", first.endOffset)))
    }

    fun testSlidesWithNothingAfterTheirFirstLineHaveNoRegion() {
        // Slide 2 is a separator followed by blank lines, slide 4 a separator at the end of the file.
        val regions = regionsOf("---\nmarp: true\n---\n\n# One\n\n---\n\n\n---\n\n# Three\n\n---")
        assertEquals(listOf(Region(2, 4, "Slide 1: One"), Region(9, 11, "Slide 3: Three")), regions)
    }

    fun testASlideStartedByAHeadingOnlyHasNoRegion() {
        val regions = regionsOf("---\nmarp: true\nheadingDivider: 2\n---\n\nintro\n\n## B\n\n## C\n\ntext")
        assertEquals(listOf(Region(3, 5, "Slide 1"), Region(9, 11, "Slide 3")), regions)
    }

    fun testADeckWithNothingAfterTheFrontMatterHasNoRegion() {
        assertEquals(emptyList<Region>(), regionsOf("---\nmarp: true\n---\n\n\n"))
        assertEquals(emptyList<Region>(), regionsOf("---\nmarp: true\n---"))
    }

    fun testSlidesStartedByHeadingDividerFoldFromTheHeadingLine() {
        val regions = regionsOf(
            """
            ---
            marp: true
            headingDivider: 2
            ---

            # A

            intro

            ## B

            text

            ## C

            more
            """.trimIndent(),
        )
        // The heading that starts a slide is the visible first line, so it is not repeated in the placeholder.
        assertEquals(listOf(Region(3, 7, "Slide 1: A"), Region(9, 11, "Slide 2"), Region(13, 15, "Slide 3")), regions)
    }

    fun testASlideWithoutHeadingIsNumberedAndALateHeadingIsItsTitle() {
        val regions = regionsOf(
            """
            ---
            marp: true
            ---

            First line

            ---

            text

            <!-- comment -->

            ## Late heading
            """.trimIndent(),
        )
        assertEquals(listOf(Region(2, 4, "Slide 1"), Region(6, 12, "Slide 2: Late heading")), regions)
    }

    fun testALongTitleIsShortened() {
        val title = "word ".repeat(30).trim()
        val regions = regionsOf("---\nmarp: true\n---\n\n# $title\n\ntext\n")
        val placeholder = regions.single().placeholder
        assertTrue(placeholder, placeholder.startsWith("Slide 1: word word"))
        assertTrue(placeholder, placeholder.endsWith("..."))
        assertTrue(placeholder.length < 80)
    }

    fun testASeparatorInsideACodeFenceDoesNotSplitTheRegion() {
        val regions = regionsOf(
            """
            ---
            marp: true
            ---

            # One

            ```
            ---
            code
            ```

            ---

            # Two
            """.trimIndent(),
        )
        assertEquals(listOf(Region(2, 9, "Slide 1: One"), Region(11, 13, "Slide 2: Two")), regions)
    }

    fun testThePlaceholderComesFromTheBundle() {
        val regions = regionsOf("---\nmarp: true\n---\n\n# Agenda\n\ntext\n\n---\n\ntext\n\nmore\n")
        assertEquals(
            listOf(
                MarpBundle.message("folding.slide.titled", "1", "Agenda"),
                MarpBundle.message("folding.slide.untitled", "2"),
            ),
            regions.map { it.placeholder },
        )
    }

    fun testNoRegionsInPlainMarkdown() {
        assertEquals(0, descriptorsOf("# One\n\ntext\n\n---\n\n# Two\n\ntext\n", "plain.md").size)
    }

    fun testNoRegionsWhenMarpIsOff() {
        assertEquals(0, descriptorsOf("---\nmarp: false\n---\n\n# One\n\ntext\n\n---\n\n# Two\n\ntext\n").size)
    }

    fun testBuilderIsDumbAwareAndNeverCollapsesByDefault() {
        assertTrue(DumbService.isDumbAware(builder))
        val descriptors = descriptorsOf("---\nmarp: true\n---\n\n# One\n\ntext\n\n---\n\n# Two\n\ntext\n")
        assertEquals(2, descriptors.size)
        for (descriptor in descriptors) assertFalse(builder.isCollapsedByDefault(descriptor.element))
    }

    fun testBuilderIsRegisteredForMarkdownBeforeTheMarkdownPlugin() {
        val builders = LanguageFolding.INSTANCE.allForLanguage(MarkdownLanguage.INSTANCE)
        assertTrue(builders.first() is MarpSlideFoldingBuilder)
    }

    /**
     * The platform refuses a region that overlaps an earlier one, and the Markdown plugin folds `## A` up to the next
     * `##`, across the `---` line. The slide regions have to win, so all slides fold and none is lost to that heading.
     */
    fun testSlideRegionsSurviveMarkdownHeadingRegionsThatCrossASlideBoundary() {
        myFixture.configureByText(
            "deck.md",
            """
            ---
            marp: true
            ---

            # Title

            Intro

            ---

            ## A

            text

            ### Sub

            sub text

            ---

            ## B

            end text
            """.trimIndent(),
        )
        val editor = myFixture.editor
        EditorTestUtil.buildInitialFoldingsInBackground(editor)
        val document = editor.document
        val regions = editor.foldingModel.allFoldRegions
        val slides = regions.filter { it.placeholderText.startsWith("Slide ") }
        assertEquals(
            listOf(
                Region(2, 6, "Slide 1: Title"),
                Region(8, 16, "Slide 2: A"),
                Region(18, 22, "Slide 3: B"),
            ),
            slides.map { Region(document.getLineNumber(it.startOffset), document.getLineNumber(it.endOffset), it.placeholderText) },
        )
        assertTrue("slides are expanded by default", slides.all { it.isExpanded })
        // The front matter keeps its own region, it ends where the first slide region starts.
        val frontMatter = regions.filter { it.startOffset == 0 }
        assertEquals(1, frontMatter.size)
        assertTrue(frontMatter.single().endOffset <= slides.first().startOffset)
    }
}
