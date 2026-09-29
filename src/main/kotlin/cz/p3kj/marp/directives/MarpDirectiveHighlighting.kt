package cz.p3kj.marp.directives

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.PlainSyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import cz.p3kj.marp.MarpBundle
import org.intellij.plugins.markdown.lang.MarkdownLanguage
import javax.swing.Icon

/** The colors of directive comments. They can be changed in Settings | Editor | Color Scheme | Marp. */
object MarpDirectiveHighlighting {
    val KEY: TextAttributesKey = createTextAttributesKey("MARP_DIRECTIVE_KEY", DefaultLanguageHighlighterColors.METADATA)
    val VALUE: TextAttributesKey = createTextAttributesKey("MARP_DIRECTIVE_VALUE", DefaultLanguageHighlighterColors.STRING)
    val NOTE: TextAttributesKey = createTextAttributesKey("MARP_PRESENTER_NOTE", DefaultLanguageHighlighterColors.DOC_COMMENT)
}

/**
 * Colors the keys and values of directive comments in Marp decks, and shows presenter notes (comments that are not
 * directives) in a different color, so it is visible at a glance what Marp does with a comment. Comments of other
 * tools (`prettier-ignore`, `markdownlint-disable`) and `<!-- fit -->` in headings keep the plain comment color.
 * Runs on the Markdown tree, where the comment is one HTML block or one inline HTML tag.
 */
class MarpDirectiveAnnotator : Annotator, DumbAware {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (!MarpDirectiveComments.isCommentElement(element)) return
        if (!MarpDirectiveComments.isMarpDeck(element.containingFile)) return
        val comment = MarpDirectiveComments.parse(element.text) ?: return
        if (comment.body.isEmpty() || comment.isMagic || MarpDirectiveComments.isFitComment(element, comment)) return
        val start = element.textRange.startOffset
        if (!comment.looksLikeDirective) {
            mark(holder, MarpDirectiveHighlighting.NOTE, comment.range.shiftRight(start))
            return
        }
        for (entry in comment.entries) {
            mark(holder, MarpDirectiveHighlighting.KEY, entry.keyRange.shiftRight(start))
            entry.valueRange?.let { mark(holder, MarpDirectiveHighlighting.VALUE, it.shiftRight(start)) }
        }
    }

    private fun mark(holder: AnnotationHolder, key: TextAttributesKey, range: TextRange) {
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(range).textAttributes(key).create()
    }
}

/** Makes the directive colors adjustable: without a page, the keys would not be listed in the color scheme settings. */
class MarpColorSettingsPage : ColorSettingsPage {

    override fun getDisplayName(): String = MarpBundle.message("color.settings.name")

    override fun getIcon(): Icon? = null

    override fun getHighlighter(): SyntaxHighlighter =
        SyntaxHighlighterFactory.getSyntaxHighlighter(MarkdownLanguage.INSTANCE, null, null) ?: PlainSyntaxHighlighter()

    override fun getDemoText(): String = """
        ---
        marp: true
        ---

        <!-- <key>_class</key>: <value>lead</value> -->

        # Hello

        <!-- <key>paginate</key>: <value>true</value> -->

        <note><!-- Say hello, then show the demo. --></note>
    """.trimIndent()

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = mapOf(
        "key" to MarpDirectiveHighlighting.KEY,
        "value" to MarpDirectiveHighlighting.VALUE,
        "note" to MarpDirectiveHighlighting.NOTE,
    )

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = arrayOf(
        AttributesDescriptor(MarpBundle.message("color.settings.key"), MarpDirectiveHighlighting.KEY),
        AttributesDescriptor(MarpBundle.message("color.settings.value"), MarpDirectiveHighlighting.VALUE),
        AttributesDescriptor(MarpBundle.message("color.settings.note"), MarpDirectiveHighlighting.NOTE),
    )

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
}
