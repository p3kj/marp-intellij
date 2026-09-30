package cz.p3kj.marp.directives

import com.intellij.openapi.util.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarpFrontMatterTest {

    private fun parse(text: String): MarpParsedFrontMatter = MarpFrontMatter.parse(text)!!

    private fun slice(text: String, range: TextRange): String = text.substring(range.startOffset, range.endOffset)

    private fun MarpParsedFrontMatter.keys(): List<String> = entries.map { it.key }

    @Test
    fun rangesAreDocumentOffsets() {
        val text = "---\nmarp: true\ntheme: gaia\n---\n# Hi\n"
        val frontMatter = parse(text)
        assertEquals("marp: true\ntheme: gaia\n", slice(text, frontMatter.bodyRange))
        assertTrue(frontMatter.isMapping)
        assertEquals(listOf("marp", "theme"), frontMatter.keys())
        val theme = frontMatter.entries[1]
        assertEquals("theme", slice(text, theme.keyRange))
        assertEquals("gaia", slice(text, theme.valueRange!!))
    }

    @Test
    fun rangesAreDocumentOffsetsWithAByteOrderMarkAndCrlf() {
        val text = "﻿---\r\nmarp: true\r\npaginate: yes\r\n---\r\n"
        val frontMatter = parse(text)
        val paginate = frontMatter.entries[1]
        assertEquals("paginate", slice(text, paginate.keyRange))
        assertEquals("yes", slice(text, paginate.valueRange!!))
        assertEquals("true", frontMatter.entries[0].rawValue)
    }

    @Test
    fun noFrontMatterNoResult() {
        assertNull(MarpFrontMatter.parse("# Title\n"))
        assertNull(MarpFrontMatter.parse("---\nmarp: true\n"))
    }

    @Test
    fun sizeAndMathTakeTheRestOfTheLineInTheFrontMatterButNotInAComment() {
        val frontMatter = "---\nmarp: true\nsize: 4:3 # c\nmath: katex # c\n---\n"
        val entries = parse(frontMatter).entries
        assertEquals("4:3 # c", entries[1].rawValue)
        assertEquals("katex # c", entries[2].rawValue)
        assertEquals("4:3", MarpDirectiveComments.parse("<!-- size: 4:3 # c -->")!!.entries.single().rawValue)
        assertEquals("katex", MarpDirectiveComments.parse("<!-- math: katex # c -->")!!.entries.single().rawValue)
    }

    @Test
    fun marpIsPlainYamlAndOtherKeysToo() {
        val entries = parse("---\nmarp: true # c\ntitle: A # b\n---\n").entries
        assertEquals("true", entries[0].rawValue)
        assertEquals("A", entries[1].rawValue)
        // The marpit directives still take the whole line.
        assertEquals("true # c", parse("---\nmarp: true\npaginate: true # c\n---\n").entries[1].rawValue)
    }

    @Test
    fun marpIsKnownOnlyInTheFrontMatter() {
        val entry = parse("---\nmarp: true\n---\n").entries.single()
        assertEquals(MarpDirectiveKey.Known(MarpDirectiveCatalog.MARP, spot = false), entry.resolved)
        val inComment = MarpDirectiveComments.parse("<!-- marp: true -->")!!.entries.single()
        assertTrue(inComment.resolved is MarpDirectiveKey.Unknown)
        assertFalse(MarpDirectiveCatalog.ALL.contains(MarpDirectiveCatalog.MARP))
        assertFalse(MarpDirectiveCatalog.VALID_KEYS.contains("marp"))
    }

    @Test
    fun spotKeysAndUnderscoreGlobalsResolveLikeInComments() {
        val entries = parse("---\nmarp: true\n_class: lead\n_theme: gaia\ntitle: x\n---\n").entries
        val class_ = entries[1].resolved as MarpDirectiveKey.Known
        assertEquals("class", class_.directive.name)
        assertTrue(class_.spot)
        assertTrue(entries[2].resolved is MarpDirectiveKey.GlobalWithUnderscore)
        assertTrue(entries[3].resolved is MarpDirectiveKey.Unknown)
    }

    @Test
    fun bodyThatIsNotAMapping() {
        assertFalse(parse("---\n- a\n- b\n---\n").isMapping)
        assertFalse(parse("---\njust some text\n---\n").isMapping)
        assertFalse(parse("---\nmarp: true\nmarp: false\n---\n").isMapping)
        assertFalse(parse("---\n---\n").isMapping)
    }

    @Test
    fun nestedYamlBelongsToTheEntryAbove() {
        val frontMatter = parse("---\nmarp: true\nimages:\n  - a.png\n  - b.png\ntheme: gaia\n---\n")
        assertTrue(frontMatter.isMapping)
        assertEquals(listOf("marp", "images", "theme"), frontMatter.keys())
    }

    // Completion spots ------------------------------------------------------------------------------------------------

    private fun spot(textWithCaret: String): MarpCompletionSpot? {
        val caret = textWithCaret.indexOf('|')
        return MarpFrontMatter.completionSpot(textWithCaret.removeRange(caret, caret + 1), caret)
    }

    @Test
    fun keySpotsInTheFrontMatter() {
        assertEquals(MarpCompletionSpot.Key("pa"), spot("---\nmarp: true\npa|\n---\n"))
        assertEquals(MarpCompletionSpot.Key(""), spot("---\nmarp: true\n|\n---\n"))
        assertEquals(MarpCompletionSpot.Key("_c"), spot("---\nmarp: true\n_c|\n---\n"))
        assertEquals(MarpCompletionSpot.Key("th"), spot("---\nth|eme: gaia\n---\n"))
    }

    @Test
    fun keySpotOnABlankLineBeforeTheClosingFence() {
        // The blank lines before the closing fence are part of the fence for Marp, but the caret is still in the front matter.
        assertEquals(MarpCompletionSpot.Key(""), spot("---\nmarp: true\n|\n---\n"))
        assertEquals(MarpCompletionSpot.Key(""), spot("---\nmarp: true\n\n|\n---\n"))
        assertNull(spot("---\nmarp: true\n|---\n"))
    }

    @Test
    fun valueSpotsInTheFrontMatter() {
        assertEquals(MarpCompletionSpot.Value("theme", ""), spot("---\nmarp: true\ntheme: |\n---\n"))
        assertEquals(MarpCompletionSpot.Value("paginate", "h"), spot("---\nmarp: true\npaginate: h|\n---\n"))
        assertEquals(MarpCompletionSpot.Value("marp", ""), spot("---\nmarp: |\n---\n"))
    }

    @Test
    fun noSpotOnIndentedLinesOrListItems() {
        assertNull(spot("---\nmarp: true\nimages:\n  pa|\n---\n"))
        assertNull(spot("---\nmarp: true\ntags:\n  - pa|\n---\n"))
        assertNull(spot("---\nmarp: true\n- pa|\n---\n"))
        assertNull(spot("---\nmarp: true\n\tpa|\n---\n"))
    }

    @Test
    fun noSpotOnTheFenceLinesOrOutsideTheFrontMatter() {
        assertNull(spot("---|\nmarp: true\n---\n"))
        assertNull(spot("---\nmarp: true\n---|\n"))
        assertNull(spot("---\nmarp: true\n---\npa|"))
        assertNull(spot("pa|\n"))
        assertNull(spot("---\nmarp: true\npaginate: true\n--- trailing|\n"))
    }

    @Test
    fun noSpotInASentence() {
        assertNull(spot("---\nmarp: true\ntitle: My tal|\n---\n"))
        assertNull(spot("---\nmarp: true\ntitle: \"Hi|\n---\n"))
    }

    @Test
    fun spotsFollowMarpsRuleForTheFences() {
        // Trailing white space after the opening fence, an indented closing fence: Marp accepts both.
        assertNotNull(spot("---  \nmarp: true\npa|\n  ---\n"))
        assertNotNull(spot("----\nmarp: true\npa|\n....\n"))
    }

    // Keys outside the caret line -------------------------------------------------------------------------------------

    private fun keysOutside(textWithCaret: String): Set<String> {
        val caret = textWithCaret.indexOf('|')
        return MarpFrontMatter.keysOutsideLine(textWithCaret.removeRange(caret, caret + 1), caret)
    }

    @Test
    fun keysOutsideTheCaretLine() {
        assertEquals(setOf("marp", "theme"), keysOutside("---\nmarp: true\ntheme: gaia\npa|\n---\n"))
        assertEquals(setOf("marp"), keysOutside("---\nmarp: true\nth|eme: gaia\n---\n"))
        assertEquals(setOf("marp", "theme"), keysOutside("---\nmarp: true\n|\ntheme: gaia\n---\n"))
    }

    @Test
    fun keysOutsideIgnoreIndentedLinesAndStripQuotes() {
        assertEquals(
            setOf("marp", "tags", "class"),
            keysOutside("---\nmarp: true\ntags:\n  theme: x\n\"class\": lead\n|\n---\n"),
        )
    }

    @Test
    fun noKeysWithoutFrontMatter() {
        assertEquals(emptySet<String>(), keysOutside("theme: gaia\n|\n"))
    }
}
