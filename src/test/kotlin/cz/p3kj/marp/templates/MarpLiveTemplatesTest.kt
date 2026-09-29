package cz.p3kj.marp.templates

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.runReadActionBlocking
import cz.p3kj.marp.MarpLightTestCase

class MarpLiveTemplatesTest : MarpLightTestCase() {

    private val deck = "---\nmarp: true\n---\n\n# One\n\n"

    /** Types [abbreviation] at the end of a deck, expands it with Tab and accepts every variable with its default. */
    private fun expand(abbreviation: String, variables: Int, expected: String) {
        myFixture.configureByText("deck.md", "$deck$abbreviation<caret>")
        myFixture.performEditorAction(IdeActions.ACTION_EXPAND_LIVE_TEMPLATE_BY_TAB)
        repeat(variables) { myFixture.performEditorAction(IdeActions.ACTION_EDITOR_NEXT_TEMPLATE_VARIABLE) }
        myFixture.checkResult("$deck$expected")
    }

    private fun inContext(fileName: String, text: String): Boolean {
        val file = myFixture.configureByText(fileName, text)
        return runReadActionBlocking { MarpTemplateContextType().isInContext(TemplateActionContext.expanding(file, text.length)) }
    }

    fun testSlide() = expand("slide", variables = 1, expected = "\n---\n\n## Title\n\n<caret>")

    fun testLead() = expand("lead", variables = 0, expected = "<!-- _class: lead -->\n<caret>")

    fun testBackgroundImage() = expand("bg", variables = 2, expected = "![bg right:40%]()<caret>")

    fun testNotes() = expand("notes", variables = 0, expected = "<!--\n<caret>\n-->")

    fun testTemplatesDoNotExpandInPlainMarkdown() {
        val text = "# Readme\n\nslide"
        myFixture.configureByText("readme.md", "$text<caret>")
        myFixture.performEditorAction(IdeActions.ACTION_EXPAND_LIVE_TEMPLATE_BY_TAB)
        myFixture.checkResult("$text<caret>")
    }

    fun testContextIsAMarpDeckOnly() {
        assertTrue(inContext("deck.md", "$deck-"))
        assertFalse(inContext("notes.txt", "$deck-"))
        assertFalse(inContext("readme.md", "# Readme\n\n-"))
        assertFalse(inContext("draft.md", "---\ntitle: Draft\n---\n\n-"))
    }
}
