package cz.p3kj.marp.templates

import com.intellij.icons.AllIcons
import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.actions.CreateFileFromTemplateDialog
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import cz.p3kj.marp.MarpBundle

/**
 * File | New | Marp Presentation: asks for a name and creates a Markdown deck from the internal file template
 * [TEMPLATE_NAME] (`fileTemplates/internal/Marp Presentation.md.ft`). It is the same mechanism as the platform's
 * "HTML File" action. The action is hidden by the platform when there is no target directory.
 */
class MarpNewPresentationAction : CreateFileFromTemplateAction(), DumbAware {

    override fun buildDialog(project: Project, directory: PsiDirectory, builder: CreateFileFromTemplateDialog.Builder) {
        builder
            .setTitle(MarpBundle.message("newPresentation.dialog.title"))
            .addKind(MarpBundle.message("newPresentation.kind"), AllIcons.FileTypes.Markdown, TEMPLATE_NAME)
    }

    override fun getActionName(directory: PsiDirectory?, newName: String, templateName: String?): String =
        MarpBundle.message("newPresentation.command", newName)

    companion object {
        /** Name of the internal file template, without the extension. */
        const val TEMPLATE_NAME: String = "Marp Presentation"
    }
}
