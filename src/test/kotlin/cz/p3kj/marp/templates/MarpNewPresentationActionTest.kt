package cz.p3kj.marp.templates

import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Computable
import com.intellij.psi.PsiFile
import cz.p3kj.marp.MarpBundle
import cz.p3kj.marp.MarpDetector
import cz.p3kj.marp.MarpLightTestCase

class MarpNewPresentationActionTest : MarpLightTestCase() {

    private val actions get() = ActionManager.getInstance()
    private val templates get() = FileTemplateManager.getInstance(project)

    fun testActionIsRegisteredInTheNewMenu() {
        val action = actions.getAction("Marp.NewPresentation")
        assertTrue(action is MarpNewPresentationAction)
        assertEquals(MarpBundle.message("action.Marp.NewPresentation.text"), action.templatePresentation.text)
        assertEquals(MarpBundle.message("action.Marp.NewPresentation.description"), action.templatePresentation.description)
        assertNotNull(action.templatePresentation.icon)
        assertEquals(
            listOf(MarpBundle.message("action.Marp.NewPresentation.synonym")),
            action.synonyms.map { it.get() },
        )

        val newGroup = actions.getAction("NewGroup") as DefaultActionGroup
        assertTrue(newGroup.getChildren(actions).any { it === action })
    }

    fun testTemplateIsAnInternalMarkdownTemplateWithoutVariables() {
        val template = templates.getInternalTemplate(MarpNewPresentationAction.TEMPLATE_NAME)
        assertEquals("md", template.extension)
        assertEquals(emptyList<String>(), template.getUnsetAttributes(templates.defaultProperties, project).toList())
    }

    fun testCreatesADeckThatIsDetectedAsMarp() {
        val template = templates.getInternalTemplate(MarpNewPresentationAction.TEMPLATE_NAME)
        val directory = myFixture.psiManager.findDirectory(myFixture.tempDirFixture.findOrCreateDir("slides"))!!

        val file: PsiFile = checkNotNull(WriteCommandAction.runWriteCommandAction(project, Computable {
            CreateFileFromTemplateAction.createFileFromTemplate("deck", template, directory, null, false)
        }))

        assertEquals("deck.md", file.name)
        assertTrue(MarpDetector.isMarpFile(file.virtualFile))
        assertEquals(template.getText(templates.defaultProperties), file.text)
        assertTrue(file.text.startsWith("---\nmarp: true\n"))
        assertTrue(file.text.endsWith("Questions?\n"))
        assertTrue(file.text.contains("<!-- _class: lead -->"))
        assertTrue(file.text.contains("\n## "))
        // The Velocity escape that keeps `##` and `$` in the template literal must not leak into the deck.
        assertFalse(file.text.contains("#[["))
        assertFalse(file.text.contains("]]#"))
    }
}
