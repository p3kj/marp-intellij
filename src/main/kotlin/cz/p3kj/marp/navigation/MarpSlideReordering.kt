package cz.p3kj.marp.navigation

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import cz.p3kj.marp.MarpBundle
import cz.p3kj.marp.slides.MarpSlideReorder
import org.jetbrains.concurrency.CancellablePromise

private val LOG = logger<MarpSlideReordering>()

/**
 * Moves slides in the text of a Marp deck: the slide under the caret one place up or down (Move Slide Up / Down here, and
 * Move Statement Up / Down on a separator line in [MarpSlideStatementMover]), or a slide to any place ([moveSlide], for a
 * drag in the slide overview). The edit itself is computed by [MarpSlideReorder]; this object reads the deck like the
 * navigation does and applies the edit as one undoable command.
 */
object MarpSlideReordering {

    /**
     * Moves the slide the caret is in one place up ([down] is `false`) or down, once the deck is read (see
     * [MarpSlideNavigation.withDeck]). Nothing happens at the first slide (up) and the last slide (down), in a file that
     * is not a Marp deck and in a read-only file. A deck with a `headingDivider` is not supported and gets a hint.
     * The promise is returned so that tests can wait for it.
     */
    fun move(project: Project, editor: Editor, down: Boolean): CancellablePromise<*> =
        MarpSlideNavigation.withDeck(project, editor) { deck ->
            if (!MarpSlideReorder.supports(deck)) {
                HintManager.getInstance().showInformationHint(editor, MarpBundle.message("hint.moveSlide.headingDivider"))
                return@withDeck
            }
            val document = editor.document
            // The deck was read from the committed PSI and every write restarts that read, so it describes this text.
            if (deck.slides.last().endOffset != document.textLength) return@withDeck
            val file = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return@withDeck
            val index = deck.slideIndexAt(editor.caretModel.offset)
            val edit = MarpSlideReorder.move(document.immutableCharSequence, deck, index, down) ?: return@withDeck
            WriteCommandAction.writeCommandAction(project, file)
                .withName(MarpBundle.message(if (down) "command.moveSlideDown" else "command.moveSlideUp"))
                .run<RuntimeException> { apply(editor, edit) }
        }

    /**
     * Moves slide [from] to the place of slide [to] (its index after the move), for a thumbnail dropped in the slide
     * overview, once the deck is read (see [MarpSlideNavigation.withDeck]). The page asked for this from the deck it had
     * rendered, so it is checked against the deck of the editor first and ignored when the two differ: [line] must be in
     * slide [from] and [count] the number of slides. Nothing happens either in a read-only file or in a deck with a
     * `headingDivider`. The undo step has the name of Move Slide Up / Down, and the caret ends in the moved slide (so the
     * preview highlights it). The focus stays where it is, the user may drag again.
     */
    fun moveSlide(project: Project, editor: Editor, from: Int, to: Int, line: Int, count: Int): CancellablePromise<*> =
        MarpSlideNavigation.withDeck(project, editor) { deck ->
            val document = editor.document
            if (editor.isViewer || !document.isWritable || !MarpSlideReorder.supports(deck)) return@withDeck
            // The deck was read from the committed PSI and every write restarts that read, so it describes this text.
            if (deck.slides.last().endOffset != document.textLength) return@withDeck
            if (deck.slides.size != count || line !in 0 until document.lineCount) {
                LOG.debug("Ignoring slide move $from to $to: the preview shows another deck (line $line, $count slides)")
                return@withDeck
            }
            if (deck.slideIndexAt(document.getLineStartOffset(line)) != from) {
                LOG.debug("Ignoring slide move $from to $to: line $line is not in slide $from")
                return@withDeck
            }
            val file = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return@withDeck
            val edit = MarpSlideReorder.moveTo(document.immutableCharSequence, deck, from, to) ?: return@withDeck
            WriteCommandAction.writeCommandAction(project, file)
                .withName(MarpBundle.message(if (to > from) "command.moveSlideDown" else "command.moveSlideUp"))
                .run<RuntimeException> { apply(editor, edit) }
        }

    /**
     * Replaces the text of [edit] in the document of [editor] and puts the caret where it belongs after it (see
     * [MarpSlideReorder.Edit.caretAfter]). Call in a write command; a selection and secondary carets are dropped.
     */
    fun apply(editor: Editor, edit: MarpSlideReorder.Edit) {
        val document = editor.document
        val caret = editor.caretModel.offset
        editor.caretModel.removeSecondaryCarets()
        editor.selectionModel.removeSelection()
        document.replaceString(edit.start, edit.end, edit.text)
        editor.caretModel.moveToOffset(edit.caretAfter(caret).coerceIn(0, document.textLength))
        editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
    }
}

/** Base of Move Slide Up / Down: like the navigation actions, plus disabled in a read-only editor. */
abstract class MarpMoveSlideAction(private val down: Boolean) : MarpSlideAction() {

    override fun update(e: AnActionEvent) {
        super.update(e)
        if (e.getData(CommonDataKeys.EDITOR)?.isViewer == true) e.presentation.isEnabled = false
    }

    override fun perform(project: Project, editor: Editor) {
        MarpSlideReordering.move(project, editor, down)
    }
}

class MarpMoveSlideUpAction : MarpMoveSlideAction(down = false)

class MarpMoveSlideDownAction : MarpMoveSlideAction(down = true)
