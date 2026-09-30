package cz.p3kj.marp.export

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/**
 * File | Export | Marp Deck to HTML / PDF, and the same two entries in the preview toolbar (group `Marp.Export`). They are
 * only there while a Marp deck with a preview is in the editor. What they do is in [MarpExporter].
 */
abstract class MarpExportAction(private val format: MarpExportFormat) : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && MarpExporter.previewOf(e)?.panel != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val preview = MarpExporter.previewOf(e) ?: return
        MarpExporter.export(project, preview, format)
    }
}

/** Marp deck to a standalone HTML file. */
class MarpExportHtmlAction : MarpExportAction(MarpExportFormat.HTML)

/** Marp deck to a PDF with one page per slide. */
class MarpExportPdfAction : MarpExportAction(MarpExportFormat.PDF)
