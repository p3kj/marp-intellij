package cz.p3kj.marp.navigation

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import cz.p3kj.marp.MarpBundle
import cz.p3kj.marp.MarpLightTestCase

class MarpSlideReorderingTest : MarpLightTestCase() {

    private val front = "---\nmarp: true\n---\n"
    private val original = "$front\n# One\n\n---\n\n# Two\n\n---\n\n# Three\n"

    private fun open(text: String, name: String = "deck.md") {
        myFixture.configureByText(name, text)
    }

    private fun move(down: Boolean) {
        PlatformTestUtil.waitForPromise(MarpSlideReordering.move(project, myFixture.editor, down))
    }

    private fun undo() {
        val editor = TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
        UndoManager.getInstance(project).undo(editor)
    }

    private fun text(): String = myFixture.editor.document.text

    // Moving --------------------------------------------------------------------------------------------------------------

    fun testDownSwapsWithTheNextSlideAndTheCaretFollows() {
        open("$front\n# One\n\n---\n\n# T<caret>wo\n\n---\n\n# Three\n")
        move(down = true)
        myFixture.checkResult("$front\n# One\n\n---\n\n# Three\n\n---\n\n# T<caret>wo\n")
    }

    fun testUpSwapsWithThePreviousSlideAndTheCaretFollows() {
        open("$front\n# One\n\n---\n\n# Two\n\n---\n\n# Thr<caret>ee\n")
        move(down = false)
        myFixture.checkResult("$front\n# One\n\n---\n\n# Thr<caret>ee\n\n---\n\n# Two\n")
    }

    fun testTheCaretCanBeAnywhereInTheSlideIncludingItsSeparatorLine() {
        open("$front\n# One\n\n<caret>---\n\n# Two\n\n---\n\n# Three\n")
        move(down = true)
        myFixture.checkResult("$front\n# One\n\n---\n\n# Three\n\n<caret>---\n\n# Two\n")
    }

    fun testFirstSlideKeepsTheFrontMatterAtTheTop() {
        open("$front\n# <caret>One\n\n---\n\n# Two\n\n---\n\n# Three\n")
        move(down = true)
        myFixture.checkResult("$front\n# Two\n\n---\n\n# <caret>One\n\n---\n\n# Three\n")
        move(down = false)
        myFixture.checkResult("$front\n# <caret>One\n\n---\n\n# Two\n\n---\n\n# Three\n")
    }

    fun testCaretInTheFrontMatterMovesTheFirstSlide() {
        open("$front\n# One\n\n---\n\n# Two\n")
        myFixture.editor.caretModel.moveToOffset(text().indexOf("true"))
        move(down = true)
        assertEquals("$front\n# Two\n\n---\n\n# One\n", text())
        assertEquals(text().indexOf("# One"), myFixture.editor.caretModel.offset)
    }

    fun testFirstSlideCannotMoveUpAndLastCannotMoveDown() {
        open("$front\n# O<caret>ne\n\n---\n\n# Two\n")
        move(down = false)
        myFixture.checkResult("$front\n# O<caret>ne\n\n---\n\n# Two\n")
        open("$front\n# One\n\n---\n\n# T<caret>wo\n")
        move(down = true)
        myFixture.checkResult("$front\n# One\n\n---\n\n# T<caret>wo\n")
    }

    fun testASelectionAndSecondaryCaretsAreDropped() {
        open(original)
        val editor = myFixture.editor
        editor.selectionModel.setSelection(text().indexOf("# Two"), text().indexOf("# Two") + 5)
        editor.caretModel.addCaret(editor.offsetToVisualPosition(text().indexOf("# Three")))
        editor.caretModel.primaryCaret.moveToOffset(text().indexOf("# Two"))
        assertEquals(2, editor.caretModel.caretCount)
        move(down = true)
        assertEquals(1, editor.caretModel.caretCount)
        assertFalse(editor.selectionModel.hasSelection())
        assertEquals("$front\n# One\n\n---\n\n# Three\n\n---\n\n# Two\n", text())
        assertEquals(text().indexOf("# Two"), editor.caretModel.offset)
    }

    fun testMoveIsOneUndoStep() {
        open("$front\n# One\n\n---\n\n# T<caret>wo\n\n---\n\n# Three\n")
        move(down = true)
        assertEquals("$front\n# One\n\n---\n\n# Three\n\n---\n\n# Two\n", text())
        undo()
        assertEquals(original, text())
    }

    fun testHeadingDividerDecksAreLeftAlone() {
        val deck = "---\nmarp: true\nheadingDivider: 2\n---\n\n# A\n\n## B\n\n## C\n"
        open(deck.replace("## B", "## <caret>B"))
        move(down = true)
        move(down = false)
        assertEquals(deck, text())
    }

    fun testNothingHappensOutsideAMarpDeck() {
        val plain = "# One\n\n---\n\n# Two\n"
        open(plain, "plain.md")
        move(down = true)
        assertEquals(plain, text())
    }

    // Moving to any place, the drag in the slide overview -----------------------------------------------------------------

    private val four = "$front\n# One\n\n---\n\n# Two\n\n---\n\n# Three\n\n---\n\n# Four\n"

    /** The editor line of [marker], like the line the overview reports for the slide that starts there. */
    private fun lineOf(marker: String): Int = myFixture.editor.document.getLineNumber(text().indexOf(marker))

    private fun moveSlide(from: Int, to: Int, line: Int, count: Int) {
        PlatformTestUtil.waitForPromise(MarpSlideReordering.moveSlide(project, myFixture.editor, from, to, line, count))
    }

    fun testMoveSlideMovesToAnyPlaceAndTheCaretGoesIntoTheMovedSlide() {
        open(four)
        moveSlide(1, 3, lineOf("# Two"), 4)
        assertEquals("$front\n# One\n\n---\n\n# Three\n\n---\n\n# Four\n\n---\n\n# Two\n", text())
        assertEquals(text().indexOf("---\n\n# Two"), myFixture.editor.caretModel.offset)
        moveSlide(3, 0, lineOf("# Two"), 4)
        assertEquals("$front\n# Two\n\n---\n\n# One\n\n---\n\n# Three\n\n---\n\n# Four\n", text())
        assertEquals(text().indexOf("# Two"), myFixture.editor.caretModel.offset)
    }

    fun testMoveSlideIsOneUndoStep() {
        open(four)
        moveSlide(0, 3, lineOf("# One"), 4)
        assertEquals("$front\n# Two\n\n---\n\n# Three\n\n---\n\n# Four\n\n---\n\n# One\n", text())
        undo()
        assertEquals(four, text())
    }

    fun testMoveSlideIgnoresARequestFromAnotherDeck() {
        open(four)
        // The line is in another slide than `from`.
        moveSlide(1, 3, lineOf("# Three"), 4)
        // The page showed a different number of slides.
        moveSlide(1, 3, lineOf("# Two"), 3)
        moveSlide(1, 3, lineOf("# Two"), 5)
        // The line is outside the document.
        moveSlide(1, 3, 1000, 4)
        moveSlide(1, 3, -1, 4)
        // Not two slides of the deck.
        moveSlide(1, 1, lineOf("# Two"), 4)
        moveSlide(1, 4, lineOf("# Two"), 4)
        moveSlide(4, 1, lineOf("# Two"), 4)
        assertEquals(four, text())
    }

    fun testMoveSlideUsesTheSlideTheLineIsInWhateverTheCaretIs() {
        open(four)
        myFixture.editor.caretModel.moveToOffset(text().indexOf("# Four"))
        // The line of the separator that starts the slide belongs to it.
        moveSlide(2, 0, lineOf("---\n\n# Three"), 4)
        assertEquals("$front\n# Three\n\n---\n\n# One\n\n---\n\n# Two\n\n---\n\n# Four\n", text())
    }

    fun testMoveSlideLeavesHeadingDividerDecksAlone() {
        val deck = "---\nmarp: true\nheadingDivider: 2\n---\n\n# A\n\n## B\n\n## C\n"
        open(deck)
        moveSlide(1, 2, lineOf("## B"), 3)
        moveSlide(2, 0, lineOf("## C"), 3)
        assertEquals(deck, text())
    }

    fun testMoveSlideLeavesAViewerAndPlainMarkdownAlone() {
        open(four)
        (myFixture.editor as EditorEx).isViewer = true
        moveSlide(1, 3, lineOf("# Two"), 4)
        assertEquals(four, text())
        val plain = "# One\n\n---\n\n# Two\n"
        open(plain, "plain.md")
        moveSlide(0, 1, 0, 2)
        assertEquals(plain, text())
    }

    // Actions -------------------------------------------------------------------------------------------------------------

    private fun contextOf(): DataContext = SimpleDataContext.builder()
        .add(CommonDataKeys.PROJECT, project)
        .add(CommonDataKeys.EDITOR, myFixture.editor)
        .add(CommonDataKeys.PSI_FILE, myFixture.file)
        .build()

    private fun action(id: String): AnAction = ActionManager.getInstance().getAction(id)

    private val ids = listOf("Marp.MoveSlideUp", "Marp.MoveSlideDown")

    fun testActionsAreOfferedInADeck() {
        open(original)
        for (id in ids) {
            val action = action(id)
            assertTrue("$id must be DumbAware", action is DumbAware)
            assertEquals(MarpBundle.message("action.$id.text"), action.templatePresentation.text)
            assertEquals(listOf(MarpBundle.message("action.$id.synonym")), action.synonyms.map { it.get() })
            val event = TestActionEvent.createTestEvent(action, contextOf())
            action.update(event)
            assertTrue("$id enabled", event.presentation.isEnabled)
            assertTrue("$id visible", event.presentation.isVisible)
        }
    }

    fun testActionsAreHiddenInPlainMarkdown() {
        open("# One\n\n---\n\n# Two\n", "plain.md")
        for (id in ids) {
            val action = action(id)
            val event = TestActionEvent.createTestEvent(action, contextOf())
            action.update(event)
            assertFalse("$id enabled", event.presentation.isEnabled)
            assertFalse("$id visible", event.presentation.isVisible)
        }
    }

    fun testActionsAreDisabledInAViewer() {
        open(original)
        (myFixture.editor as EditorEx).isViewer = true
        for (id in ids) {
            val action = action(id)
            val event = TestActionEvent.createTestEvent(action, contextOf())
            action.update(event)
            assertFalse("$id enabled", event.presentation.isEnabled)
        }
    }

    fun testActionsMoveTheSlide() {
        open("$front\n# One\n\n---\n\n# T<caret>wo\n\n---\n\n# Three\n")
        val down = action("Marp.MoveSlideDown")
        down.actionPerformed(TestActionEvent.createTestEvent(down, contextOf()))
        waitForText("$front\n# One\n\n---\n\n# Three\n\n---\n\n# Two\n")
        val up = action("Marp.MoveSlideUp")
        up.actionPerformed(TestActionEvent.createTestEvent(up, contextOf()))
        waitForText(original)
    }

    private fun waitForText(expected: String) {
        PlatformTestUtil.waitWithEventsDispatching("Text did not become the expected one, is ${text()}", { text() == expected }, 10)
    }

    fun testActionsHaveNoShortcutAndSitInTheCodeMenu() {
        val default = KeymapManager.getInstance().getKeymap("\$default")!!
        for (id in ids) assertEquals("$id shortcuts", 0, default.getShortcuts(id).size)
        val codeMenu = ActionManager.getInstance().getAction("CodeMenu") as DefaultActionGroup
        val group = action("Marp.SlideReordering")
        assertTrue(codeMenu.getChildActionsOrStubs().any { it === group })
        val children = (group as DefaultActionGroup).getChildActionsOrStubs().mapNotNull { ActionManager.getInstance().getId(it) }
        assertEquals(ids, children)
    }
}
