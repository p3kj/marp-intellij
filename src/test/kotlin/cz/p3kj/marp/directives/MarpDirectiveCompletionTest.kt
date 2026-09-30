package cz.p3kj.marp.directives

import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.util.ThreeState
import cz.p3kj.marp.MarpLightTestCase

class MarpDirectiveCompletionTest : MarpLightTestCase() {

    private val deck = "---\nmarp: true\n---\n\n"

    /** The lookup strings after basic completion at `<caret>`, or `null` when completion inserted the only item. */
    private fun complete(text: String): List<String>? {
        myFixture.configureByText("deck.md", text)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings
    }

    /** The type text (the right side of the popup) of the item called [name] in the lookup that is open. */
    private fun typeTextOf(name: String): String? {
        val element = myFixture.lookupElements!!.first { it.lookupString == name }
        return LookupElementPresentation().also { element.renderElement(it) }.typeText
    }

    fun testOffersMatchingSpotDirectives() {
        val items = complete("$deck<!-- _ba<caret> -->")!!
        assertEquals(
            listOf("_backgroundColor", "_backgroundImage", "_backgroundPosition", "_backgroundRepeat", "_backgroundSize"),
            items.sorted(),
        )
    }

    fun testOffersEveryDirectiveWithoutAPrefix() {
        val items = complete("$deck<!-- <caret> -->")!!
        assertContainsElements(items, "theme", "class", "_class", "math", "size", "headingDivider", "_paginate")
        assertDoesntContain(items, "_theme", "_math", "_size")
        assertEquals(16 + 10, items.size)
    }

    fun testItemsShowWhereTheDirectiveApplies() {
        complete("$deck<!-- <caret> -->")
        assertEquals("whole deck", typeTextOf("theme"))
        assertEquals("whole deck", typeTextOf("headingDivider"))
        assertEquals("this and following slides", typeTextOf("class"))
        assertEquals("this and following slides", typeTextOf("paginate"))
        assertEquals("this slide only", typeTextOf("_class"))
        assertEquals("this slide only", typeTextOf("_paginate"))
    }

    fun testSpotItemsShowThatTheyApplyToOneSlide() {
        complete("$deck<!-- _c<caret> -->")
        assertEquals("this slide only", typeTextOf("_class"))
        assertEquals("this slide only", typeTextOf("_color"))
    }

    fun testPlainItemsShowThatTheyApplyFromTheSlideOn() {
        complete("$deck<!-- back<caret> -->")
        assertEquals("this and following slides", typeTextOf("backgroundColor"))
        assertNull(myFixture.lookupElements!!.firstOrNull { it.lookupString == "_backgroundColor" })
    }

    fun testEachSpotDirectiveSitsRightBehindItsPlainName() {
        val items = complete("$deck<!-- <caret> -->")!!
        val expected = MarpDirectiveCatalog.ALL.flatMap { directive ->
            if (directive.scope == MarpDirectiveScope.LOCAL) listOf(directive.name, "_" + directive.name) else listOf(directive.name)
        }
        assertEquals(expected, items)
        assertEquals(items.indexOf("class") + 1, items.indexOf("_class"))
    }

    fun testInsertingAKeyAddsAColonAndASpace() {
        complete("$deck<!-- _pa<caret> -->")
        myFixture.checkResult("$deck<!-- _paginate: <caret> -->")
    }

    fun testInsertingAKeyInAMultiLineComment() {
        complete("$deck<!--\n_class: lead\nfoot<caret>\n-->")
        myFixture.checkResult("$deck<!--\n_class: lead\nfooter: <caret>\n-->")
    }

    fun testReplacingAKeyThatAlreadyHasAColon() {
        myFixture.configureByText("deck.md", "$deck<!-- _pa<caret>: true -->")
        myFixture.completeBasic()
        myFixture.checkResult("$deck<!-- _paginate: <caret>true -->")
    }

    fun testOffersPaginateValues() {
        val items = complete("$deck<!-- paginate: <caret> -->")!!
        assertEquals(listOf("true", "false", "hold", "skip"), items)
    }

    fun testFiltersValuesByPrefix() {
        val items = complete("$deck<!-- backgroundRepeat: repeat-<caret> -->")!!
        assertEquals(listOf("repeat-x", "repeat-y"), items)
    }

    fun testOffersBuiltInThemes() {
        val items = complete("$deck<!-- theme: <caret> -->")!!
        assertContainsElements(items, "default", "gaia", "uncover")
    }

    fun testOffersValuesOfASpotDirective() {
        val items = complete("$deck<!-- _class: <caret> -->")!!
        assertEquals(listOf("lead", "invert"), items)
    }

    fun testNoValuesForFreeTextDirectives() {
        val items = complete("$deck<!-- header: <caret> -->")
        assertTrue(items.isNullOrEmpty())
    }

    fun testInlineCommentInAParagraph() {
        val items = complete("${deck}Some text <!-- paginate: <caret> --> more text")!!
        assertEquals(listOf("true", "false", "hold", "skip"), items)
    }

    fun testUnknownKeyHasNoValues() {
        val items = complete("$deck<!-- Note: <caret> -->")
        assertTrue(items.isNullOrEmpty())
    }

    fun testNothingInAPresenterNoteSentence() {
        val items = complete("$deck<!-- Say hello, then pag<caret> -->")
        assertTrue(items.isNullOrEmpty())
    }

    fun testNothingInPlainMarkdown() {
        val items = complete("# Not a deck\n\n<!-- <caret> -->")
        assertTrue(items.isNullOrEmpty())
        myFixture.checkResult("# Not a deck\n\n<!--  -->")
    }

    fun testNothingOutsideAComment() {
        val items = complete("${deck}pag<caret>")
        assertTrue(items.isNullOrEmpty())
    }

    private fun confidenceAtCaret(textWithCaret: String): ThreeState {
        myFixture.configureByText("deck.md", textWithCaret)
        val offset = myFixture.caretOffset
        val element = myFixture.file.findElementAt(maxOf(0, offset - 1))!!
        return MarpDirectiveCompletionConfidence().shouldSkipAutopopup(myFixture.editor, element, myFixture.file, offset)
    }

    fun testConfidenceOpensWhereADirectiveIsWritten() {
        assertEquals(ThreeState.NO, confidenceAtCaret("$deck<!-- _cla<caret> -->"))
        assertEquals(ThreeState.NO, confidenceAtCaret("$deck<!-- paginate: h<caret> -->"))
    }

    fun testConfidenceSkipsNotes() {
        assertEquals(ThreeState.YES, confidenceAtCaret("$deck<!-- pag<caret> -->"))
        assertEquals(ThreeState.YES, confidenceAtCaret("$deck<!-- Say hello, then<caret> -->"))
    }

    fun testConfidenceLeavesTheCaretOutsideTheCommentAlone() {
        assertEquals(ThreeState.UNSURE, confidenceAtCaret("$deck<!-- _class: lead --><caret>"))
        assertEquals(ThreeState.UNSURE, confidenceAtCaret("$deck<caret><!-- _class: lead -->"))
        assertEquals(ThreeState.UNSURE, confidenceAtCaret("${deck}Some text<caret>"))
        assertEquals(ThreeState.UNSURE, confidenceAtCaret("# Not a deck\n\n<!-- _cla<caret> -->"))
    }
}
