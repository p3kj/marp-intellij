package cz.p3kj.marp.images

import com.intellij.testFramework.fixtures.CompletionAutoPopupTester
import cz.p3kj.marp.MarpLightTestCase

/**
 * Typing in the alt text of an image: the completion popup opens where the alt text holds nothing but keywords, and
 * stays away from a description.
 */
class MarpImageAutoPopupTest : MarpLightTestCase() {

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

    fun testOpensForTheFirstWord() {
        val items = popupAfterTyping("$deck![<caret>", "b")
        assertNotNull(items)
        assertContainsElements(items!!, "bg", "blur", "brightness")
    }

    fun testOpensAfterKeywords() {
        val items = popupAfterTyping("$deck![bg <caret>", "co")
        assertNotNull(items)
        assertContainsElements(items!!, "contain", "contrast", "cover")
        assertDoesntContain(items, "bg")
    }

    fun testOpensInAnImageThatHasAUrl() {
        val items = popupAfterTyping("$deck![bg <caret>](x.png)", "co")
        assertNotNull(items)
        assertContainsElements(items!!, "contain", "cover")
    }

    fun testStaysAwayFromADescription() {
        assertNull(popupAfterTyping("$deck![A photo <caret>", "b"))
        assertNull(popupAfterTyping("$deck![A photo <caret>](x.png)", "b"))
    }

    fun testStaysAwayOnceTheAltTextHasAWordThatIsNoKeyword() {
        assertNull(popupAfterTyping("$deck![bg photo <caret>", "b"))
    }

    fun testStaysAwayFromCapitalisedText() {
        assertNull(popupAfterTyping("$deck![<caret>", "Diagram"))
    }

    fun testStaysAwayOutsideTheAltText() {
        assertNull(popupAfterTyping("$deck![x](<caret>", "b"))
        assertNull(popupAfterTyping("$deck<caret>", "b"))
    }

    fun testStaysAwayInCode() {
        assertNull(popupAfterTyping("$deck```md\n![<caret>\n```", "b"))
    }

    fun testStaysAwayInPlainMarkdown() {
        assertNull(popupAfterTyping("# Not a deck\n\n![<caret>", "b"))
    }
}
