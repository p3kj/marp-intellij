package cz.p3kj.marp.navigation

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import cz.p3kj.marp.MarpBundle
import cz.p3kj.marp.MarpLightTestCase
import cz.p3kj.marp.slides.MarpDeck

class MarpSlideNavigationTest : MarpLightTestCase() {

    private val deck = """
        ---
        marp: true
        ---

        # One

        ---

        # Two

        ---

        # Three
    """.trimIndent()

    private fun open(text: String = deck, name: String = "deck.md") {
        myFixture.configureByText(name, text)
    }

    private fun deckOf(): MarpDeck = runReadActionBlocking { MarpSlideNavigation.deckFor(project, myFixture.editor.document)!! }

    private fun caret(offset: Int) {
        myFixture.editor.caretModel.moveToOffset(offset)
    }

    private fun move(delta: Int) {
        PlatformTestUtil.waitForPromise(MarpSlideNavigation.move(project, myFixture.editor, delta))
    }

    private fun caretOffset(): Int = myFixture.editor.caretModel.offset

    private fun offsetOf(marker: String): Int = myFixture.editor.document.text.indexOf(marker)

    // Next and Previous ---------------------------------------------------------------------------------------------

    fun testNextMovesToTheContentOfTheFollowingSlide() {
        open()
        caret(offsetOf("# One"))
        move(+1)
        assertEquals(offsetOf("# Two"), caretOffset())
        assertEquals(deckOf().slides[1].contentOffset, caretOffset())
        move(+1)
        assertEquals(offsetOf("# Three"), caretOffset())
    }

    fun testPreviousMovesBack() {
        open()
        caret(offsetOf("# Three"))
        move(-1)
        assertEquals(offsetOf("# Two"), caretOffset())
        move(-1)
        assertEquals(offsetOf("# One"), caretOffset())
    }

    fun testNextFromTheFrontMatterGoesToTheSecondSlide() {
        open()
        caret(0)
        move(+1)
        assertEquals(offsetOf("# Two"), caretOffset())
    }

    fun testNextFromASeparatorLineGoesToTheSlideAfterThatSeparator() {
        open()
        caret(offsetOf("---\n\n# Two"))
        move(+1)
        assertEquals(offsetOf("# Three"), caretOffset())
    }

    fun testMovesStayInsideTheDeck() {
        open()
        caret(offsetOf("# Three") + 3)
        move(+1)
        assertEquals("Next at the last slide leaves the caret", offsetOf("# Three") + 3, caretOffset())
        caret(offsetOf("# One") + 2)
        move(-1)
        assertEquals("Previous at the first slide leaves the caret", offsetOf("# One") + 2, caretOffset())
    }

    fun testMoveDropsTheSelectionAndSecondaryCarets() {
        open()
        val editor = myFixture.editor
        editor.selectionModel.setSelection(offsetOf("# One"), offsetOf("# One") + 5)
        editor.caretModel.addCaret(editor.offsetToVisualPosition(offsetOf("# Three")))
        assertEquals(2, editor.caretModel.caretCount)
        caret(offsetOf("# One"))
        move(+1)
        assertEquals(1, editor.caretModel.caretCount)
        assertFalse(editor.selectionModel.hasSelection())
        assertEquals(offsetOf("# Two"), caretOffset())
    }

    fun testHeadingDividerSlidesAreFollowed() {
        open(
            """
            ---
            marp: true
            headingDivider: 2
            ---

            # A

            ## B

            text

            ## C
            """.trimIndent(),
        )
        caret(offsetOf("# A"))
        move(+1)
        assertEquals(offsetOf("## B"), caretOffset())
        move(+1)
        assertEquals(offsetOf("## C"), caretOffset())
    }

    fun testUncommittedEditIsSeen() {
        open()
        val document = myFixture.editor.document
        caret(offsetOf("# One"))
        // No commit: the new slide is only in the document, the navigation waits for the PSI to catch up.
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(offsetOf("# One") + "# One\n".length, "\n---\n\n# Inserted\n")
        }
        move(+1)
        assertEquals(offsetOf("# Inserted"), caretOffset())
    }

    fun testNothingHappensOutsideAMarpDeck() {
        open("# One\n\n---\n\n# Two\n", "plain.md")
        caret(0)
        move(+1)
        assertEquals(0, caretOffset())
    }

    fun testTargetIndexClamps() {
        open()
        val deck = deckOf()
        assertEquals(0, MarpSlideNavigation.targetIndex(deck, 0, -1))
        assertEquals(1, MarpSlideNavigation.targetIndex(deck, 0, +1))
        assertEquals(2, MarpSlideNavigation.targetIndex(deck, offsetOf("# Two"), +1))
        assertEquals(2, MarpSlideNavigation.targetIndex(deck, offsetOf("# Three"), +1))
        assertEquals(0, MarpSlideNavigation.targetIndex(deck, offsetOf("# Two"), -1))
        assertEquals(2, MarpSlideNavigation.targetIndex(deck, 0, +10))
    }

    // Go to Slide ---------------------------------------------------------------------------------------------------

    fun testParseSlideNumber() {
        assertEquals(2, MarpSlideNavigation.parseSlideNumber("3", 3))
        assertEquals(2, MarpSlideNavigation.parseSlideNumber(" 3 ", 3))
        assertEquals(0, MarpSlideNavigation.parseSlideNumber("1", 3))
        assertNull(MarpSlideNavigation.parseSlideNumber("0", 3))
        assertNull(MarpSlideNavigation.parseSlideNumber("4", 3))
        assertNull(MarpSlideNavigation.parseSlideNumber("-1", 3))
        assertNull(MarpSlideNavigation.parseSlideNumber("x", 3))
        assertNull(MarpSlideNavigation.parseSlideNumber("2x", 3))
        assertNull(MarpSlideNavigation.parseSlideNumber("", 3))
        assertNull(MarpSlideNavigation.parseSlideNumber(null, 3))
    }

    /** Runs [block] with an input dialog that answers [answer] and records what it was asked. */
    private fun withDialog(answer: String?, block: (asked: MutableList<String?>) -> Unit) {
        val asked = ArrayList<String?>()
        val previous = TestDialogManager.setTestInputDialog(object : TestInputDialog {
            override fun show(message: String): String? = answer

            override fun show(message: String, validator: InputValidator?): String? {
                val ex = validator as InputValidatorEx
                asked += message
                asked += ex.getErrorText("2")
                asked += ex.getErrorText("9")
                asked += ex.getErrorText("")
                return answer
            }
        })
        try {
            block(asked)
        } finally {
            TestDialogManager.setTestInputDialog(previous)
        }
    }

    fun testDialogAsksForANumberAndValidatesIt() {
        withDialog("2") { asked ->
            assertEquals(1, MarpSlideNavigation.askForSlide(project, 3, 1))
            assertEquals(
                listOf<String?>(
                    MarpBundle.message("dialog.goToSlide.message", 3),
                    null,
                    MarpBundle.message("dialog.goToSlide.error", 3),
                    MarpBundle.message("dialog.goToSlide.error", 3),
                ),
                asked,
            )
        }
    }

    fun testDialogCancelledOrInvalidAnswerMovesNothing() {
        withDialog(null) { assertNull(MarpSlideNavigation.askForSlide(project, 3, 1)) }
        withDialog("7") { assertNull(MarpSlideNavigation.askForSlide(project, 3, 1)) }
    }

    /** Runs Go to Slide with a dialog that answers [answer], then waits for the caret to reach [expected]. */
    private fun goToSlide(answer: String?, expected: Int) {
        withDialog(answer) {
            PlatformTestUtil.waitForPromise(MarpSlideNavigation.showGoToSlide(project, myFixture.editor))
            waitForCaret(expected)
        }
    }

    private fun waitForCaret(offset: Int) {
        PlatformTestUtil.waitWithEventsDispatching("Caret did not reach $offset, is at ${caretOffset()}", { caretOffset() == offset }, 10)
    }

    fun testGoToSlideMovesTheCaret() {
        open()
        caret(offsetOf("# One"))
        goToSlide("3", offsetOf("# Three"))
    }

    fun testGoToSlideCanGoBackToTheFirstSlide() {
        open()
        caret(offsetOf("# Three"))
        goToSlide("1", offsetOf("# One"))
    }

    // Actions -------------------------------------------------------------------------------------------------------

    private fun contextOf(): DataContext = SimpleDataContext.builder()
        .add(CommonDataKeys.PROJECT, project)
        .add(CommonDataKeys.EDITOR, myFixture.editor)
        .add(CommonDataKeys.PSI_FILE, myFixture.file)
        .build()

    private fun action(id: String): AnAction = ActionManager.getInstance().getAction(id)

    fun testActionsAreOfferedInADeck() {
        open()
        for (id in listOf("Marp.NextSlide", "Marp.PreviousSlide", "Marp.GoToSlide")) {
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
        for (id in listOf("Marp.NextSlide", "Marp.PreviousSlide", "Marp.GoToSlide")) {
            val action = action(id)
            val event = TestActionEvent.createTestEvent(action, contextOf())
            action.update(event)
            assertFalse("$id enabled", event.presentation.isEnabled)
            assertFalse("$id visible", event.presentation.isVisible)
        }
    }

    fun testActionsNeedAnEditor() {
        open()
        val action = action("Marp.NextSlide")
        val event = TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(project))
        action.update(event)
        assertFalse(event.presentation.isEnabledAndVisible)
    }

    fun testNextSlideActionMovesTheCaret() {
        open()
        caret(offsetOf("# One"))
        val next = action("Marp.NextSlide")
        next.actionPerformed(TestActionEvent.createTestEvent(next, contextOf()))
        waitForCaret(offsetOf("# Two"))
        val previous = action("Marp.PreviousSlide")
        previous.actionPerformed(TestActionEvent.createTestEvent(previous, contextOf()))
        waitForCaret(offsetOf("# One"))
    }

    fun testShortcuts() {
        val keymaps = KeymapManager.getInstance()
        val default = keymaps.getKeymap("\$default")!!
        assertEquals(1, default.getShortcuts("Marp.NextSlide").size)
        assertEquals(1, default.getShortcuts("Marp.PreviousSlide").size)
        assertEquals(0, default.getShortcuts("Marp.GoToSlide").size)
        // The two bundled keymaps that use the same strokes for something else get none of ours.
        for (name in listOf("NetBeans 6.5", "Visual Studio", "Visual Studio OSX")) {
            val keymap = keymaps.getKeymap(name) ?: continue
            assertEquals("$name Next", 0, keymap.getShortcuts("Marp.NextSlide").size)
            assertEquals("$name Previous", 0, keymap.getShortcuts("Marp.PreviousSlide").size)
        }
    }
}
