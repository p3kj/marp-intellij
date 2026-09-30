package cz.p3kj.marp.export

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.ide.progress.withBackgroundProgress
import cz.p3kj.marp.MarpBundle
import cz.p3kj.marp.editor.MarpPreviewFileEditor
import cz.p3kj.marp.editor.MarpProjectScope
import cz.p3kj.marp.editor.MarpSplitEditor
import cz.p3kj.marp.preview.MarpExportException
import cz.p3kj.marp.preview.MarpPreviewPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

private val LOG = logger<MarpExporter>()

/**
 * Exports the deck of a Marp preview editor to HTML or PDF. The work is done by the live preview page (it holds the
 * current themes and options and reaches the local images), see the Export section of `docs/ARCHITECTURE.md`:
 * HTML is rendered by the page and written here, PDF is the page printed by Chromium.
 *
 * [export] runs on the EDT (save dialog), the rest in the project scope under a background progress.
 */
internal object MarpExporter {

    private const val NOTIFICATION_GROUP = "Marp Export"

    /** The Marp split editor of the deck the action was invoked for, from the data context or the file's selected editor. */
    fun splitEditorOf(e: AnActionEvent): MarpSplitEditor? {
        (e.getData(PlatformCoreDataKeys.FILE_EDITOR) as? MarpSplitEditor)?.let { return it }
        val project = e.project ?: return null
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        return FileEditorManager.getInstance(project).getSelectedEditor(file) as? MarpSplitEditor
    }

    /** The preview editor of the deck the action was invoked for, see [splitEditorOf]. */
    fun previewOf(e: AnActionEvent): MarpPreviewFileEditor? = splitEditorOf(e)?.preview

    /** EDT. Asks where to save, then exports in the background. Nothing happens when the dialog is cancelled. */
    fun export(project: Project, preview: MarpPreviewFileEditor, format: MarpExportFormat) {
        val panel = preview.panel ?: return
        // The page is what gets exported: without a loaded page there is nothing to render or print.
        if (!panel.isPageReady) {
            notify(project, NotificationType.WARNING, MarpBundle.message("export.notReady"))
            return
        }
        val target = chooseTarget(project, preview, format) ?: return
        MarpProjectScope.getInstance(project).scope.launch { run(project, preview, panel, format, target) }
    }

    private fun chooseTarget(project: Project, preview: MarpPreviewFileEditor, format: MarpExportFormat): Path? {
        val markdown = preview.file
        // Relative images and theme URLs of an HTML file resolve against where it is saved, so start next to the deck.
        val directory = markdown.parent?.let { it.fileSystem.getNioPath(it) }
        val descriptor = FileSaverDescriptor(format.dialogTitle, MarpBundle.message("export.dialog.description"), format.extension)
        val chosen = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            .save(directory, format.defaultName(markdown.name))?.file?.toPath() ?: return null
        val target = format.withExtension(chosen)
        // The dialog confirmed an overwrite of `chosen`, but not of the name with the extension added.
        if (target != chosen && Files.exists(target)) {
            val overwrite = MessageDialogBuilder.yesNo(
                MarpBundle.message("export.overwrite.title"),
                MarpBundle.message("export.overwrite.message", target.fileName.toString()),
            ).ask(project)
            if (!overwrite) return null
        }
        return target
    }

    private suspend fun run(project: Project, preview: MarpPreviewFileEditor, panel: MarpPreviewPanel, format: MarpExportFormat, target: Path) {
        val name = target.fileName.toString()
        try {
            withBackgroundProgress(project, MarpBundle.message("export.progress", name)) {
                // The throttled render may not have sent the latest keystrokes yet.
                preview.renderNow()
                when (format) {
                    MarpExportFormat.HTML -> {
                        val html = panel.exportHtml(preview.file.nameWithoutExtension)
                        withContext(Dispatchers.IO) { Files.writeString(target, html) }
                    }
                    MarpExportFormat.PDF -> {
                        panel.flushRender()
                        panel.printToPdf(target)
                    }
                }
            }
            // Makes the file show up in the project view when it is written inside the project.
            withContext(Dispatchers.IO) { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target) }
            done(project, target)
        }
        catch (e: CancellationException) {
            throw e
        }
        catch (e: Exception) {
            LOG.warn("Cannot export ${preview.file.path} to $target", e)
            notify(project, NotificationType.ERROR, MarpBundle.message("export.failed", name, reason(e, format)))
        }
    }

    /** The user-facing part of a failure. [MarpExportException] messages are for the log. */
    fun reason(e: Exception, format: MarpExportFormat): String = when {
        e is MarpExportException && e.timedOut -> MarpBundle.message("export.error.timeout")
        e is MarpExportException && e.pageGone -> MarpBundle.message("export.error.pageGone")
        e is MarpExportException && format == MarpExportFormat.PDF -> MarpBundle.message("export.error.pdf")
        e is MarpExportException -> MarpBundle.message("export.error.render")
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun done(project: Project, target: Path) {
        if (project.isDisposed) return
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(MarpBundle.message("export.done", target.fileName.toString()), NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring(MarpBundle.message("export.open")) { BrowserUtil.browse(target) })
            .notify(project)
    }

    fun notify(project: Project, type: NotificationType, content: String) {
        if (project.isDisposed) return
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(content, type)
            .notify(project)
    }
}
