package cz.p3kj.marp.editor

import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.TextEditorWithPreviewProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import cz.p3kj.marp.MarpBundle
import cz.p3kj.marp.MarpDetector
import cz.p3kj.marp.structure.MarpStructureViewBuilder
import cz.p3kj.marp.sync.MarpScrollSync
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownFile

/**
 * Opens Markdown files with `marp: true` front matter in an Editor / Split / Preview editor with the Marp preview.
 *
 * [FileEditorPolicy.HIDE_OTHER_EDITORS] hides the Markdown plugin's own split editor for Marp decks only; other Markdown
 * files keep the regular JetBrains preview. [TextEditorWithPreviewProvider] creates the text editor asynchronously and
 * persists the layout per file.
 */
class MarpSplitEditorProvider : TextEditorWithPreviewProvider(MarpPreviewFileEditorProvider()), DumbAware {

    override fun getEditorTypeId(): String = EDITOR_TYPE_ID

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_OTHER_EDITORS

    /** [MarpPreviewFileEditorProvider] always creates a [MarpPreviewFileEditor]. */
    override fun createSplitEditor(firstEditor: TextEditor, secondEditor: FileEditor): FileEditor {
        check(secondEditor is MarpPreviewFileEditor) { "Unexpected preview editor: ${secondEditor.javaClass.name}" }
        return MarpSplitEditor(firstEditor, secondEditor)
    }

    companion object {
        const val EDITOR_TYPE_ID: String = "marp-preview-editor"
    }
}

/** The preview half; not registered on its own, only used through [MarpSplitEditorProvider]. */
internal class MarpPreviewFileEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean = MarpDetector.isMarpFile(file)

    /** [accept] reads the cached document text. */
    override fun acceptRequiresReadAction(): Boolean = true

    override fun createEditor(project: Project, file: VirtualFile): FileEditor = MarpPreviewFileEditor(project, file)

    override fun getEditorTypeId(): String = "marp-preview"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR
}

/**
 * Text editor + Marp preview, with editor <-> preview scroll sync, the preview toolbar and the slide outline.
 *
 * The Structure tool window and the File Structure popup ask the editor for its structure view, and this editor
 * answers with the slides of the deck ([MarpStructureViewBuilder]). Only Marp decks get this editor, so other Markdown
 * files keep the outline of the Markdown plugin.
 *
 * The toolbar (group [TOOLBAR_GROUP_ID]) goes into the right group only: the platform shows it in the floating toolbar
 * of the editor, so the editor gets no permanent toolbar row. The left group stays empty for the same reason.
 */
class MarpSplitEditor(textEditor: TextEditor, preview: MarpPreviewFileEditor) :
    TextEditorWithPreview(textEditor, preview, MarpBundle.message("editor.name"), TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW) {

    private val project = preview.project

    private val scrollSync = MarpScrollSync(project, textEditor.editor, preview, this)

    init {
        Disposer.register(this, scrollSync)
    }

    override fun getStructureViewBuilder(): StructureViewBuilder? {
        val virtualFile = file?.takeIf { it.isValid } ?: return super.getStructureViewBuilder()
        val psi: PsiFile? = runReadActionBlocking { PsiManager.getInstance(project).findFile(virtualFile) }
        return if (psi is MarkdownFile) MarpStructureViewBuilder(psi) else super.getStructureViewBuilder()
    }

    override fun createRightToolbarActionGroup(): ActionGroup? = ActionManager.getInstance().getAction(TOOLBAR_GROUP_ID) as? ActionGroup

    override fun onLayoutChange(oldValue: TextEditorWithPreview.Layout?, newValue: TextEditorWithPreview.Layout?) {
        scrollSync.layoutChanged(newValue)
    }

    override val splitterProportionKey: String
        get() = "MarpPreview.SplitterProportionKey"

    companion object {
        /** Id of the toolbar action group in plugin.xml. */
        const val TOOLBAR_GROUP_ID: String = "Marp.PreviewToolbar"
    }
}
