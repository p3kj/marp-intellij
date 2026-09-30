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

/**
 * File | Export | Marp Deck to PowerPoint / PNG / JPEG Images (Marp CLI), and the same entries in the preview toolbar
 * popup. Unlike the HTML and PDF export they do not need the preview page, so they show for every Marp deck in the Marp
 * editor even where JCEF is missing. Whether Marp CLI is there is only known when the export runs: a disabled entry
 * could not say why, the export tells (and offers the settings). What they do is in [MarpCliExporter].
 */
abstract class MarpCliExportAction(private val format: MarpCliFormat) : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && MarpExporter.previewOf(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val preview = MarpExporter.previewOf(e) ?: return
        MarpCliExporter.export(project, preview.file, format)
    }
}

/** Marp deck to a PowerPoint file with Marp CLI. */
class MarpExportPptxAction : MarpCliExportAction(MarpCliFormat.PPTX)

/** Marp deck to one PNG image per slide with Marp CLI. */
class MarpExportPngAction : MarpCliExportAction(MarpCliFormat.PNG)

/** Marp deck to one JPEG image per slide with Marp CLI. */
class MarpExportJpegAction : MarpCliExportAction(MarpCliFormat.JPEG)
