package cz.p3kj.marp.directives

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarpDirectiveCommentsTest {

    private fun parse(text: String): MarpParsedComment = MarpDirectiveComments.parse(text)!!

    private fun MarpParsedComment.keys(): List<String> = entries.map { it.key }

    private fun slice(text: String, range: com.intellij.openapi.util.TextRange): String = text.substring(range.startOffset, range.endOffset)

    @Test
    fun singleLineDirective() {
        val text = "<!-- _class: lead -->"
        val comment = parse(text)
        assertEquals("_class: lead", comment.body)
        assertTrue(comment.isMapping)
        assertTrue(comment.isDirective)
        val entry = comment.entries.single()
        assertEquals("_class", entry.key)
        assertEquals("_class", slice(text, entry.keyRange))
        assertEquals("lead", entry.rawValue)
        assertEquals("lead", entry.value)
        assertEquals("lead", slice(text, entry.valueRange!!))
    }

    @Test
    fun multiLineDirectivesWithTwoKeys() {
        val text = "<!--\n_class: lead\npaginate: true\n-->"
        val comment = parse(text)
        assertEquals(listOf("_class", "paginate"), comment.keys())
        assertEquals("paginate", slice(text, comment.entries[1].keyRange))
        assertEquals("true", slice(text, comment.entries[1].valueRange!!))
        assertTrue(comment.isDirective)
    }

    @Test
    fun emptyQuotedValueKeepsTheQuotesInItsRange() {
        val text = "<!-- _header: '' -->"
        val entry = parse(text).entries.single()
        assertEquals("''", entry.rawValue)
        assertEquals("", entry.value)
        assertEquals("''", slice(text, entry.valueRange!!))
    }

    @Test
    fun missingValueHasNoRange() {
        val entry = parse("<!--\nheadingDivider:\n  - 1\n  - 3\n-->").entries.single()
        assertEquals("", entry.rawValue)
        assertNull(entry.valueRange)
    }

    @Test
    fun marpitDirectivesTakeTheWholeRestOfTheLineAsTheValue() {
        // Marpit's loose YAML quotes the value, so a trailing comment belongs to it.
        val text = "<!-- paginate: true # show numbers -->"
        val entry = parse(text).entries.single()
        assertEquals("true # show numbers", entry.rawValue)
        assertEquals("true # show numbers", slice(text, entry.valueRange!!))
        assertEquals("lead # x", parse("<!-- _class: lead # x -->").entries.single().rawValue)
        assertEquals("#fff", parse("<!-- backgroundColor: #fff -->").entries.single().rawValue)
        assertEquals("Say **hi**", parse("<!-- header: Say **hi** -->").entries.single().rawValue)
    }

    @Test
    fun otherKeysEndAPlainValueAtAYamlComment() {
        assertEquals("4:3", parse("<!-- size: 4:3 # x -->").entries.single().rawValue)
        assertEquals("katex", parse("<!-- math: katex # x -->").entries.single().rawValue)
        assertEquals("hi", parse("<!-- Note: hi # x -->").entries.single().rawValue)
        assertNull(parse("<!-- size: #fff -->").entries.single().valueRange)
    }

    @Test
    fun quotedAndFlowValuesOfMarpitDirectivesStayYaml() {
        val quoted = parse("<!-- header: 'Hi # there' # note -->").entries.single()
        assertEquals("'Hi # there'", quoted.rawValue)
        assertEquals("Hi # there", quoted.value)
        assertEquals("[1, 3]", parse("<!-- headingDivider: [1, 3] # note -->").entries.single().rawValue)
    }

    @Test
    fun aValueStartingWithAStarIsAYamlAliasAndMakesTheCommentANote() {
        for (text in listOf("<!-- header: **Bold** -->", "<!-- Note: *hi* -->", "<!-- _class: lead\nheader: *x -->")) {
            val comment = parse(text)
            assertFalse(text, comment.isMapping)
            assertFalse(text, comment.isDirective)
        }
        assertTrue(parse("<!-- header: '**Bold**' -->").isDirective)
        assertTrue(parse("<!-- header: Say **hi** -->").isDirective)
    }

    @Test
    fun aRepeatedKeyMakesTheCommentANote() {
        val comment = parse("<!--\n_class: lead\n_class: invert\n-->")
        assertFalse(comment.isMapping)
        assertFalse(comment.isDirective)
        assertTrue(parse("<!--\n_class: lead\nclass: invert\n-->").isDirective)
    }

    @Test
    fun anIndentedLineAfterACompleteValueMakesTheCommentANote() {
        assertFalse(parse("<!--\npaginate: true\n  more\n-->").isMapping)
        assertFalse(parse("<!--\n_class: lead\n  invert\n-->").isDirective)
        assertFalse(parse("<!--\nheader: \"Hi\"\n  more\n-->").isMapping)
    }

    @Test
    fun valuesThatContinueOnTheNextLinesAreFine() {
        assertTrue(parse("<!--\nstyle: |\n  section { color: red }\n-->").isDirective)
        assertTrue(parse("<!--\nheader: >\n  text\n  more\n-->").isDirective)
        assertTrue(parse("<!--\nheader: \"open\n  more\"\n-->").isDirective)
        assertTrue(parse("<!--\nheadingDivider: [1,\n  2]\n-->").isDirective)
        assertTrue(parse("<!--\nheadingDivider:\n  - 1\n  - 2\n-->").isDirective)
        assertTrue(parse("<!--\nclass: lead\n  - x\n-->").isMapping)
        // A plain scalar of a key that is not a Marpit directive may continue, as in YAML.
        assertTrue(parse("<!--\nNote: hello\n  world\n-->").isMapping)
    }

    @Test
    fun blockSequenceContinuesTheEntry() {
        val comment = parse("<!--\nheadingDivider:\n  - 1\n  - 2\nclass: lead\n-->")
        assertTrue(comment.isMapping)
        assertEquals(listOf("headingDivider", "class"), comment.keys())
        val compact = parse("<!--\nheadingDivider:\n- 1\n- 2\n-->")
        assertTrue(compact.isMapping)
        assertEquals(listOf("headingDivider"), compact.keys())
    }

    @Test
    fun indentedLineWithoutAPreviousEntryIsNotAMapping() {
        assertFalse(parse("<!--\n  indented\n-->").isMapping)
    }

    @Test
    fun blankAndCommentLinesAreSkipped() {
        val comment = parse("<!--\n# a note to self\n\n_class: lead\n-->")
        assertTrue(comment.isMapping)
        assertEquals(listOf("_class"), comment.keys())
    }

    @Test
    fun plainNoteIsNotAMapping() {
        val comment = parse("<!-- Say hello, then show the demo. -->")
        assertFalse(comment.isMapping)
        assertFalse(comment.isDirective)
        assertFalse(comment.looksLikeDirective)
        assertTrue(comment.entries.isEmpty())
    }

    @Test
    fun aNoteWithAColonIsAMappingButNotADirective() {
        val comment = parse("<!-- Note: hello -->")
        assertTrue(comment.isMapping)
        assertEquals(listOf("Note"), comment.keys())
        assertFalse(comment.isDirective)
        assertFalse(comment.looksLikeDirective)
    }

    @Test
    fun urlsAreNotKeys() {
        assertFalse(parse("<!-- https://example.com/demo -->").isMapping)
    }

    @Test
    fun proseAfterAKeyLineMakesItANote() {
        val comment = parse("<!--\n_class: lead\nand then say hello\n-->")
        assertFalse(comment.isMapping)
        assertFalse(comment.isDirective)
    }

    @Test
    fun globalWithUnderscoreLooksLikeADirectiveButMarpIgnoresIt() {
        val comment = parse("<!-- _theme: gaia -->")
        assertTrue(comment.looksLikeDirective)
        assertFalse(comment.isDirective)
    }

    @Test
    fun oneKnownKeyMakesTheWholeCommentDirectives() {
        val comment = parse("<!-- _clas: lead\npaginate: true -->")
        assertTrue(comment.isDirective)
        assertEquals(listOf("_clas", "paginate"), comment.keys())
    }

    @Test
    fun magicCommentsAreRecognised() {
        for (body in listOf(
            "prettier-ignore", "prettier-ignore-start", "prettier-ignore-end", "markdownlint-disable MD033",
            "markdownlint-enable", "markdownlint-capture", "markdownlint-restore", "lint disable no-html", "lint ignore",
        )) {
            assertTrue(body, parse("<!-- $body -->").isMagic)
        }
        assertFalse(parse("<!-- prettier-ignore-me -->").isMagic)
        assertFalse(parse("<!-- _class: lead -->").isMagic)
    }

    @Test
    fun extraDashesAreNotPartOfTheBody() {
        val text = "<!---- x ---->"
        val comment = parse(text)
        assertEquals("x", comment.body)
        assertEquals("x", slice(text, comment.bodyRange))
        assertEquals(text.length, comment.range.endOffset)
        val empty = parse("<!---->")
        assertEquals("", empty.body)
        assertFalse(empty.isMapping)
    }

    @Test
    fun windowsLineEndings() {
        val text = "<!--\r\n_class: lead\r\npaginate: true\r\n-->"
        val comment = parse(text)
        assertEquals(listOf("_class", "paginate"), comment.keys())
        assertEquals("lead", comment.entries[0].rawValue)
        assertEquals("true", slice(text, comment.entries[1].valueRange!!))
    }

    @Test
    fun textWithoutACompleteCommentIsNotParsed() {
        assertNull(MarpDirectiveComments.parse("no comment"))
        assertNull(MarpDirectiveComments.parse("<!-- open"))
        assertNotNull(MarpDirectiveComments.parse("text <!-- a: b --> text"))
    }

    // completionSpot ----------------------------------------------------------------------------------------------------

    private fun spot(textWithCaret: String): MarpCompletionSpot? {
        val caret = textWithCaret.indexOf('|')
        return MarpDirectiveComments.completionSpot(textWithCaret.removeRange(caret, caret + 1), caret)
    }

    @Test
    fun keySpots() {
        assertEquals(MarpCompletionSpot.Key(""), spot("<!-- | -->"))
        assertEquals(MarpCompletionSpot.Key("pag"), spot("<!-- pag| -->"))
        assertEquals(MarpCompletionSpot.Key("_"), spot("<!-- _| -->"))
        assertEquals(MarpCompletionSpot.Key("_pa"), spot("<!-- _pa| -->"))
        assertEquals(MarpCompletionSpot.Key("back"), spot("<!--\n_class: lead\nback|\n-->"))
        assertEquals(MarpCompletionSpot.Key(""), spot("<!--|-->"))
        assertEquals(MarpCompletionSpot.Key(""), spot("<!--\n|\n-->"))
    }

    @Test
    fun valueSpots() {
        assertEquals(MarpCompletionSpot.Value("paginate", ""), spot("<!-- paginate: | -->"))
        assertEquals(MarpCompletionSpot.Value("_class", "le"), spot("<!-- _class: le| -->"))
        assertEquals(MarpCompletionSpot.Value("theme", ""), spot("<!--\ntheme: |\n-->"))
        assertEquals(MarpCompletionSpot.Value("paginate", "h"), spot("<!--\n_class: lead\npaginate: h|\n-->"))
    }

    @Test
    fun aCommentThatIsStillBeingTypedHasSpots() {
        assertEquals(MarpCompletionSpot.Key("_pa"), spot("<!-- _pa|"))
        assertEquals(MarpCompletionSpot.Value("paginate", ""), spot("<!-- paginate: |"))
    }

    @Test
    fun noValueSpotWithoutSpaceAfterTheColon() {
        assertNull(spot("<!-- paginate:| -->"))
    }

    @Test
    fun noSpotAfterTheCommentOrBeforeItsBody() {
        assertNull(spot("<!-- _class: lead --> |"))
        assertNull(spot("<!-- _class: lead -->| after"))
        assertNull(spot("|<!-- x -->"))
        assertNull(spot("<!|-- x -->"))
        assertNull(spot("<!-- x --|>"))
    }

    @Test
    fun noSpotInASentence() {
        assertNull(spot("<!-- Say hello, then| -->"))
        assertNull(spot("<!-- Say hello| world -->"))
        assertNull(spot("<!-- class: lead in| -->"))
        assertNull(spot("<!-- see https://exa| -->"))
    }
}
