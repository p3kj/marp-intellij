package cz.p3kj.marp.directives

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.util.ThreeState
import cz.p3kj.marp.MarpBundle
import cz.p3kj.marp.MarpLightTestCase

/**
 * Completion in the front matter. The Markdown plugin injects YAML there when the YAML plugin is loaded (see
 * build.gradle.kts), so the caret is in an injected file like in the IDE; the generic schema of the Markdown plugin adds
 * keys such as `title`, so the tests look for the Marp keys and never for the exact list.
 */
class MarpFrontMatterCompletionTest : MarpLightTestCase() {

    private val head = "---\nmarp: true\n"

    /** The lookup strings after basic completion at `<caret>`, or `null` when completion inserted the only item. */
    private fun complete(text: String): List<String>? {
        myFixture.configureByText("deck.md", text)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings
    }

    private fun typeTextOf(element: LookupElement): String? = LookupElementPresentation().also { presentation -> element.renderElement(presentation) }.typeText

    /** The keys offered by this plugin (the other contributors add words and schema keys of their own), by their type text. */
    private fun marpKeys(): List<String> {
        val types = listOf("completion.type.global", "completion.type.local", "completion.type.spot", "completion.type.frontMatter").map { MarpBundle.message(it) }
        return myFixture.lookupElements.orEmpty()
            .filter { typeTextOf(it) in types }
            .map { it.lookupString }
    }

    fun testTheFrontMatterIsInjectedWithYaml() {
        // With the caret in the front matter the fixture hands out the injected YAML file, like the IDE does for completion.
        myFixture.configureByText("deck.md", "${head}pa<caret>\n---\n")
        val manager = InjectedLanguageManager.getInstance(project)
        assertTrue("YAML is injected into the front matter, is the YAML plugin loaded in tests?", manager.isInjectedFragment(myFixture.file))
        assertNotSame(myFixture.file, manager.getTopLevelFile(myFixture.file))
    }

    fun testOffersDirectiveNames() {
        val items = complete("${head}ba<caret>\n---\n")!!
        assertContainsElements(items, "backgroundColor", "backgroundImage", "backgroundSize")
        assertDoesntContain(items, "_backgroundColor")
    }

    fun testOffersEveryMarpKeyWithoutAPrefix() {
        val items = complete("${head}<caret>\n---\n")!!
        assertContainsElements(items, "theme", "class", "_class", "math", "size", "headingDivider", "_paginate", "paginate")
        assertDoesntContain(items, "marp", "_theme", "_math", "_size")
    }

    fun testOffersMarpOnTheLineThatHasIt() {
        // A deck always has `marp: true`, so the key is only offered where that line itself is edited.
        complete("---\nma<caret>rp: true\n---\n")
        assertContainsElements(marpKeys(), "marp", "math")
    }

    fun testDoesNotOfferMarpTwice() {
        complete("${head}ma<caret>\n---\n")
        assertContainsElements(marpKeys(), "math")
        assertDoesntContain(marpKeys(), "marp")
    }

    fun testMarpKeyIsMarkedAsFrontMatterOnly() {
        complete("---\nma<caret>rp: true\n---\n")
        val marp = myFixture.lookupElements!!.first { it.lookupString == "marp" }
        assertEquals("front matter", typeTextOf(marp))
    }

    fun testOffersSpotDirectives() {
        val items = complete("${head}_c<caret>\n---\n")
        if (items == null) myFixture.checkResult("${head}_class: <caret>\n---\n")
        else assertContainsElements(items, "_class", "_color")
        assertDoesntContain(items ?: emptyList(), "class", "color")
    }

    fun testInsertingAKeyAddsAColonAndASpace() {
        complete("${head}pagi<caret>\n---\n")
        myFixture.checkResult("${head}paginate: <caret>\n---\n")
    }

    fun testInsertingAKeyOfAnEntryThatHasAColonMovesBehindIt() {
        myFixture.configureByText("deck.md", "${head}pagi<caret>: true\n---\n")
        myFixture.completeBasic()
        myFixture.checkResult("${head}paginate: <caret>true\n---\n")
    }

    fun testOffersPaginateValues() {
        val items = complete("${head}paginate: <caret>\n---\n")!!
        assertEquals(listOf("true", "false", "hold", "skip"), items)
    }

    fun testFiltersValuesByPrefix() {
        val items = complete("${head}backgroundRepeat: repeat-<caret>\n---\n")!!
        assertEquals(listOf("repeat-x", "repeat-y"), items)
    }

    fun testOffersBuiltInThemes() {
        val items = complete("${head}theme: <caret>\n---\n")!!
        assertContainsElements(items, "default", "gaia", "uncover")
    }

    fun testOffersValuesOfASpotDirective() {
        val items = complete("${head}_class: <caret>\n---\n")!!
        assertEquals(listOf("lead", "invert"), items)
    }

    fun testOffersTheMarpValue() {
        // Only a deck completes, and the value has to be `true` for that, so this edits the existing value.
        val items = complete("---\nmarp: t<caret>rue\n---\n")
        if (items == null) assertTrue(myFixture.editor.document.text.startsWith("---\nmarp: true"))
        else assertEquals(listOf("true"), items)
    }

    fun testNoValuesForFreeTextDirectives() {
        val items = complete("${head}header: <caret>\n---\n")
        assertTrue(items.isNullOrEmpty() || !items.contains("true"))
    }

    fun testNoValuesForUnknownKeys() {
        val items = complete("${head}author: <caret>\n---\n")
        assertTrue(items.isNullOrEmpty())
    }

    fun testAKeyThatIsAlreadyThereIsNotOfferedAgain() {
        complete("${head}theme: gaia\nt<caret>\n---\n")
        assertDoesntContain(marpKeys(), "theme")
    }

    fun testOtherKeysAreStillOfferedNextToOne() {
        complete("${head}theme: gaia\nh<caret>\n---\n")
        assertContainsElements(marpKeys(), "header", "headingDivider")
    }

    fun testTheKeyThatIsBeingEditedIsStillOffered() {
        // The line with the caret does not count.
        complete("${head}th<caret>eme: gaia\n---\n")
        assertContainsElements(marpKeys(), "theme")
    }

    fun testSpotFormsThatAreAlreadyThereAreNotOfferedAgain() {
        complete("${head}_class: lead\n_<caret>\n---\n")
        assertContainsElements(marpKeys(), "_paginate")
        assertDoesntContain(marpKeys(), "_class")
    }

    fun testNothingOnAnIndentedLine() {
        val items = complete("${head}images:\n  ba<caret>\n---\n")
        assertTrue(items.isNullOrEmpty() || !items.contains("backgroundColor"))
    }

    fun testNothingForAListItem() {
        val items = complete("${head}tags:\n  - ba<caret>\n---\n")
        assertTrue(items.isNullOrEmpty() || !items.contains("backgroundColor"))
    }

    fun testNothingInADeckWithoutTheFrontMatterKey() {
        val items = complete("---\nmarp: false\nba<caret>\n---\n")
        assertTrue(items.isNullOrEmpty() || !items.contains("backgroundColor"))
    }

    fun testNothingInPlainMarkdownFrontMatter() {
        val items = complete("---\ntitle: Post\nba<caret>\n---\n")
        assertTrue(items.isNullOrEmpty() || !items.contains("backgroundColor"))
    }

    fun testNothingAfterTheFrontMatter() {
        val items = complete("${head}---\n\nba<caret>")
        assertTrue(items.isNullOrEmpty() || !items.contains("backgroundColor"))
    }

    fun testTheOtherKeysOfTheFrontMatterStay() {
        // The Markdown plugin's schema keys are not taken away, plain YAML completion still runs next to ours.
        val items = complete("${head}ti<caret>\n---\n")
        if (items == null) myFixture.checkResult("${head}title<caret>\n---\n")
        else assertContainsElements(items, "title")
    }

    fun testCommentsStillWorkInADeckWithFrontMatter() {
        val items = complete("$head---\n\n<!-- _ba<caret> -->")!!
        assertContainsElements(items, "_backgroundColor")
    }

    private fun confidenceAtCaret(textWithCaret: String): ThreeState {
        myFixture.configureByText("deck.md", textWithCaret)
        val offset = myFixture.caretOffset
        val element = myFixture.file.findElementAt(maxOf(0, offset - 1))!!
        return MarpDirectiveCompletionConfidence().shouldSkipAutopopup(myFixture.editor, element, myFixture.file, offset)
    }

    fun testConfidenceOpensForKeysAndValuesWithChoices() {
        assertEquals(ThreeState.NO, confidenceAtCaret("${head}pa<caret>\n---\n"))
        assertEquals(ThreeState.NO, confidenceAtCaret("${head}paginate: h<caret>\n---\n"))
        assertEquals(ThreeState.NO, confidenceAtCaret("${head}theme: g<caret>\n---\n"))
    }

    fun testConfidenceLeavesOtherValuesToYaml() {
        assertEquals(ThreeState.UNSURE, confidenceAtCaret("${head}header: H<caret>\n---\n"))
        assertEquals(ThreeState.UNSURE, confidenceAtCaret("${head}author: A<caret>\n---\n"))
        assertEquals(ThreeState.UNSURE, confidenceAtCaret("${head}images:\n  - a<caret>\n---\n"))
    }

    fun testConfidenceLeavesTheRestOfTheDocumentAlone() {
        assertEquals(ThreeState.UNSURE, confidenceAtCaret("${head}---\n\nSome text<caret>"))
        assertEquals(ThreeState.UNSURE, confidenceAtCaret("---\ntitle: x\npa<caret>\n---\n"))
    }
}
