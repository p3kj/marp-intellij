package cz.p3kj.marp.directives

import com.intellij.testFramework.fixtures.CompletionAutoPopupTester
import cz.p3kj.marp.MarpLightTestCase

/**
 * Typing in a comment or in the front matter: the completion popup opens where a directive is written and stays away
 * from presenter notes.
 */
class MarpDirectiveAutoPopupTest : MarpLightTestCase() {

    private val deck = "---\nmarp: true\n---\n\n"
    private lateinit var tester: CompletionAutoPopupTester

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        tester = CompletionAutoPopupTester(myFixture)
    }

    /** The popup items after typing [typed] at the caret of [text], `null` when no popup is open. */
    private fun popupAfterTyping(text: String, typed: String): List<String>? {
        var items: List<String>? = null
        tester.runWithAutoPopupEnabled {
            myFixture.configureByText("deck.md", text)
            tester.typeWithPauses(typed)
            tester.joinCompletion()
            items = myFixture.lookupElementStrings
        }
        return items
    }

    fun testOpensForASpotDirectiveName() {
        val items = popupAfterTyping("$deck<!-- <caret> -->", "_ba")
        assertNotNull(items)
        assertContainsElements(items!!, "_backgroundColor", "_backgroundSize")
    }

    fun testOpensForValuesAfterTheColon() {
        val items = popupAfterTyping("$deck<!-- paginate: <caret> -->", "h")
        assertEquals(listOf("hold"), items)
    }

    fun testOpensForANameInADirectiveComment() {
        val items = popupAfterTyping("$deck<!--\n_class: lead\n<caret>\n-->", "ba")
        assertNotNull(items)
        assertContainsElements(items!!, "backgroundColor")
    }

    fun testStaysAwayFromPresenterNotes() {
        assertNull(popupAfterTyping("$deck<!-- <caret> -->", "pag"))
        assertNull(popupAfterTyping("$deck<!-- Say <caret> -->", "pag"))
        assertNull(popupAfterTyping("$deck<!-- header: <caret> -->", "Hel"))
        assertNull(popupAfterTyping("$deck<!-- Note: <caret> -->", "h"))
    }

    fun testOpensForANameInTheFrontMatter() {
        val items = popupAfterTyping("---\nmarp: true\n<caret>\n---\n\n", "pa")
        assertNotNull(items)
        assertContainsElements(items!!, "paginate")
    }

    fun testOpensForAValueInTheFrontMatter() {
        val items = popupAfterTyping("---\nmarp: true\ntheme: <caret>\n---\n\n", "g")
        assertNotNull(items)
        assertContainsElements(items!!, "gaia")
    }

    fun testOpensForASpotNameInTheFrontMatter() {
        val items = popupAfterTyping("---\nmarp: true\n<caret>\n---\n\n", "_ba")
        assertNotNull(items)
        assertContainsElements(items!!, "_backgroundColor")
    }

    fun testStaysAwayFromOtherFrontMatterValues() {
        assertNull(popupAfterTyping("---\nmarp: true\nheader: <caret>\n---\n\n", "Hel"))
    }

    fun testStaysAwayFromTheTextAfterTheFrontMatter() {
        assertNull(popupAfterTyping("---\nmarp: true\n---\n\n<caret>", "pag"))
    }

    fun testStaysAwayFromPlainMarkdown() {
        assertNull(popupAfterTyping("# Not a deck\n\n<!-- <caret> -->", "_pag"))
    }
}
