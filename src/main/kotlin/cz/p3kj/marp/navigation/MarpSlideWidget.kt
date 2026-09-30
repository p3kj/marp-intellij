package cz.p3kj.marp.navigation

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.impl.status.EditorBasedWidget
import com.intellij.openapi.wm.impl.status.widget.StatusBarEditorBasedWidgetFactory
import com.intellij.util.Consumer
import com.intellij.util.concurrency.AppExecutorUtil
import cz.p3kj.marp.MarpBundle
import cz.p3kj.marp.MarpDetector
import cz.p3kj.marp.slides.MarpDeck
import org.jetbrains.concurrency.CancellablePromise
import java.awt.Component
import java.awt.event.MouseEvent

/**
 * "Slide 3 / 12" in the status bar while the caret is in a Marp deck, with a click to go to another slide. It is on by
 * default and hidden like any other widget through the status bar menu. For anything that is not a Marp deck the text
 * is empty, which hides the widget.
 */
class MarpSlideWidgetFactory : StatusBarEditorBasedWidgetFactory(), DumbAware {

    override fun getId(): String = MarpSlideWidget.ID

    override fun getDisplayName(): String = MarpBundle.message("statusBar.slide.name")

    override fun createWidget(project: Project): StatusBarWidget = MarpSlideWidget(project)
}

class MarpSlideWidget(project: Project) : EditorBasedWidget(project), StatusBarWidget.TextPresentation {

    /** The text on show. EDT only. */
    private var current = ""

    override fun ID(): String = ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getText(): String = current

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getTooltipText(): String? = if (current.isEmpty()) null else MarpBundle.message("statusBar.slide.tooltip")

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        getEditor()?.let { MarpSlideNavigation.showGoToSlide(project, it) }
    }

    override fun install(statusBar: StatusBar) {
        super.install(statusBar)
        val multicaster = EditorFactory.getInstance().eventMulticaster
        multicaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                if (event.editor === getEditor()) refresh()
            }
        }, this)
        multicaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (getEditor()?.document == event.document) refresh()
            }
        }, this)
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                refresh()
            }
        })
        refresh()
    }

    /**
     * Brings the text up to date with the caret. The deck of a document is cached with the modification stamp it was
     * computed for, so moving the caret costs nothing. A new deck is read in the background from the committed PSI and
     * the promise is returned for it, `null` when the answer was known right away. EDT only.
     */
    internal fun refresh(): CancellablePromise<*>? {
        val editor = getEditor()
        val document = editor?.document
        val file = document?.let { FileDocumentManager.getInstance().getFile(it) }
        // Cheap check first: no background read for every keystroke in a file that cannot be a deck.
        if (editor == null || document == null || file == null || !MarpDetector.isMarkdown(file)) {
            show("")
            return null
        }
        val cached = document.getUserData(DECK_KEY)
        if (cached != null && cached.stamp == document.modificationStamp) {
            show(textFor(cached.deck, editor.caretModel.offset))
            return null
        }
        return ReadAction.nonBlocking<CachedDeck> { CachedDeck(document.modificationStamp, MarpSlideNavigation.deckFor(project, document)) }
            .withDocumentsCommitted(project)
            .expireWith(this)
            .expireWhen { editor.isDisposed }
            .coalesceBy(this)
            .finishOnUiThread(ModalityState.defaultModalityState()) { deck ->
                document.putUserData(DECK_KEY, deck)
                if (getEditor() === editor) show(textFor(deck.deck, editor.caretModel.offset))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun show(text: String) {
        if (text == current) return
        current = text
        statusBar?.updateWidget(ID())
    }

    /** The deck of a document as of [stamp], `null` for a document that is not a Marp deck. */
    internal class CachedDeck(val stamp: Long, val deck: MarpDeck?)

    companion object {
        const val ID = "Marp.SlidePosition"

        private val DECK_KEY = Key.create<CachedDeck>("marp.slideWidget.deck")

        /** "Slide 3 / 12" for the slide at [offset], empty (widget hidden) without a deck. */
        fun textFor(deck: MarpDeck?, offset: Int): String =
            if (deck == null) "" else MarpBundle.message("statusBar.slide.text", deck.slideIndexAt(offset) + 1, deck.slides.size)
    }
}
