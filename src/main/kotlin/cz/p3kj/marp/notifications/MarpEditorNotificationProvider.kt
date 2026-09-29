package cz.p3kj.marp.notifications

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import cz.p3kj.marp.MarpBundle
import cz.p3kj.marp.MarpDetector
import cz.p3kj.marp.editor.MarpSplitEditor
import java.util.function.Function
import javax.swing.JComponent

/**
 * "Marp deck detected" banner for a Marp deck that is open in some other editor (typically the Markdown plugin's,
 * because `marp: true` was added after the file was opened). The action reopens the file, so
 * [cz.p3kj.marp.editor.MarpSplitEditorProvider] takes over. Kept up to date while typing by
 * [MarpNotificationDocumentListener].
 */
class MarpEditorNotificationProvider : EditorNotificationProvider, DumbAware {

    /**
     * Called in a read action. Only the loaded document is checked, never the file on disk: banners are for files that
     * are open in an editor, and those always have a document.
     */
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        if (!file.isValid || !MarpDetector.isMarkdown(file)) return null
        val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return null
        if (!MarpDetector.isMarp(document.immutableCharSequence)) return null
        return Function { fileEditor ->
            if (fileEditor is MarpSplitEditor || fileEditor !is TextEditor) return@Function null
            EditorNotificationPanel(fileEditor, EditorNotificationPanel.Status.Info).apply {
                text = MarpBundle.message("notification.marp.detected")
                createActionLabel(MarpBundle.message("notification.marp.open")) { reopen(project, file) }
            }
        }
    }

    private fun reopen(project: Project, file: VirtualFile) {
        if (project.isDisposed || !file.isValid) return
        val manager = FileEditorManager.getInstance(project)
        manager.closeFile(file)
        manager.openFile(file, true)
    }
}
