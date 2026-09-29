package cz.p3kj.marp.directives

import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.model.Pointer
import com.intellij.openapi.project.DumbAware
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiFile
import cz.p3kj.marp.MarpBundle

/**
 * Quick documentation (and the hover popup, which uses the same providers) for the directive under the caret in a
 * comment of a Marp deck: `_class`, `paginate` and so on. Only the key has documentation, not the value or a note.
 */
class MarpDirectiveDocumentationTargetProvider : DocumentationTargetProvider, DumbAware {

    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        if (!MarpDirectiveComments.isMarpDeck(file)) return emptyList()
        val markdown = MarpDirectiveComments.markdownFile(file) ?: return emptyList()
        val element = MarpDirectiveComments.commentElementAt(markdown, offset) ?: return emptyList()
        val comment = MarpDirectiveComments.parse(element.text) ?: return emptyList()
        val relative = offset - element.textRange.startOffset
        // The end of the key counts: the caret is often right behind the last letter.
        val entry = comment.entries.firstOrNull { relative >= it.keyRange.startOffset && relative <= it.keyRange.endOffset }
            ?: return emptyList()
        val directive = when (val key = entry.resolved) {
            is MarpDirectiveKey.Known -> key.directive
            is MarpDirectiveKey.GlobalWithUnderscore -> key.directive
            is MarpDirectiveKey.Unknown -> return emptyList()
        }
        return listOf(MarpDirectiveDocumentationTarget(directive))
    }
}

/** The documentation of one directive, built by [MarpDirectiveDocs]. It holds no PSI, so its pointer is the target itself. */
class MarpDirectiveDocumentationTarget(val directive: MarpDirective) : DocumentationTarget {

    override fun createPointer(): Pointer<out DocumentationTarget> = Pointer.hardPointer(this)

    override fun computePresentation(): TargetPresentation = TargetPresentation.builder(directive.name).presentation()

    override fun computeDocumentationHint(): String = MarpDirectiveDocs.hint(directive)

    override fun computeDocumentation(): DocumentationResult = DocumentationResult.documentation(MarpDirectiveDocs.html(directive))

    override fun equals(other: Any?): Boolean = other is MarpDirectiveDocumentationTarget && other.directive == directive

    override fun hashCode(): Int = directive.hashCode()
}

/** The texts of the directive documentation. Everything user-visible comes from the message bundle. */
object MarpDirectiveDocs {

    private const val MARPIT_URL = "https://marpit.marp.app/directives"
    private const val MARP_CORE_URL = "https://github.com/marp-team/marp-core#readme"

    /** A short line for the hint: the name and where the directive applies. */
    fun hint(directive: MarpDirective): String =
        "${directive.name}: ${MarpBundle.message(if (directive.scope == MarpDirectiveScope.GLOBAL) "completion.type.global" else "completion.type.local")}"

    /** The documentation popup: the name, the description and scope, then the values and who defines the directive. */
    fun html(directive: MarpDirective): String = buildString {
        append(DocumentationMarkup.DEFINITION_START).append(directive.name).append(DocumentationMarkup.DEFINITION_END)
        append(DocumentationMarkup.CONTENT_START)
        append("<p>").append(MarpBundle.message(directive.docKey)).append("</p>")
        val scope = if (directive.scope == MarpDirectiveScope.GLOBAL) "directive.doc.scope.global" else "directive.doc.scope.local"
        append("<p>").append(MarpBundle.message(scope)).append("</p>")
        append(DocumentationMarkup.CONTENT_END)
        append(DocumentationMarkup.SECTIONS_START)
        val values = if (directive.themeValue) MarpDirectiveCatalog.BUILT_IN_THEMES else directive.suggestions
        if (values.isNotEmpty()) {
            section(MarpBundle.message("directive.doc.values"), values.joinToString(", ") { "<code>$it</code>" })
        }
        val (originKey, url) = when (directive.origin) {
            MarpDirectiveOrigin.MARPIT -> "directive.doc.origin.marpit" to MARPIT_URL
            MarpDirectiveOrigin.MARP_CORE -> "directive.doc.origin.core" to MARP_CORE_URL
        }
        section(MarpBundle.message("directive.doc.origin"), "<a href=\"$url\">${MarpBundle.message(originKey)}</a>")
        append(DocumentationMarkup.SECTIONS_END)
    }

    private fun StringBuilder.section(header: String, content: String) {
        append(DocumentationMarkup.SECTION_HEADER_START).append(header)
        append(DocumentationMarkup.SECTION_SEPARATOR).append(content)
        append(DocumentationMarkup.SECTION_END)
    }
}
