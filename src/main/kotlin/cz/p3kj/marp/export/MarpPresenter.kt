package cz.p3kj.marp.export

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.util.concurrency.AppExecutorUtil
import cz.p3kj.marp.MarpBundle
import cz.p3kj.marp.editor.MarpProjectScope
import cz.p3kj.marp.editor.MarpPreviewFileEditor
import cz.p3kj.marp.editor.MarpSplitEditor
import cz.p3kj.marp.navigation.MarpSlideNavigation
import cz.p3kj.marp.preview.MarpPresentOptions
import cz.p3kj.marp.preview.MarpPreviewPanel
import cz.p3kj.marp.slides.MarpDeck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.concurrency.CancellablePromise
import java.nio.file.Path

private val LOG = logger<MarpPresenter>()

/**
 * Present Deck, a button of the preview toolbar: shows the deck in the system browser one slide at a time, starting at
 * the slide under the caret. Only shown while a Marp deck with a preview is in the editor. What it does is in
 * [MarpPresenter].
 */
class MarpPresentAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && MarpExporter.previewOf(e)?.panel != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val split = MarpExporter.splitEditorOf(e) ?: return
        MarpPresenter.present(project, split)
    }
}

/**
 * The presentation is the HTML export of the live preview page with the `present` option (see the Present section of
 * `docs/ARCHITECTURE.md`): a self-contained HTML file with a script that shows one slide at a time. It is written to a
 * temporary file and opened with [BrowserUtil]. There is no second browser, no server and no live reload: run Present
 * again to see edits.
 */
internal object MarpPresenter {

    /**
     * EDT. Finds the slide under the caret, then prepares the presentation in the background and opens it in the
     * browser. Returns the promise of the slide lookup, `null` when nothing was started (no preview page, or it is not
     * loaded yet, which shows the `export.notReady` warning).
     */
    fun present(project: Project, split: MarpSplitEditor): CancellablePromise<*>? {
        val preview = split.preview
        val panel = preview.panel ?: return null
        if (!panel.isPageReady) {
            MarpExporter.notify(project, NotificationType.WARNING, MarpBundle.message("export.notReady"))
            return null
        }
        return withStartSlide(project, split.textEditor.editor) { start ->
            MarpProjectScope.getInstance(project).scope.launch { run(project, preview, panel, start) }
        }
    }

    /**
     * Reads the deck of [editor] once its document is committed and calls [onStart] on the EDT with the zero-based slide
     * the caret is in, 0 when the file is not a Marp deck. The caret is read on the EDT, so it matches the deck (a write
     * action that lands first restarts the read), see `MarpSlideNavigation.withDeck`. Not called when the editor or the
     * project is disposed meanwhile. The promise is returned so that tests can wait for it.
     */
    fun withStartSlide(project: Project, editor: Editor, onStart: (Int) -> Unit): CancellablePromise<*> =
        ReadAction.nonBlocking<MarpDeck?> { MarpSlideNavigation.deckFor(project, editor.document) }
            .withDocumentsCommitted(project)
            .expireWhen { editor.isDisposed || project.isDisposed }
            .finishOnUiThread(ModalityState.defaultModalityState()) { deck ->
                if (!editor.isDisposed && !project.isDisposed) onStart(deck?.slideIndexAt(editor.caretModel.offset) ?: 0)
            }
            .submit(AppExecutorUtil.getAppExecutorService())

    /** The page renders and writes the file under a progress, then the browser opens it. Failures are notified, never thrown. */
    private suspend fun run(project: Project, preview: MarpPreviewFileEditor, panel: MarpPreviewPanel, start: Int) {
        val markdown = preview.file
        try {
            val path = withBackgroundProgress(project, MarpBundle.message("present.progress", markdown.name)) {
                // The throttled render may not have sent the latest keystrokes yet.
                preview.renderNow()
                val html = panel.exportHtml(markdown.nameWithoutExtension, MarpPresentOptions(baseHref(markdown), start))
                withContext(Dispatchers.IO) { MarpPresentFiles.write(html) }
            }
            // Same thread as the Open action of the export: BrowserUtil reports a browser that cannot be started with a dialog.
            withContext(Dispatchers.EDT) { BrowserUtil.browse(path) }
        }
        catch (e: CancellationException) {
            throw e
        }
        catch (e: Exception) {
            LOG.warn("Cannot present ${markdown.path}", e)
            val reason = MarpExporter.reason(e, MarpExportFormat.HTML)
            MarpExporter.notify(project, NotificationType.ERROR, MarpBundle.message("present.failed", markdown.name, reason))
        }
    }

    /** The base URL of the folder of [markdown] on the local file system, `null` on any other file system. */
    private fun baseHref(markdown: VirtualFile): String? {
        val parent = markdown.parent ?: return null
        val directory: Path = parent.fileSystem.getNioPath(parent) ?: return null
        return MarpPresentFiles.baseHref(directory)
    }
}
