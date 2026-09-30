package cz.p3kj.marp.navigation

import com.intellij.codeInsight.editorActions.moveUpDown.LineRange
import com.intellij.codeInsight.editorActions.moveUpDown.StatementUpDownMover
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import cz.p3kj.marp.directives.MarpDirectiveComments
import cz.p3kj.marp.slides.MarpSlideParser
import cz.p3kj.marp.slides.MarpSlideReorder
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownFile

/**
 * Makes the usual Move Statement Up / Down move a whole slide when the caret is on the separator line (`---`, `***`,
 * `___`) of a slide after the first one, the way it moves a whole method from its header line in Java. That is a slide
 * mover for the keyboard without a shortcut of its own, and Move Line Up / Down still moves the separator line itself.
 *
 * Anywhere else (inside a slide, a selection, several carets, a file that is not a Marp deck, a deck with a
 * `headingDivider`) this returns `false` and the platform moves lines as before. On a separator line the move is always
 * handled here: at the end of the deck nothing moves rather than the separator line sliding into the last slide.
 *
 * The platform swaps two line ranges, which cannot express a slide move (the first slide keeps its front matter and
 * the last one may need a blank line, see [MarpSlideReorder]). So the edit is made in [beforeMove], the way other movers
 * do their own moving, and the platform's own swap is switched off by giving it the same range twice. All of it runs
 * inside the platform's command and write action, so it is one undo step.
 *
 * Movers run on the EDT after the platform committed the document, so the deck comes from the cached PSI.
 */
class MarpSlideStatementMover : StatementUpDownMover(), DumbAware {

    override fun checkAvailable(editor: Editor, file: PsiFile, info: MoveInfo, down: Boolean): Boolean {
        if (file !is MarkdownFile || !MarpDirectiveComments.isMarpDeck(file)) return false
        if (editor.caretModel.caretCount != 1 || editor.selectionModel.hasSelection()) return false
        val deck = MarpSlideParser.deck(file)
        if (!MarpSlideReorder.supports(deck)) return false
        val document = editor.document
        val line = document.getLineNumber(editor.caretModel.offset)
        val lineStart = document.getLineStartOffset(line)
        val index = deck.slideIndexAt(lineStart)
        // Only the line that starts a slide after the first one is a separator line.
        if (index == 0 || deck.slides[index].startOffset != lineStart) return false

        // From here on this mover has the move, whether or not there is anything to move.
        info.toMove = LineRange(line, line + 1)
        info.indentSource = false
        info.indentTarget = false
        val edit = MarpSlideReorder.move(document.immutableCharSequence, deck, index, down) ?: return info.prohibitMove()
        // The same range object twice: the platform skips its own swap and leaves the move to beforeMove.
        info.toMove2 = info.toMove
        info.putUserData(EDIT, edit)
        return true
    }

    override fun beforeMove(editor: Editor, info: MoveInfo, down: Boolean) {
        info.getUserData(EDIT)?.let { MarpSlideReordering.apply(editor, it) }
    }

    private companion object {
        val EDIT = Key.create<MarpSlideReorder.Edit>("marp.slideMove")
    }
}
