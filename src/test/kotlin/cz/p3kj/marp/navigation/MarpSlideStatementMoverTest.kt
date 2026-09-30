package cz.p3kj.marp.navigation

import com.intellij.codeInsight.editorActions.moveUpDown.LineMover
import com.intellij.codeInsight.editorActions.moveUpDown.StatementUpDownMover
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import cz.p3kj.marp.MarpLightTestCase

/**
 * Move Statement Up / Down through the editor actions, so the platform handler and the mover are exercised together.
 * On a separator line the whole slide moves, everywhere else the lines move as they do without the plugin.
 */
class MarpSlideStatementMoverTest : MarpLightTestCase() {

    private val front = "---\nmarp: true\n---\n"

    private fun open(text: String, name: String = "deck.md") {
        myFixture.configureByText(name, text)
    }

    private fun down() = myFixture.performEditorAction("MoveStatementDown")

    private fun up() = myFixture.performEditorAction("MoveStatementUp")

    private fun text(): String = myFixture.editor.document.text

    private fun undo() {
        UndoManager.getInstance(project).undo(TextEditorProvider.getInstance().getTextEditor(myFixture.editor))
    }

    fun testSeparatorLineMovesTheSlideDown() {
        open("$front\n# One\n\n---\n\n# Two\n\n<caret>---\n\n# Three\n\n---\n\n# Four\n")
        down()
        myFixture.checkResult("$front\n# One\n\n---\n\n# Two\n\n---\n\n# Four\n\n<caret>---\n\n# Three\n")
    }

    fun testSeparatorLineMovesTheSlideUp() {
        open("$front\n# One\n\n---\n\n# Two\n\n<caret>---\n\n# Three\n\n---\n\n# Four\n")
        up()
        myFixture.checkResult("$front\n# One\n\n<caret>---\n\n# Three\n\n---\n\n# Two\n\n---\n\n# Four\n")
    }

    fun testTheSlideKeepsMovingWhileTheCaretStaysOnItsSeparator() {
        open("$front\n# One\n\n---\n\n# Two\n\n<caret>---\n\n# Three\n\n---\n\n# Four\n")
        down()
        up()
        up()
        myFixture.checkResult("$front\n# One\n\n<caret>---\n\n# Three\n\n---\n\n# Two\n\n---\n\n# Four\n")
    }

    fun testSecondSlideUpSwapsBodiesAndKeepsTheFrontMatterFirst() {
        open("$front\n# One\n\n<caret>---\n\n# Two\n\n---\n\n# Three\n")
        up()
        myFixture.checkResult("$front\n<caret># Two\n\n---\n\n# One\n\n---\n\n# Three\n")
    }

    fun testLastSlideUpKeepsTheEndOfTheFile() {
        open("$front\n# One\n\n---\n\n# Two\n\n<caret>---\n\n# Three\n")
        up()
        myFixture.checkResult("$front\n# One\n\n<caret>---\n\n# Three\n\n---\n\n# Two\n")
    }

    fun testOtherSeparatorStylesMove() {
        open("$front\n# One\n\n***\n\n# Two\n\n<caret>___\n\n# Three\n\n---\n\n# Four\n")
        down()
        myFixture.checkResult("$front\n# One\n\n***\n\n# Two\n\n---\n\n# Four\n\n<caret>___\n\n# Three\n")
    }

    fun testMoveIsOneUndoStep() {
        val text = "$front\n# One\n\n---\n\n# Two\n\n---\n\n# Three\n"
        open(text.replace("---\n\n# Two", "<caret>---\n\n# Two"))
        down()
        assertEquals("$front\n# One\n\n---\n\n# Three\n\n---\n\n# Two\n", this.text())
        undo()
        assertEquals(text, this.text())
    }

    fun testLastSlideSeparatorDownMovesNothing() {
        val text = "$front\n# One\n\n---\n\n# Two\n\n<caret>---\n\n# Three\n"
        open(text)
        val plain = text.replace("<caret>", "")
        val caret = myFixture.editor.caretModel.offset
        down()
        assertEquals(plain, this.text())
        assertEquals(caret, myFixture.editor.caretModel.offset)
    }

    // Everywhere else the lines move ---------------------------------------------------------------------------------------

    fun testInsideASlideTheLinesMove() {
        open("$front\n# One\n\n---\n\n<caret># Two\nbody\n\n---\n\n# Three\n")
        down()
        myFixture.checkResult("$front\n# One\n\n---\n\nbody\n<caret># Two\n\n---\n\n# Three\n")
        up()
        myFixture.checkResult("$front\n# One\n\n---\n\n<caret># Two\nbody\n\n---\n\n# Three\n")
    }

    fun testInTheFirstSlideTheLinesMove() {
        open("$front\n# One\n<caret>body\n\n---\n\n# Two\n")
        up()
        myFixture.checkResult("$front\n<caret>body\n# One\n\n---\n\n# Two\n")
    }

    fun testASelectionOnASeparatorLineMovesLines() {
        open("$front\n# One\n\n<selection>---</selection>\n\n# Two\n\n---\n\n# Three\n")
        down()
        assertEquals("$front\n# One\n\n\n---\n# Two\n\n---\n\n# Three\n", text())
    }

    fun testMoveLineStillMovesTheSeparatorLineItself() {
        open("$front\n# One\n\n<caret>---\n\n# Two\n\n---\n\n# Three\n")
        myFixture.performEditorAction("MoveLineDown")
        assertEquals("$front\n# One\n\n\n---\n# Two\n\n---\n\n# Three\n", text())
    }

    fun testSecondaryCaretsFallBackToLines() {
        open("$front\n# One\n\n<caret>---\n\n# Two\n\n<caret>---\n\n# Three\n")
        assertEquals(2, myFixture.editor.caretModel.caretCount)
        down()
        assertEquals("$front\n# One\n\n\n---\n# Two\n\n\n---\n# Three\n", text())
    }

    fun testPlainMarkdownMovesLines() {
        open("# One\n\n<caret>---\n\n# Two\n", "plain.md")
        down()
        myFixture.checkResult("# One\n\n\n<caret>---\n# Two\n")
    }

    fun testHeadingDividerDecksMoveLines() {
        open("---\nmarp: true\nheadingDivider: 2\n---\n\n# A\n\n<caret>---\n\n## B\n")
        down()
        myFixture.checkResult("---\nmarp: true\nheadingDivider: 2\n---\n\n# A\n\n\n<caret>---\n## B\n")
    }

    fun testItRunsBeforeTheLineMover() {
        val movers = StatementUpDownMover.STATEMENT_UP_DOWN_MOVER_EP.extensionList
        val ours = movers.indexOfFirst { it is MarpSlideStatementMover }
        assertTrue("registered", ours >= 0)
        assertTrue("before LineMover", ours < movers.indexOfFirst { it is LineMover })
    }
}
