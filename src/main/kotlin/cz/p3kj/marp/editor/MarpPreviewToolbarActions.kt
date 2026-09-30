package cz.p3kj.marp.editor

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import cz.p3kj.marp.export.MarpExporter
import cz.p3kj.marp.settings.MarpAppSettings
import cz.p3kj.marp.settings.MarpConfigurable

/*
 * Actions of the preview toolbar (group `Marp.PreviewToolbar`, registered in plugin.xml and shown by
 * [MarpSplitEditor.createRightToolbarActionGroup]). The scroll sync and notes toggles change the IDE-wide
 * [MarpAppSettings], the same values as Settings | Tools | Marp, so the toolbar and the settings page never disagree.
 * Open editors react through `MarpAppSettingsListener`. The overview toggle is per editor: it reads the editor from the
 * data context (like the export actions) and keeps its state in [MarpPreviewFileEditor.overview]. No action holds an
 * editor reference.
 */

/** Turns editor <-> preview scroll sync on or off. Turning it on realigns the preview with the editor. */
class MarpScrollSyncToggleAction : ToggleAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean = MarpAppSettings.getInstance().scrollSync

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        MarpAppSettings.getInstance().update { scrollSync = state }
    }
}

/** Shows or hides the presenter notes under each slide. */
class MarpPresenterNotesToggleAction : ToggleAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean = MarpAppSettings.getInstance().presenterNotes

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        MarpAppSettings.getInstance().update { presenterNotes = state }
    }
}

/**
 * Shows the slides of this preview as a grid of thumbnails. A view mode of one deck, not a preference: per editor, not
 * persisted, see "Slide overview" in `docs/ARCHITECTURE.md`. Hidden without a preview page (no JCEF, no Marp editor).
 */
class MarpOverviewToggleAction : ToggleAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        // The toggle state is set by the super call, so this has to come first.
        super.update(e)
        e.presentation.isEnabledAndVisible = MarpExporter.previewOf(e)?.panel != null
    }

    override fun isSelected(e: AnActionEvent): Boolean = MarpExporter.previewOf(e)?.overview == true

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        MarpExporter.previewOf(e)?.setOverview(state)
    }
}

/** Opens Settings | Tools | Marp. */
class MarpOpenSettingsAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ShowSettingsUtil.getInstance().showSettingsDialog(project, MarpConfigurable::class.java)
    }
}
