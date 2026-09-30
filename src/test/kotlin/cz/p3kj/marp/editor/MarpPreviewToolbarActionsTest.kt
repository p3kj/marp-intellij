package cz.p3kj.marp.editor

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.TestActionEvent
import com.intellij.util.ui.UIUtil
import cz.p3kj.marp.MarpBundle
import cz.p3kj.marp.MarpLightTestCase
import cz.p3kj.marp.settings.MarpAppSettings
import cz.p3kj.marp.settings.MarpAppSettingsListener

class MarpPreviewToolbarActionsTest : MarpLightTestCase() {

    private val settings get() = MarpAppSettings.getInstance()
    private val actions get() = ActionManager.getInstance()

    private var savedScrollSync = true
    private var savedPresenterNotes = false
    private var published = 0

    override fun setUp() {
        super.setUp()
        savedScrollSync = settings.scrollSync
        savedPresenterNotes = settings.presenterNotes
        published = 0
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(MarpAppSettingsListener.TOPIC, MarpAppSettingsListener { published++ })
    }

    override fun tearDown() {
        try {
            settings.update {
                scrollSync = savedScrollSync
                presenterNotes = savedPresenterNotes
            }
        } finally {
            super.tearDown()
        }
    }

    private fun event(action: AnAction, context: DataContext = SimpleDataContext.getProjectContext(project)): AnActionEvent =
        TestActionEvent.createTestEvent(action, context)

    private fun toggle(id: String): ToggleAction = actions.getAction(id) as ToggleAction

    private fun toolbarIds(): List<String> {
        val group = actions.getAction(MarpSplitEditor.TOOLBAR_GROUP_ID) as DefaultActionGroup
        return group.getChildren(actions)
            .filter { it !is Separator }
            .map { actions.getId(it) ?: "<unregistered ${it.javaClass.name}>" }
    }

    fun testGroupHoldsTheToolbarActions() {
        val ids = toolbarIds()
        assertEquals(
            listOf("Marp.ToggleScrollSync", "Marp.TogglePresenterNotes", "Marp.ToggleOverview", "Marp.Present", "Marp.Export", "Marp.OpenSettings"),
            ids,
        )
        for (id in ids) {
            val action = actions.getAction(id)
            assertTrue("$id must be dumb-aware", action.isDumbAware)
            val kind = if (action is ActionGroup) "group" else "action"
            assertEquals(MarpBundle.message("$kind.$id.text"), action.templatePresentation.text)
            assertEquals(MarpBundle.message("$kind.$id.description"), action.templatePresentation.description)
            assertNotNull("$id needs an icon", action.templatePresentation.icon)
        }
    }

    fun testTogglesAreFoundByMarpInFindAction() {
        assertEquals(
            listOf(MarpBundle.message("action.Marp.ToggleScrollSync.synonym")),
            actions.getAction("Marp.ToggleScrollSync").synonyms.map { it.get() },
        )
        assertEquals(
            listOf(MarpBundle.message("action.Marp.TogglePresenterNotes.synonym")),
            actions.getAction("Marp.TogglePresenterNotes").synonyms.map { it.get() },
        )
        assertEquals(
            listOf(MarpBundle.message("action.Marp.ToggleOverview.synonym")),
            actions.getAction("Marp.ToggleOverview").synonyms.map { it.get() },
        )
        assertEquals("Marp: Synchronize Scrolling", MarpBundle.message("action.Marp.ToggleScrollSync.synonym"))
        assertEquals("Marp: Show Presenter Notes", MarpBundle.message("action.Marp.TogglePresenterNotes.synonym"))
        assertEquals("Marp: Slide Overview", MarpBundle.message("action.Marp.ToggleOverview.synonym"))
    }

    fun testScrollSyncToggle() {
        checkToggle("Marp.ToggleScrollSync", { settings.scrollSync }) { value -> settings.update { scrollSync = value } }
    }

    fun testPresenterNotesToggle() {
        checkToggle("Marp.TogglePresenterNotes", { settings.presenterNotes }) { value -> settings.update { presenterNotes = value } }
    }

    private fun checkToggle(id: String, read: () -> Boolean, write: (Boolean) -> Unit) {
        val action = toggle(id)
        val e = event(action)

        val initial = read()
        assertEquals(initial, action.isSelected(e))

        // The toolbar follows changes made elsewhere, for example on the settings page.
        write(!initial)
        assertEquals(!initial, action.isSelected(e))

        // Toggling changes the setting and publishes exactly once.
        published = 0
        action.setSelected(e, initial)
        assertEquals(initial, read())
        assertEquals(initial, action.isSelected(e))
        assertEquals(1, published)

        // Selecting the current value again changes nothing and publishes nothing.
        action.setSelected(e, initial)
        assertEquals(initial, read())
        assertEquals(1, published)
    }

    fun testTogglesLeaveTheOtherSettingAlone() {
        val scrollSync = settings.scrollSync
        val presenterNotes = settings.presenterNotes

        val scrollSyncAction = toggle("Marp.ToggleScrollSync")
        scrollSyncAction.setSelected(event(scrollSyncAction), !scrollSync)
        assertEquals(!scrollSync, settings.scrollSync)
        assertEquals(presenterNotes, settings.presenterNotes)

        val notesAction = toggle("Marp.TogglePresenterNotes")
        notesAction.setSelected(event(notesAction), !presenterNotes)
        assertEquals(!scrollSync, settings.scrollSync)
        assertEquals(!presenterNotes, settings.presenterNotes)
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

    private fun editorContext(editor: MarpSplitEditor): DataContext =
        SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(PlatformCoreDataKeys.FILE_EDITOR, editor).build()

    fun testOverviewToggleIsPerEditor() {
        val action = toggle("Marp.ToggleOverview")
        val first = splitEditor("first.md")
        val second = splitEditor("second.md")
        val firstEvent = event(action, editorContext(first))
        val secondEvent = event(action, editorContext(second))

        // Off for a new editor. Headless has no JCEF, so there is no panel and the flag is all there is.
        assertFalse(first.preview.overview)
        assertFalse(action.isSelected(firstEvent))
        assertFalse(action.isSelected(secondEvent))

        action.setSelected(firstEvent, true)
        assertTrue(first.preview.overview)
        assertTrue(action.isSelected(firstEvent))
        assertFalse(second.preview.overview)
        assertFalse(action.isSelected(secondEvent))

        // The selected state is what the toolbar button shows.
        val update = event(action, editorContext(first))
        action.update(update)
        assertEquals(first.preview.panel != null, update.presentation.isEnabledAndVisible)
        assertTrue(Toggleable.isSelected(update.presentation))

        action.setSelected(secondEvent, true)
        action.setSelected(firstEvent, false)
        assertFalse(first.preview.overview)
        assertTrue(second.preview.overview)
        assertFalse(action.isSelected(firstEvent))
        assertTrue(action.isSelected(secondEvent))
    }

    fun testOverviewToggleNeedsAMarpPreview() {
        val action = toggle("Marp.ToggleOverview")
        val e = event(action)
        assertFalse(action.isSelected(e))
        action.setSelected(e, true)
        assertFalse(action.isSelected(e))
        action.update(e)
        assertFalse(e.presentation.isEnabledAndVisible)
    }

    fun testSettingsActionNeedsAProject() {
        val action = actions.getAction("Marp.OpenSettings")

        val withProject = event(action)
        action.update(withProject)
        assertTrue(withProject.presentation.isEnabled)

        val withoutProject = event(action, DataContext.EMPTY_CONTEXT)
        action.update(withoutProject)
        assertFalse(withoutProject.presentation.isEnabled)
    }
}
