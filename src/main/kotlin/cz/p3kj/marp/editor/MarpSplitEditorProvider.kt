package cz.p3kj.marp.editor

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
import cz.p3kj.marp.MarpBundle
import cz.p3kj.marp.MarpDetector
import cz.p3kj.marp.sync.MarpScrollSync

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

    override fun createSplitEditor(firstEditor: TextEditor, secondEditor: FileEditor): FileEditor {
        if (secondEditor is MarpPreviewFileEditor) return MarpSplitEditor(firstEditor, secondEditor)
        return TextEditorWithPreview(firstEditor, secondEditor, MarpBundle.message("editor.name"), TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW)
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

/** Text editor + Marp preview, with editor <-> preview scroll sync. */
class MarpSplitEditor(textEditor: TextEditor, preview: MarpPreviewFileEditor) :
    TextEditorWithPreview(textEditor, preview, MarpBundle.message("editor.name"), TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW) {

    private val scrollSync = MarpScrollSync(preview.project, textEditor.editor, preview, this)

    init {
        Disposer.register(this, scrollSync)
    }

    override fun onLayoutChange(oldValue: TextEditorWithPreview.Layout?, newValue: TextEditorWithPreview.Layout?) {
        scrollSync.layoutChanged(newValue)
    }

    override val splitterProportionKey: String
        get() = "MarpPreview.SplitterProportionKey"
}
