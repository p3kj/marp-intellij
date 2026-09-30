package cz.p3kj.marp.navigation

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.concurrency.AppExecutorUtil
import cz.p3kj.marp.MarpBundle
import cz.p3kj.marp.directives.MarpDirectiveComments
import cz.p3kj.marp.slides.MarpDeck
import cz.p3kj.marp.slides.MarpSlide
import cz.p3kj.marp.slides.MarpSlideParser
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownFile
import org.jetbrains.concurrency.CancellablePromise

/**
 * Moves the caret between the slides of a Marp deck: Next / Previous Slide and Go to Slide. The slides come from
 * [MarpSlideParser], so they follow the same rules as the preview and the Structure view (including `headingDivider`).
 * The caret is all that moves, scroll sync then makes the preview follow.
 *
 * The deck is read from the committed PSI in a non-blocking read action, so nothing here blocks the EDT and no
 * document is committed on it. The action is finished on the EDT, where the live caret is read, see [withDeck].
 */
object MarpSlideNavigation {

    /** The deck of [document] when it is a Marp deck, else `null`. Call in a read action, the deck is that of the committed PSI. */
    fun deckFor(project: Project, document: Document): MarpDeck? {
        val file = PsiDocumentManager.getInstance(project).getPsiFile(document) as? MarkdownFile ?: return null
        if (!MarpDirectiveComments.isMarpDeck(file)) return null
        return MarpSlideParser.deck(file)
    }

    /**
     * Reads the deck of [editor] once its document is committed and calls [onDeck] on the EDT. A write action that lands
     * before [onDeck] restarts the read, so the deck always matches the text of the editor at that moment and the live
     * caret offset can be compared with it. Nothing is called for a file that is not a Marp deck or a disposed editor.
     * The promise is returned so that tests can wait for it.
     */
    fun withDeck(project: Project, editor: Editor, onDeck: (MarpDeck) -> Unit): CancellablePromise<*> =
        ReadAction.nonBlocking<MarpDeck?> { deckFor(project, editor.document) }
            .withDocumentsCommitted(project)
            .expireWhen { editor.isDisposed }
            .finishOnUiThread(ModalityState.defaultModalityState()) { deck ->
                if (deck != null && !editor.isDisposed) onDeck(deck)
            }
            .submit(AppExecutorUtil.getAppExecutorService())

    /** The slide [delta] slides away from the one at [offset], kept inside the deck. */
    fun targetIndex(deck: MarpDeck, offset: Int, delta: Int): Int =
        (deck.slideIndexAt(offset) + delta).coerceIn(0, deck.slides.lastIndex)

    /** Puts the caret at the content of [slide], drops the selection and scrolls there. */
    fun moveTo(editor: Editor, slide: MarpSlide) {
        val offset = slide.contentOffset.coerceIn(0, editor.document.textLength)
        editor.caretModel.removeSecondaryCarets()
        editor.caretModel.moveToOffset(offset)
        editor.selectionModel.removeSelection()
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER_UP)
    }

    /**
     * Moves the caret [delta] slides from the slide it is in. The caret is read on the EDT when the deck arrives, so
     * presses in quick succession each advance one slide. At the last (first) slide Next (Previous) stays there.
     */
    fun move(project: Project, editor: Editor, delta: Int): CancellablePromise<*> =
        withDeck(project, editor) { deck ->
            moveTo(editor, deck.slides[targetIndex(deck, editor.caretModel.offset, delta)])
        }

    /** The zero-based slide index for a number typed by the user (`1..count`), `null` when it is not one. */
    fun parseSlideNumber(input: String?, count: Int): Int? {
        val number = input?.trim()?.toIntOrNull() ?: return null
        return if (number in 1..count) number - 1 else null
    }

    /**
     * Asks for a slide number, prefilled with the current one, and moves the caret to that slide. The promise is done
     * when the dialog is closed, the move itself follows with a second read.
     */
    fun showGoToSlide(project: Project, editor: Editor): CancellablePromise<*> =
        withDeck(project, editor) { deck ->
            val count = deck.slides.size
            val index = askForSlide(project, count, deck.slideIndexAt(editor.caretModel.offset) + 1) ?: return@withDeck
            // The dialog is modal and the text may have changed meanwhile: go by the number in a fresh deck.
            withDeck(project, editor) { fresh -> moveTo(editor, fresh.slides[index.coerceAtMost(fresh.slides.lastIndex)]) }
        }

    /** The input dialog: the zero-based index of the slide the user typed (1 to [count]), `null` when cancelled. [current] is one-based. */
    internal fun askForSlide(project: Project, count: Int, current: Int): Int? {
        val validator = object : InputValidatorEx {
            override fun getErrorText(inputString: String): String? =
                if (parseSlideNumber(inputString, count) != null) null else MarpBundle.message("dialog.goToSlide.error", count)

            override fun checkInput(inputString: String): Boolean = parseSlideNumber(inputString, count) != null

            override fun canClose(inputString: String): Boolean = checkInput(inputString)
        }
        val input = Messages.showInputDialog(
            project,
            MarpBundle.message("dialog.goToSlide.message", count),
            MarpBundle.message("dialog.goToSlide.title"),
            null,
            current.toString(),
            validator,
        )
        return parseSlideNumber(input, count)
    }
}

/** Base of the slide navigation actions: only offered in the editor of a Marp deck, hidden everywhere else. */
abstract class MarpSlideAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.PSI_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR) != null &&
            file is MarkdownFile && MarpDirectiveComments.isMarpDeck(file)
    }

    final override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        perform(project, editor)
    }

    protected abstract fun perform(project: Project, editor: Editor)
}

class MarpNextSlideAction : MarpSlideAction() {
    override fun perform(project: Project, editor: Editor) {
        MarpSlideNavigation.move(project, editor, +1)
    }
}

class MarpPreviousSlideAction : MarpSlideAction() {
    override fun perform(project: Project, editor: Editor) {
        MarpSlideNavigation.move(project, editor, -1)
    }
}

class MarpGoToSlideAction : MarpSlideAction() {
    override fun perform(project: Project, editor: Editor) {
        MarpSlideNavigation.showGoToSlide(project, editor)
    }
}
