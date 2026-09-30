package cz.p3kj.marp.images

import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.model.Pointer
import com.intellij.openapi.project.DumbAware
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiFile
import cz.p3kj.marp.MarpBundle
import cz.p3kj.marp.directives.docSection

/**
 * Quick documentation (and the hover popup, which uses the same providers) for the keyword under the caret in the alt
 * text of an image in a Marp deck: `bg`, `left:40%`, `w:400`, `sepia` and so on. The whole word counts, and the caret
 * right behind its last letter too. Like [cz.p3kj.marp.directives.MarpDirectiveDocumentationTargetProvider] this
 * provider is empty everywhere else, so it needs no ordering.
 */
class MarpImageDocumentationTargetProvider : DocumentationTargetProvider, DumbAware {

    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        val spot = MarpImageSyntax.spotAt(file, offset) ?: return emptyList()
        val keyword = MarpImageKeywordCatalog.resolve(spot.word) ?: return emptyList()
        return listOf(MarpImageDocumentationTarget(keyword))
    }
}

/** The documentation of one keyword, built by [MarpImageDocs]. It holds no PSI, so its pointer is the target itself. */
class MarpImageDocumentationTarget(val keyword: MarpImageKeyword) : DocumentationTarget {

    override fun createPointer(): Pointer<out DocumentationTarget> = Pointer.hardPointer(this)

    override fun computePresentation(): TargetPresentation = TargetPresentation.builder(keyword.name).presentation()

    override fun computeDocumentationHint(): String = MarpImageDocs.hint(keyword)

    override fun computeDocumentation(): DocumentationResult = DocumentationResult.documentation(MarpImageDocs.html(keyword))

    override fun equals(other: Any?): Boolean = other is MarpImageDocumentationTarget && other.keyword == keyword

    override fun hashCode(): Int = keyword.hashCode()
}

/** The texts of the image keyword documentation. Everything user-visible comes from the message bundle. */
object MarpImageDocs {

    private const val MARPIT_URL = "https://marpit.marp.app/image-syntax"

    /** What the keyword does, in a word: background, size or filter. */
    fun typeText(keyword: MarpImageKeyword): String = MarpBundle.message(
        when (keyword.kind) {
            MarpImageKeywordKind.FLAG -> "image.completion.type.background"
            MarpImageKeywordKind.KEY_VALUE -> "image.completion.type.size"
            MarpImageKeywordKind.FILTER -> "image.completion.type.filter"
        },
    )

    /** A short line for the hint: the keyword and what it does. */
    fun hint(keyword: MarpImageKeyword): String = MarpBundle.message("image.doc.hint", keyword.name, typeText(keyword))

    /** The documentation popup: the keyword, the description, then the default and who defines it. */
    fun html(keyword: MarpImageKeyword): String = buildString {
        append(DocumentationMarkup.DEFINITION_START).append(keyword.lookupString).append(DocumentationMarkup.DEFINITION_END)
        append(DocumentationMarkup.CONTENT_START)
        append("<p>").append(MarpBundle.message(keyword.docKey)).append("</p>")
        append(DocumentationMarkup.CONTENT_END)
        append(DocumentationMarkup.SECTIONS_START)
        keyword.defaultValue?.let { docSection(MarpBundle.message("image.doc.default"), "<code>$it</code>") }
        docSection(MarpBundle.message("image.doc.origin"), "<a href=\"$MARPIT_URL\">${MarpBundle.message("image.doc.origin.marpit")}</a>")
        append(DocumentationMarkup.SECTIONS_END)
    }
}
