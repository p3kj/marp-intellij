package cz.p3kj.marp.images

import com.intellij.codeInsight.lookup.Lookup
import cz.p3kj.marp.MarpLightTestCase

class MarpImageCompletionTest : MarpLightTestCase() {

    private val deck = "---\nmarp: true\n---\n\n"

    /** The lookup strings after basic completion at `<caret>`, or `null` when completion inserted the only item. */
    private fun complete(text: String): List<String>? {
        myFixture.configureByText("deck.md", text)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings
    }

    /** Completion at `<caret>` offers none of our keywords. Plain word completion may still offer other words. */
    private fun assertNoKeywords(text: String) {
        myFixture.configureByText("deck.md", text)
        myFixture.completeBasic()
        assertDoesntContain(myFixture.lookupElementStrings.orEmpty(), "bg", "blur", "brightness", "w:", "sepia")
    }

    fun testOffersTheKeywordsForAPrefix() {
        val items = complete("$deck![b<caret>")!!
        assertContainsElements(items, "bg", "blur", "brightness")
        assertDoesntContain(items, "left", "cover", "w:")
    }

    fun testTheBackgroundComesFirst() {
        val items = complete("$deck![b<caret>")!!
        assertEquals(listOf("bg", "blur", "brightness"), items)
    }

    fun testOffersEveryKeywordWithoutAPrefix() {
        val items = complete("$deck![<caret>")!!
        assertEquals(MarpImageKeywordCatalog.ALL.filter { !it.backgroundOnly }.map { it.lookupString }, items)
    }

    fun testOffersTheBackgroundKeywordsAfterBg() {
        val items = complete("$deck![bg <caret>](x.png)")!!
        assertContainsElements(items, "left", "right", "fit", "contain", "cover", "auto", "vertical", "horizontal", "w:", "height:", "sepia")
        assertDoesntContain(items, "bg")
        assertEquals(MarpImageKeywordCatalog.ALL.drop(1).map { it.lookupString }, items)
    }

    fun testBgMayComeAfterTheWordBeingCompleted() {
        // The platform matches inside words too (le is in grayscale), the keyword that starts with it ranks first.
        val items = complete("$deck![le<caret> bg](x.png)")!!
        assertEquals("left", items.first())
        assertContainsElements(items, "grayscale")
    }

    fun testInsertsAKeywordWithoutAValue() {
        assertNull(complete("$deck![bg lef<caret>](x.png)"))
        myFixture.checkResult("$deck![bg left<caret>](x.png)")
    }

    fun testInsertsAFilterWithoutAnArgument() {
        assertNull(complete("$deck![sep<caret>](x.png)"))
        myFixture.checkResult("$deck![sepia<caret>](x.png)")
    }

    fun testInsertsASizeKeywordWithAColon() {
        val items = complete("$deck![w<caret>](x.png)")!!
        // The platform matches inside words too (w ends drop-shadow), the keywords that start with it rank first.
        assertEquals(listOf("w:", "width:"), items.take(2))
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        myFixture.checkResult("$deck![w:<caret>](x.png)")
    }

    fun testLeavesOutTheKeywordsTheAltTextHas() {
        val items = complete("$deck![bg left blur:5px <caret>](x.png)")!!
        assertDoesntContain(items, "bg", "left", "blur")
        assertContainsElements(items, "right", "cover", "brightness", "w:")
    }

    fun testCompletesInTheMiddleOfAWord() {
        val items = complete("$deck![bg c<caret>ver](x.png)")!!
        assertContainsElements(items, "cover", "contain", "contrast")
    }

    fun testOffersTheKeywordsAfterTheDescriptionOnDemand() {
        val items = complete("$deck![A photo b<caret>](x.png)")!!
        assertContainsElements(items, "bg", "blur")
    }

    fun testNothingInTheUrlOrAfterTheImage() {
        assertNoKeywords("$deck![x](b<caret>)")
        assertNoKeywords("$deck![x](x.png) b<caret>")
        assertNoKeywords("$deck[b<caret>](x.png)")
    }

    fun testNothingInACodeFence() {
        assertNoKeywords("$deck```md\n![b<caret>\n```")
    }

    fun testNothingInInlineCode() {
        assertNoKeywords("$deck`![b<caret>`")
    }

    fun testNothingInAnHtmlComment() {
        assertNoKeywords("$deck<!-- ![b<caret> -->")
    }

    fun testNothingInTheFrontMatter() {
        assertNoKeywords("---\nmarp: true\ntitle: ![b<caret>\n---\n")
    }

    fun testNothingInAMarkdownFileWithoutMarp() {
        assertNoKeywords("# Not a deck\n\n![b<caret>")
        assertNoKeywords("---\ntitle: Post\n---\n\n![b<caret>")
    }
}
