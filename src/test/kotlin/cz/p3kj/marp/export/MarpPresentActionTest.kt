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
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.util.ui.UIUtil
import cz.p3kj.marp.MarpBundle
import cz.p3kj.marp.MarpLightTestCase
import cz.p3kj.marp.editor.MarpSplitEditor
import cz.p3kj.marp.editor.MarpSplitEditorProvider

class MarpPresentActionTest : MarpLightTestCase() {

    private val actions get() = ActionManager.getInstance()

    private fun event(action: AnAction, context: DataContext = SimpleDataContext.getProjectContext(project)): AnActionEvent =
        TestActionEvent.createTestEvent(action, context)

    private fun childIds(group: ActionGroup): List<String> =
        (group as DefaultActionGroup).getChildren(actions).filter { it !is Separator }.map { actions.getId(it) ?: "<unregistered ${it.javaClass.name}>" }

    fun testActionIsRegisteredWithItsTexts() {
        val action = actions.getAction("Marp.Present")
        assertNotNull("Marp.Present is not registered", action)
        assertTrue(action is MarpPresentAction)
        assertTrue("must be DumbAware", action is DumbAware)
        assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
        assertEquals("Present Deck", action.templatePresentation.text)
        assertEquals(MarpBundle.message("action.Marp.Present.text"), action.templatePresentation.text)
        assertEquals(MarpBundle.message("action.Marp.Present.description"), action.templatePresentation.description)
        assertNotNull("the toolbar button needs an icon", action.templatePresentation.icon)
    }

    fun testActionIsFoundBySynonymInFindAction() {
        assertEquals(
            listOf(MarpBundle.message("action.Marp.Present.synonym"), MarpBundle.message("action.Marp.Present.synonym.slideshow")),
            actions.getAction("Marp.Present").synonyms.map { it.get() },
        )
    }

    fun testToolbarHoldsItRightBeforeTheExportPopup() {
        val toolbar = actions.getAction("Marp.PreviewToolbar") as ActionGroup
        val ids = childIds(toolbar)
        assertTrue(ids.toString(), "Marp.Present" in ids)
        assertEquals(ids.toString(), ids.indexOf("Marp.Export") - 1, ids.indexOf("Marp.Present"))
    }

    fun testHiddenWithoutAMarpEditor() {
        val action = actions.getAction("Marp.Present")
        val withProject = event(action)
        action.update(withProject)
        assertFalse("in a plain project context", withProject.presentation.isEnabledAndVisible)

        val withoutProject = event(action, DataContext.EMPTY_CONTEXT)
        action.update(withoutProject)
        assertFalse("without a project", withoutProject.presentation.isEnabledAndVisible)
    }

    fun testHiddenForAFileThatIsNotOpenInAMarpEditor() {
        val file = myFixture.addFileToProject("closed.md", "---\nmarp: true\n---\n# One\n").virtualFile
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.VIRTUAL_FILE, file).build()
        val action = actions.getAction("Marp.Present")
        assertNull(MarpExporter.splitEditorOf(event(action, context)))
        val e = event(action, context)
        action.update(e)
        assertFalse(e.presentation.isEnabledAndVisible)
    }

    // The slide the presentation starts at ----------------------------------------------------------------------------

    private val deck = "---\nmarp: true\n---\n\n# One\n\n---\n\n# Two\n\n---\n\n# Three\n"

    private fun startSlide(caretOffset: Int): Int? {
        myFixture.editor.caretModel.moveToOffset(caretOffset)
        var start: Int? = null
        PlatformTestUtil.waitForPromise(MarpPresenter.withStartSlide(project, myFixture.editor) { start = it })
        return start
    }

    fun testStartsAtTheSlideUnderTheCaret() {
        myFixture.configureByText("deck.md", deck)
        val text = myFixture.editor.document.text
        assertEquals(0, startSlide(text.indexOf("# One")))
        assertEquals(1, startSlide(text.indexOf("# Two")))
        assertEquals(2, startSlide(text.indexOf("# Three") + 3))
        assertEquals("the front matter belongs to the first slide", 0, startSlide(0))
        assertEquals("a separator belongs to the slide it starts", 1, startSlide(text.indexOf("---\n\n# Two")))
    }

    fun testStartsAtTheFirstSlideOutsideAMarpDeck() {
        myFixture.configureByText("plain.md", "# One\n\n---\n\n# Two\n")
        assertEquals(0, startSlide(myFixture.editor.document.text.indexOf("# Two")))
    }

    fun testStartsAtTheHeadingDividerSlide() {
        myFixture.configureByText("divided.md", "---\nmarp: true\nheadingDivider: 2\n---\n\n## A\n\ntext\n\n## B\n\ntext\n")
        val text = myFixture.editor.document.text
        assertEquals(0, startSlide(text.indexOf("## A")))
        assertEquals(1, startSlide(text.indexOf("## B")))
    }

    // The action with a split editor ----------------------------------------------------------------------------------

    /** The light fixture's editor manager does not use the plugin's provider, so the split editor is created by hand. */
    private fun splitEditor(name: String): MarpSplitEditor {
        val file = myFixture.addFileToProject(name, deck).virtualFile
        val editor = MarpSplitEditorProvider().createEditor(project, file)
        // The split editor builds its floating toolbar in a later EDT event, which must not run after the dispose.
        Disposer.register(testRootDisposable) {
            UIUtil.dispatchAllInvocationEvents()
            Disposer.dispose(editor)
        }
        return editor as MarpSplitEditor
    }

    fun testFindsTheSplitEditorInTheDataContext() {
        val editor = splitEditor("open.md")
        val action = actions.getAction("Marp.Present")
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(PlatformCoreDataKeys.FILE_EDITOR, editor)
            .build()
        assertSame(editor, MarpExporter.splitEditorOf(event(action, context)))
        assertSame(editor.preview, MarpExporter.previewOf(event(action, context)))

        // Whether the action shows depends on JCEF, which the preview needs.
        val e = event(action, context)
        action.update(e)
        assertEquals(editor.preview.panel != null, e.presentation.isEnabledAndVisible)
    }
}
