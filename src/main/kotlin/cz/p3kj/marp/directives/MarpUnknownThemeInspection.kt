package cz.p3kj.marp.directives

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import cz.p3kj.marp.MarpBundle
import cz.p3kj.marp.themes.MarpThemeService
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownFile

/**
 * Reports a `theme:` directive (in the front matter or in a directive comment) that names a theme which is neither
 * built into marp-core nor found in the custom themes, with quick fixes to add one ([MarpThemeQuickFixes]). A theme name
 * is taken as written and is case-sensitive, like in Marpit.
 *
 * Whether a name is unknown depends on files and settings outside the document, which load in the background, so the
 * inspection is quiet whenever it cannot be sure: while the themes are not loaded yet, in an untrusted project (no custom
 * theme loads there) and while any theme source has an error (a missing file, a failed download, a broken `.marprc`),
 * see [MarpThemeService.themeNamesForInspection]. The preview banner names those problems. It never waits for the
 * themes: the service highlights the open files again when they are loaded.
 *
 * It is separate from [MarpDirectiveInspection] because it depends on that external state and can be turned off alone.
 */
class MarpUnknownThemeInspection : LocalInspectionTool(), DumbAware {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : PsiElementVisitor() {
        override fun visitElement(element: PsiElement) {
            // The Markdown file only: with the XML module the same file also has an HTML root, which is visited too.
            if (element is MarkdownFile) {
                if (MarpDirectiveComments.isMarpDeck(element)) checkFrontMatter(element, holder)
                return
            }
            if (!MarpDirectiveComments.isCommentElement(element)) return
            if (!MarpDirectiveComments.isMarpDeck(element.containingFile)) return
            checkComment(element, holder)
        }
    }

    private fun checkFrontMatter(file: PsiFile, holder: ProblemsHolder) {
        val text = MarpFrontMatter.text(file) ?: return
        val frontMatter = MarpFrontMatter.parse(text) ?: return
        if (!frontMatter.isMapping) return
        check(file, frontMatter.entries, holder)
    }

    private fun checkComment(element: PsiElement, holder: ProblemsHolder) {
        val comment = MarpDirectiveComments.parse(element.text) ?: return
        if (!comment.isMapping || comment.isMagic) return
        check(element, comment.entries, holder)
    }

    private fun check(element: PsiElement, entries: List<MarpDirectiveEntry>, holder: ProblemsHolder) {
        for (entry in entries) {
            val key = entry.resolved as? MarpDirectiveKey.Known ?: continue
            if (!key.directive.themeValue) continue
            val range = entry.valueRange ?: continue
            val name = entry.value
            if (name.isBlank()) continue
            // Asked only now, so that a file without a theme directive never starts loading the themes.
            val custom = MarpThemeService.getInstance(holder.project).themeNamesForInspection() ?: return
            if (name in MarpDirectiveCatalog.BUILT_IN_THEMES || name in custom) continue
            holder.registerProblem(
                element, MarpBundle.message("inspection.unknownTheme.problem", name), ProblemHighlightType.GENERIC_ERROR_OR_WARNING, range,
                *MarpThemeQuickFixes.forUnknownTheme(holder.project, name),
            )
        }
    }
}
