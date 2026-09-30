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
 * comment or in the front matter of a Marp deck: `_class`, `paginate`, `marp` and so on. Only the key has documentation,
 * not the value or a note.
 *
 * With the YAML plugin the front matter is an injected YAML file. The IDE asks about the injected file first and then
 * about the Markdown file (hover), so both are mapped to the Markdown file ([MarpFrontMatter.hostOf]) and end up with the
 * same target. The platform merges the targets of all providers and the YAML plugin has no target provider of its own
 * (only a legacy PSI-fallback documentation provider), so this provider, which is empty except on Marp keys, needs no
 * ordering.
 */
class MarpDirectiveDocumentationTargetProvider : DocumentationTargetProvider, DumbAware {

    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        val (entries, at) = MarpDirectiveComments.entriesAt(file, offset) ?: return emptyList()
        return targetOf(entries, at)
    }

    /** The documentation of the key at [offset] (in the coordinates of the entries), if it is a directive. */
    private fun targetOf(entries: List<MarpDirectiveEntry>, offset: Int): List<DocumentationTarget> {
        // The end of the key counts: the caret is often right behind the last letter.
        val entry = entries.firstOrNull { offset >= it.keyRange.startOffset && offset <= it.keyRange.endOffset } ?: return emptyList()
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
    private const val MARP_VSCODE_URL = "https://github.com/marp-team/marp-vscode"

    /** Where the directive applies, in a word: global, local, or front matter for the keys that only the front matter has. */
    fun typeText(directive: MarpDirective): String = MarpBundle.message(
        when {
            directive.origin == MarpDirectiveOrigin.MARP_VSCODE -> "completion.type.frontMatter"
            directive.scope == MarpDirectiveScope.GLOBAL -> "completion.type.global"
            else -> "completion.type.local"
        },
    )

    /** A short line for the hint: the name and where the directive applies. */
    fun hint(directive: MarpDirective): String = MarpBundle.message("directive.doc.hint", directive.name, typeText(directive))

    /** The documentation popup: the name, the description and scope, then the values and who defines the directive. */
    fun html(directive: MarpDirective): String = buildString {
        append(DocumentationMarkup.DEFINITION_START).append(directive.name).append(DocumentationMarkup.DEFINITION_END)
        append(DocumentationMarkup.CONTENT_START)
        append("<p>").append(MarpBundle.message(directive.docKey)).append("</p>")
        val scope = when {
            directive.origin == MarpDirectiveOrigin.MARP_VSCODE -> "directive.doc.scope.frontMatter"
            directive.scope == MarpDirectiveScope.GLOBAL -> "directive.doc.scope.global"
            else -> "directive.doc.scope.local"
        }
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
            MarpDirectiveOrigin.MARP_VSCODE -> "directive.doc.origin.vscode" to MARP_VSCODE_URL
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
