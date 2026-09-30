package cz.p3kj.marp.images

import com.intellij.psi.PsiFile
import cz.p3kj.marp.MarpDetector
import cz.p3kj.marp.directives.MarpDirectiveComments
import cz.p3kj.marp.directives.MarpFrontMatter
import org.intellij.plugins.markdown.lang.MarkdownElementTypes
import org.intellij.plugins.markdown.lang.MarkdownTokenTypes
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownFile

/**
 * The word being completed in the alt text of an image: where the alt text is ([altStart] to [altEnd], between `![`
 * and `]`), the word around the caret ([wordStart], [wordEnd], [word]), the part of it before the caret ([prefix]) and
 * the other words of the alt text.
 */
class MarpImageAltSpot(
    val altStart: Int,
    val altEnd: Int,
    val wordStart: Int,
    val wordEnd: Int,
    val prefix: String,
    val word: String,
    val otherWords: List<String>,
)

/**
 * Finds the alt text of an image at a caret, for completion, its autopopup and the hover documentation.
 *
 * The alt text is found in the text of the line, not in the syntax tree: while `![b` is being typed the tree has no
 * image yet (only `!`, `[` and text), and `![b]` alone may parse as a reference link. The tree is only asked to reject
 * code and HTML, see [spotAt].
 */
object MarpImageSyntax {

    /**
     * The alt text around [caret] in [text], `null` when the caret is not between `![` and the closing `]` on its line.
     * From the caret the line is scanned back: a `]` or a `[` without a `!` before it means the caret is elsewhere (in a
     * link, in the URL, after the image). The alt text ends at the first `]` or the end of the line behind the caret.
     * An escaped `\![` is not an image. Other escaped brackets and alt text over several lines are not handled.
     */
    fun altSpot(text: CharSequence, caret: Int): MarpImageAltSpot? {
        if (caret < 0 || caret > text.length) return null
        var altStart = -1
        var i = caret - 1
        while (i >= 0) {
            val c = text[i]
            if (c == '\n' || c == '\r' || c == ']') return null
            if (c == '[') {
                if (i > 0 && text[i - 1] == '!' && (i < 2 || text[i - 2] != '\\')) altStart = i + 1
                break
            }
            i--
        }
        if (altStart < 0) return null
        var altEnd = caret
        while (altEnd < text.length && text[altEnd] != ']' && text[altEnd] != '\n' && text[altEnd] != '\r') altEnd++
        var wordStart = caret
        while (wordStart > altStart && !text[wordStart - 1].isWhitespace()) wordStart--
        var wordEnd = caret
        while (wordEnd < altEnd && !text[wordEnd].isWhitespace()) wordEnd++
        val others = (text.subSequence(altStart, wordStart).toString() + " " + text.subSequence(wordEnd, altEnd))
            .split(WHITESPACE).filter { it.isNotEmpty() }
        return MarpImageAltSpot(
            altStart, altEnd, wordStart, wordEnd,
            text.subSequence(wordStart, caret).toString(), text.subSequence(wordStart, wordEnd).toString(), others,
        )
    }

    /**
     * The alt text at [offset] of [file] (an injected file is mapped to its Markdown file first), `null` outside Marp
     * decks and where the text is no Markdown: the front matter, code blocks and spans, and HTML.
     */
    fun spotAt(file: PsiFile, offset: Int): MarpImageAltSpot? {
        val (host, hostOffset) = MarpFrontMatter.hostOf(file, offset)
        if (!MarpDirectiveComments.isMarpDeck(host)) return null
        val text = MarpFrontMatter.text(host) ?: return null
        val frontMatter = MarpDetector.findFrontMatter(text)
        if (frontMatter != null && hostOffset < frontMatter.endOffset) return null
        val spot = altSpot(text, hostOffset) ?: return null
        val markdown = MarpDirectiveComments.markdownFile(host) ?: return null
        if (isCodeOrHtml(markdown, spot.altStart - 1)) return null
        return spot
    }

    /**
     * Whether the leaf at [offset] sits in a code fence, code block, code span, HTML block or inline HTML tag. The
     * Markdown tree of the file is walked, `PsiFile.findElementAt` would return a leaf of the HTML template tree.
     */
    private fun isCodeOrHtml(markdown: MarkdownFile, offset: Int): Boolean {
        var node = markdown.node.findLeafElementAt(offset)
        while (node != null && node.psi !is PsiFile) {
            val type = node.elementType
            if (type == MarkdownElementTypes.CODE_FENCE || type == MarkdownElementTypes.CODE_BLOCK ||
                type == MarkdownElementTypes.CODE_SPAN || type == MarkdownElementTypes.HTML_BLOCK ||
                type == MarkdownTokenTypes.HTML_TAG
            ) {
                return true
            }
            node = node.treeParent
        }
        return false
    }

    /**
     * Whether the alt text at [spot] is being written as keywords: every other word already is one (true for the first
     * word). A real description such as `A photo of` is not, so typing it does not open the popup.
     */
    fun optionsLikely(spot: MarpImageAltSpot): Boolean = spot.otherWords.all { MarpImageKeywordCatalog.resolve(it) != null }

    /** Whether the alt text has the word `bg` (besides the word at the caret), which makes the image a background. */
    fun hasBackground(spot: MarpImageAltSpot): Boolean = MarpImageKeywordCatalog.BG.name in spot.otherWords

    private val WHITESPACE = Regex("\\s+")
}
