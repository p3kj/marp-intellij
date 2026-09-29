package cz.p3kj.marp.directives

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.openapi.editor.colors.TextAttributesKey
import cz.p3kj.marp.MarpLightTestCase

class MarpDirectiveAnnotatorTest : MarpLightTestCase() {

    private val deck = "---\nmarp: true\n---\n\n"

    /** The highlighted substrings that carry [key], in document order. */
    private fun highlighted(text: String, key: TextAttributesKey): List<String> {
        myFixture.configureByText("deck.md", text)
        val document = myFixture.editor.document.text
        return myFixture.doHighlighting()
            .filter { it.forcedTextAttributesKey == key }
            .sortedBy(HighlightInfo::getStartOffset)
            .map { document.substring(it.startOffset, it.endOffset) }
    }

    fun testKeyAndValueOfADirectiveComment() {
        val text = "$deck<!-- _class: lead -->\n\n# Hello\n"
        assertEquals(listOf("_class"), highlighted(text, MarpDirectiveHighlighting.KEY))
        assertEquals(listOf("lead"), highlighted(text, MarpDirectiveHighlighting.VALUE))
        assertEmpty(highlighted(text, MarpDirectiveHighlighting.NOTE))
    }

    fun testEveryLineOfAMultiLineDirectiveComment() {
        val text = "$deck<!--\n_class: lead\npaginate: true\n-->\n"
        assertEquals(listOf("_class", "paginate"), highlighted(text, MarpDirectiveHighlighting.KEY))
        assertEquals(listOf("lead", "true"), highlighted(text, MarpDirectiveHighlighting.VALUE))
    }

    fun testInlineCommentInAParagraph() {
        val text = "${deck}Some text <!-- _paginate: skip --> more text\n"
        assertEquals(listOf("_paginate"), highlighted(text, MarpDirectiveHighlighting.KEY))
        assertEquals(listOf("skip"), highlighted(text, MarpDirectiveHighlighting.VALUE))
    }

    fun testPresenterNoteCoversTheWholeComment() {
        val text = "$deck<!-- Say hello, then show the demo. -->\n"
        assertEquals(listOf("<!-- Say hello, then show the demo. -->"), highlighted(text, MarpDirectiveHighlighting.NOTE))
        assertEmpty(highlighted(text, MarpDirectiveHighlighting.KEY))
    }

    fun testNoteThatLooksLikeAMappingIsANote() {
        val text = "$deck<!-- Note: hello -->\n"
        assertEquals(listOf("<!-- Note: hello -->"), highlighted(text, MarpDirectiveHighlighting.NOTE))
        assertEmpty(highlighted(text, MarpDirectiveHighlighting.KEY))
    }

    fun testGlobalWithUnderscoreIsStillShownAsADirective() {
        val text = "$deck<!-- _theme: gaia -->\n"
        assertEquals(listOf("_theme"), highlighted(text, MarpDirectiveHighlighting.KEY))
        assertEmpty(highlighted(text, MarpDirectiveHighlighting.NOTE))
    }

    fun testMagicAndEmptyCommentsKeepTheCommentColor() {
        val text = "$deck<!-- prettier-ignore -->\n\n<!-- markdownlint-disable MD033 -->\n\n<!---->\n\n# Title <!-- fit -->\n"
        assertEmpty(highlighted(text, MarpDirectiveHighlighting.KEY))
        assertEmpty(highlighted(text, MarpDirectiveHighlighting.NOTE))
    }

    fun testFitOutsideAHeadingIsANote() {
        val text = "$deck<!-- fit -->\n"
        assertEquals(listOf("<!-- fit -->"), highlighted(text, MarpDirectiveHighlighting.NOTE))
    }

    fun testNothingInPlainMarkdown() {
        val text = "# Not a deck\n\n<!-- _class: lead -->\n\n<!-- a note -->\n"
        assertEmpty(highlighted(text, MarpDirectiveHighlighting.KEY))
        assertEmpty(highlighted(text, MarpDirectiveHighlighting.VALUE))
        assertEmpty(highlighted(text, MarpDirectiveHighlighting.NOTE))
    }

    fun testNothingInsideACodeBlock() {
        val text = "$deck```html\n<!-- _class: lead -->\n```\n"
        assertEmpty(highlighted(text, MarpDirectiveHighlighting.KEY))
        assertEmpty(highlighted(text, MarpDirectiveHighlighting.NOTE))
    }

    fun testColorSettingsPageListsAllThreeColors() {
        val page = MarpColorSettingsPage()
        assertEquals("Marp", page.displayName)
        assertEquals(
            setOf(MarpDirectiveHighlighting.KEY, MarpDirectiveHighlighting.VALUE, MarpDirectiveHighlighting.NOTE),
            page.attributeDescriptors.map { it.key }.toSet(),
        )
        assertEquals(page.additionalHighlightingTagToDescriptorMap.keys, setOf("key", "value", "note"))
        for (tag in page.additionalHighlightingTagToDescriptorMap.keys) {
            assertTrue(tag, page.demoText.contains("<$tag>"))
        }
    }
}
