package cz.p3kj.marp.directives

import cz.p3kj.marp.MarpLightTestCase

class MarpDirectiveCompletionTest : MarpLightTestCase() {

    private val deck = "---\nmarp: true\n---\n\n"

    /** The lookup strings after basic completion at `<caret>`, or `null` when completion inserted the only item. */
    private fun complete(text: String): List<String>? {
        myFixture.configureByText("deck.md", text)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings
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
}
