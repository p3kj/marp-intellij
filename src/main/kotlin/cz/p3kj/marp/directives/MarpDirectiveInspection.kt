package cz.p3kj.marp.directives

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import cz.p3kj.marp.MarpBundle
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownFile

/**
 * Reports what Marp silently ignores in the directive comments and in the front matter of a Marp deck:
 * - a directive name that does not exist (with a "did you mean" for near misses),
 * - a global directive written with an underscore, such as `_theme`, which only exists for local directives,
 * - a value that Marp does not accept for `paginate`, `math` and `headingDivider`.
 *
 * A comment that has no valid directive at all is a presenter note to Marp, so it is left alone unless a key is a
 * near miss of a directive, as in `Class: lead` (a weak warning, since it may well be a note). Comments of other tools (`prettier-ignore`, `markdownlint-disable`)
 * are skipped. Unknown theme names are not reported, the preview warns about them.
 *
 * The front matter holds the metadata of other tools too (`title`, `author`, ...), so an unknown key there is only a weak
 * warning when it is a near miss of a directive, and nothing otherwise. It is found by [MarpFrontMatter] in the text of the
 * file, not in the PSI: the problems are registered on the file itself with document offsets (the Markdown plugin's front
 * matter element is experimental API), and the Markdown file element is visited like any other element. The YAML plugin
 * reports YAML errors, such as a repeated key, so those are not repeated here.
 */
class MarpDirectiveInspection : LocalInspectionTool(), DumbAware {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : PsiElementVisitor() {
        override fun visitElement(element: PsiElement) {
            // The Markdown file only: with the XML module the same file also has an HTML root, which is visited too.
            if (element is MarkdownFile) {
                if (MarpDirectiveComments.isMarpDeck(element)) checkFrontMatter(element, holder)
                return
            }
            if (!MarpDirectiveComments.isCommentElement(element)) return
            if (!MarpDirectiveComments.isMarpDeck(element.containingFile)) return
            check(element, holder)
        }
    }

    private fun checkFrontMatter(file: PsiFile, holder: ProblemsHolder) {
        val text = MarpFrontMatter.text(file) ?: return
        val frontMatter = MarpFrontMatter.parse(text) ?: return
        if (!frontMatter.isMapping) return
        for (entry in frontMatter.entries) {
            when (val key = entry.resolved) {
                is MarpDirectiveKey.GlobalWithUnderscore -> report(
                    holder, file, entry.keyRange,
                    MarpBundle.message("inspection.directive.globalWithUnderscore", entry.key, key.directive.name),
                )
                // Other keys are the metadata of other tools, only a near miss of a directive is worth a hint.
                is MarpDirectiveKey.Unknown -> key.suggestion?.let { suggestion ->
                    report(
                        holder, file, entry.keyRange,
                        MarpBundle.message("inspection.frontMatter.unknown", entry.key, suggestion),
                        ProblemHighlightType.WEAK_WARNING,
                    )
                }
                is MarpDirectiveKey.Known -> checkValue(holder, file, entry, key.directive)
            }
        }
    }

    private fun check(element: PsiElement, holder: ProblemsHolder) {
        val comment = MarpDirectiveComments.parse(element.text) ?: return
        if (!comment.isMapping || comment.isMagic) return
        val isDirective = comment.isDirective
        for (entry in comment.entries) {
            when (val key = entry.resolved) {
                is MarpDirectiveKey.GlobalWithUnderscore -> report(
                    holder, element, entry.keyRange,
                    MarpBundle.message("inspection.directive.globalWithUnderscore", entry.key, key.directive.name),
                )
                is MarpDirectiveKey.Unknown -> {
                    val suggestion = key.suggestion
                    when {
                        isDirective && suggestion != null -> report(
                            holder, element, entry.keyRange,
                            MarpBundle.message("inspection.directive.unknown.suggestion", entry.key, suggestion),
                        )
                        isDirective -> report(holder, element, entry.keyRange, MarpBundle.message("inspection.directive.unknown", entry.key))
                        // Notes like `Header: welcome` are plausible, so this one is only a weak warning.
                        suggestion != null -> report(
                            holder, element, entry.keyRange,
                            MarpBundle.message("inspection.directive.unknownInNote", entry.key, suggestion),
                            ProblemHighlightType.WEAK_WARNING,
                        )
                    }
                }
                is MarpDirectiveKey.Known -> checkValue(holder, element, entry, key.directive)
            }
        }
    }

    /** Only inline values are checked; a value that is empty or continues on the next lines (a list) is left alone. */
    private fun checkValue(holder: ProblemsHolder, element: PsiElement, entry: MarpDirectiveEntry, directive: MarpDirective) {
        val range = entry.valueRange ?: return
        if (MarpDirectiveCatalog.isValid(directive, entry.rawValue, entry.value)) return
        val message = if (directive.check == MarpValueCheck.HEADING_DIVIDER) {
            MarpBundle.message("inspection.directive.invalidHeadingDivider", entry.rawValue)
        }
        else {
            MarpBundle.message("inspection.directive.invalidValue", entry.value, entry.key, directive.suggestions.joinToString(", "))
        }
        report(holder, element, range, message)
    }

    private fun report(
        holder: ProblemsHolder, element: PsiElement, range: TextRange, message: String,
        type: ProblemHighlightType = ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
    ) {
        holder.registerProblem(element, message, type, range)
    }
}
