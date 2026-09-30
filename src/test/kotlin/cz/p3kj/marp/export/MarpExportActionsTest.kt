package cz.p3kj.marp.export

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.TestActionEvent
import com.intellij.util.ui.UIUtil
import cz.p3kj.marp.MarpBundle
import cz.p3kj.marp.MarpLightTestCase
import cz.p3kj.marp.editor.MarpSplitEditor
import cz.p3kj.marp.editor.MarpSplitEditorProvider

class MarpExportActionsTest : MarpLightTestCase() {

    private val actions get() = ActionManager.getInstance()

    private fun event(action: AnAction, context: DataContext = SimpleDataContext.getProjectContext(project)): AnActionEvent =
        TestActionEvent.createTestEvent(action, context)

    private fun childIds(group: ActionGroup): List<String> =
        (group as DefaultActionGroup).getChildren(actions).map { actions.getId(it) ?: "<unregistered ${it.javaClass.name}>" }

    fun testBothActionsAreRegisteredWithTheirTexts() {
        for (id in listOf("Marp.ExportHtml", "Marp.ExportPdf")) {
            val action = actions.getAction(id)
            assertNotNull("$id is not registered", action)
            assertTrue("$id must be DumbAware", action is DumbAware)
            assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
            assertEquals(MarpBundle.message("action.$id.text"), action.templatePresentation.text)
            assertEquals(MarpBundle.message("action.$id.description"), action.templatePresentation.description)
        }
        assertTrue(actions.getAction("Marp.ExportHtml") is MarpExportHtmlAction)
        assertTrue(actions.getAction("Marp.ExportPdf") is MarpExportPdfAction)
    }

    fun testActionsAreInFileExport() {
        val fileExport = actions.getAction("FileExportGroup") as ActionGroup
        val ids = childIds(fileExport)
        assertTrue(ids.toString(), "Marp.ExportHtml" in ids)
        assertTrue(ids.toString(), "Marp.ExportPdf" in ids)
    }

    fun testToolbarHoldsTheExportPopup() {
        val toolbar = actions.getAction("Marp.PreviewToolbar") as ActionGroup
        assertTrue(childIds(toolbar).toString(), "Marp.Export" in childIds(toolbar))

        val export = actions.getAction("Marp.Export") as ActionGroup
        assertTrue("shown as a popup button", export.isPopup)
        assertTrue("stays usable while indexing", export is DumbAware)
        assertNotNull("the popup button needs an icon", export.templatePresentation.icon)
        assertEquals(listOf("Marp.ExportHtml", "Marp.ExportPdf"), childIds(export))
        assertEquals("Export Deck", export.templatePresentation.text)
    }

    fun testHiddenWithoutAMarpEditor() {
        for (id in listOf("Marp.ExportHtml", "Marp.ExportPdf")) {
            val action = actions.getAction(id)
            val withProject = event(action)
            action.update(withProject)
            assertFalse("$id in a plain project context", withProject.presentation.isEnabledAndVisible)

            val withoutProject = event(action, DataContext.EMPTY_CONTEXT)
            action.update(withoutProject)
            assertFalse("$id without a project", withoutProject.presentation.isEnabledAndVisible)
        }
    }

    private fun fileContext(file: VirtualFile): DataContext =
        SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.VIRTUAL_FILE, file).build()

    fun testNoPreviewForAFileThatIsNotOpenInAMarpEditor() {
        val file = myFixture.addFileToProject("closed.md", "---\nmarp: true\n---\n# One\n").virtualFile
        val action = actions.getAction("Marp.ExportHtml")
        assertNull(MarpExporter.previewOf(event(action, fileContext(file))))
        val e = event(action, fileContext(file))
        action.update(e)
        assertFalse(e.presentation.isEnabledAndVisible)
    }

    /** The light fixture's editor manager does not use the plugin's provider, so the split editor is created by hand. */
    private fun splitEditor(name: String): MarpSplitEditor {
        val file = myFixture.addFileToProject(name, "---\nmarp: true\n---\n# One\n").virtualFile
        val editor = MarpSplitEditorProvider().createEditor(project, file)
        // The split editor builds its floating toolbar in a later EDT event, which must not run after the dispose.
        Disposer.register(testRootDisposable) {
            UIUtil.dispatchAllInvocationEvents()
            Disposer.dispose(editor)
        }
        return editor as MarpSplitEditor
    }

    fun testFindsThePreviewOfTheSplitEditorInTheDataContext() {
        val editor = splitEditor("open.md")
        val action = actions.getAction("Marp.ExportHtml")
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(PlatformCoreDataKeys.FILE_EDITOR, editor).build()
        assertSame(editor.preview, MarpExporter.previewOf(event(action, context)))

        // Whether the action shows depends on JCEF, which the preview needs.
        val e = event(action, context)
        action.update(e)
        assertEquals(editor.preview.panel != null, e.presentation.isEnabledAndVisible)
    }
}
