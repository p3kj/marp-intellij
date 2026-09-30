package cz.p3kj.marp.sync

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.VisibleAreaEvent
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Condition
import com.intellij.openapi.wm.IdeFocusManager
import cz.p3kj.marp.editor.MarpPreviewFileEditor
import cz.p3kj.marp.navigation.MarpSlideReordering
import cz.p3kj.marp.preview.MarpPreviewPanel
import cz.p3kj.marp.preview.MarpSlideMove
import cz.p3kj.marp.settings.MarpAppSettings
import cz.p3kj.marp.settings.MarpAppSettingsListener
import java.awt.Point
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Keeps the text editor and the Marp preview in step (EDT only):
 * - editor scrolled -> `scrollToLine(top visible line + fraction)`, when [MarpAppSettings.scrollSync] is on;
 * - preview scrolled (`revealLine`) -> scroll the editor so that fractional line is at the top, caret untouched;
 * - caret moved -> `setActiveLine`;
 * - double-click in a slide (`didClick`) -> caret to that line, scroll it into view, focus the editor;
 * - thumbnail dropped in the slide overview (`didMoveSlide`) -> the slide moves in the text, see [MarpSlideReordering.moveSlide].
 *
 * Programmatic scrolls of one side must not bounce back from the other, so events arriving right after this class
 * scrolled the other side are ignored ([MarpScrollEchoGuard]).
 */
class MarpScrollSync(
    private val project: Project,
    private val editor: Editor,
    private val preview: MarpPreviewFileEditor,
    private val splitEditor: TextEditorWithPreview?,
) : Disposable, MarpPreviewPanel.Listener {

    private val echoGuard = MarpScrollEchoGuard()
    private var lastActiveLine = -1

    /** [didClick] is showing the editor: it scrolls to the caret itself, [layoutChanged] must not sync first. */
    private var showingEditorForClick = false

    init {
        editor.scrollingModel.addVisibleAreaListener(object : VisibleAreaListener {
            override fun visibleAreaChanged(e: VisibleAreaEvent) {
                onEditorScrolled()
            }
        }, this)
        editor.caretModel.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                sendActiveLine()
            }
        }, this)
        preview.panel?.listener = this
        // Turning scroll sync on aligns the preview right away instead of on the next scroll.
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(MarpAppSettingsListener.TOPIC, MarpAppSettingsListener {
            if (!editor.isDisposed && scrollSyncEnabled() && isSplitLayout()) sendTopLine(force = true)
        })
        sendActiveLine()
        if (scrollSyncEnabled()) sendTopLine(force = true)
    }

    /** The split layout changed: when the preview becomes visible, align it with the editor. */
    fun layoutChanged(layout: TextEditorWithPreview.Layout?) {
        if (showingEditorForClick) return
        if (layout == TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW && scrollSyncEnabled()) sendTopLine(force = true)
    }

    private fun scrollSyncEnabled(): Boolean = !project.isDisposed && MarpAppSettings.getInstance().scrollSync

    /** Both halves visible (always `true` without a split editor, as in tests). */
    private fun isSplitLayout(): Boolean =
        splitEditor == null || splitEditor.getLayout() == TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW

    private fun onEditorScrolled() {
        if (!echoGuard.acceptEditorScroll() || editor.isDisposed || !scrollSyncEnabled()) return
        // Hidden editor (preview-only layout) or not laid out yet: nothing meaningful to report.
        if (!editor.component.isShowing || editor.scrollingModel.visibleArea.height <= 0) return
        sendTopLine(force = false)
    }

    private fun sendTopLine(force: Boolean) {
        val panel = preview.panel ?: return
        val line = topVisibleLine()
        if (echoGuard.beforeSendToPreview(line, force)) panel.scrollToLine(line)
    }

    private fun sendActiveLine() {
        if (editor.isDisposed) return
        val line = editor.caretModel.logicalPosition.line
        if (line == lastActiveLine) return
        lastActiveLine = line
        preview.panel?.setActiveLine(line)
    }

    override fun revealLine(line: Double) {
        if (editor.isDisposed || !scrollSyncEnabled()) return
        // The preview reporting the position we just sent it.
        if (!echoGuard.acceptPreviewScroll()) return
        val y = lineToY(line) ?: return
        val scrollingModel = editor.scrollingModel
        echoGuard.reveal {
            scrollingModel.disableAnimation()
            try {
                scrollingModel.scrollVertically(y)
            }
            finally {
                scrollingModel.enableAnimation()
            }
            // Where the editor actually is (whole pixels, clamped at the end), so a later visible-area event without a
            // real scroll (typing that changes the line width) does not send a slightly different line back.
            topVisibleLine()
        }
    }

    override fun didClick(line: Int) {
        if (editor.isDisposed) return
        val lineCount = editor.document.lineCount
        val target = line.coerceIn(0, maxOf(0, lineCount - 1))
        // Caret first, then the layout: the editor then scrolls once, to the caret, and the preview follows that scroll
        // (instead of first jumping to the editor's old position when the layout changes).
        val caretModel = editor.caretModel
        caretModel.removeSecondaryCarets()
        caretModel.moveToLogicalPosition(LogicalPosition(target, 0))
        editor.selectionModel.removeSelection()

        val showEditor = splitEditor != null && splitEditor.getLayout() == TextEditorWithPreview.Layout.SHOW_PREVIEW
        if (showEditor) {
            showingEditorForClick = true
            try {
                splitEditor.setLayout(TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW)
            }
            finally {
                showingEditorForClick = false
            }
            // The editor has no size until the new layout is applied.
            val expired = Condition<Any?> { editor.isDisposed }
            ApplicationManager.getApplication().invokeLater({ editor.scrollingModel.scrollToCaret(ScrollType.CENTER) }, expired)
        }
        else {
            editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
        }
        IdeFocusManager.getInstance(project).requestFocus(editor.contentComponent, true)
    }

    override fun moveSlide(move: MarpSlideMove) {
        if (editor.isDisposed) return
        MarpSlideReordering.moveSlide(project, editor, move.from, move.to, move.line, move.count)
    }

    /** Logical line at the top of the viewport plus how far (0..1) the viewport top is into it. */
    private fun topVisibleLine(): Double {
        val lineCount = editor.document.lineCount
        if (lineCount == 0) return 0.0
        val y = editor.scrollingModel.visibleArea.y
        val line = editor.xyToLogicalPosition(Point(0, y)).line.coerceIn(0, lineCount - 1)
        val (top, height) = lineBounds(line, lineCount)
        return line + ((y - top).toDouble() / height).coerceIn(0.0, MAX_FRACTION)
    }

    /** Editor y of fractional [line], or `null` for an empty document. */
    private fun lineToY(line: Double): Int? {
        val lineCount = editor.document.lineCount
        if (lineCount == 0) return null
        val whole = floor(line).toInt().coerceIn(0, lineCount - 1)
        val fraction = (line - whole).coerceIn(0.0, 1.0)
        val (top, height) = lineBounds(whole, lineCount)
        return top + (fraction * height).roundToInt()
    }

    /** Top y and height of a logical line (soft wraps included; folded or empty spans fall back to one line height). */
    private fun lineBounds(line: Int, lineCount: Int): Pair<Int, Int> {
        val top = editor.logicalPositionToXY(LogicalPosition(line, 0)).y
        val next = if (line + 1 < lineCount) editor.logicalPositionToXY(LogicalPosition(line + 1, 0)).y else top + editor.lineHeight
        val height = (next - top).takeIf { it > 0 } ?: editor.lineHeight
        return top to maxOf(1, height)
    }

    override fun dispose() {
        val panel = preview.panel
        if (panel != null && panel.listener === this) panel.listener = null
    }

    private companion object {
        const val MAX_FRACTION = 0.999
    }
}
