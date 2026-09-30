package cz.p3kj.marp.folding

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import cz.p3kj.marp.MarpBundle
import cz.p3kj.marp.directives.MarpDirectiveComments
import cz.p3kj.marp.slides.MarpDeck
import cz.p3kj.marp.slides.MarpSlideParser
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownFile

/**
 * One fold region per slide of a Marp deck. A region starts at the end of the line that starts the slide (the closing
 * front matter line of the first slide, the `---` line, or the heading line of a slide that `headingDivider` started)
 * and ends after the last visible character of the slide, so the folded text reads `---[Slide 3: Agenda]` and blank
 * lines before the next slide stay visible. The slides are the same as in the preview and the Structure view, and slides
 * with nothing after their first line have no region. Regions are expanded by default and only exist in Marp decks.
 *
 * Registered with `order="first"`: the Markdown plugin folds a heading up to the next heading of the same or a higher
 * level, and that region often runs across a `---` line. The platform refuses a region that overlaps an earlier one and
 * builders run in extension order, so the slide regions have to come first. What is lost is only such heading regions
 * (they would fold parts of two slides) and, rarely, a list or block quote that a `headingDivider` heading splits.
 */
class MarpSlideFoldingBuilder : FoldingBuilderEx(), DumbAware {

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        if (root !is MarkdownFile || !MarpDirectiveComments.isMarpDeck(root)) return FoldingDescriptor.EMPTY_ARRAY
        val folds = folds(MarpSlideParser.deck(root), document)
        if (folds.isEmpty()) return FoldingDescriptor.EMPTY_ARRAY
        return Array(folds.size) {
            val fold = folds[it]
            FoldingDescriptor(root.node, fold.range, null, placeholder(fold), false, emptySet())
        }
    }

    /** Not used, every descriptor carries its own placeholder. */
    override fun getPlaceholderText(node: ASTNode): String = "..."

    override fun isCollapsedByDefault(node: ASTNode): Boolean = false

    /** A slide region: [number] is one-based, [title] is the first heading below the first line of the slide. */
    internal data class SlideFold(val range: TextRange, val number: Int, val title: String?)

    companion object {
        private const val MAX_TITLE_LENGTH = 60

        /** The regions of [deck], ascending and without overlaps. Offsets are clamped to [document], which may be a bit newer than the PSI. */
        internal fun folds(deck: MarpDeck, document: Document): List<SlideFold> {
            val text = document.charsSequence
            val length = text.length
            val folds = ArrayList<SlideFold>(deck.slides.size)
            for (slide in deck.slides) {
                // The visible first line of the slide: the last line of the front matter, the `---` line, the heading line.
                val headEnd = (if (slide.bodyOffset > slide.startOffset) slide.bodyOffset - 1 else slide.startOffset).coerceIn(0, length)
                val headLine = document.getLineNumber(headEnd)
                val start = document.getLineEndOffset(headLine)
                // endOffset is the start of the next slide, so trimming blank space never reaches its `---` or heading line.
                var end = minOf(slide.endOffset, length)
                while (end > start && text[end - 1].isWhitespace()) end--
                if (end <= start) continue
                val title = slide.headings.firstOrNull()?.takeIf { it.offset >= start }?.text?.takeIf { it.isNotEmpty() }
                folds += SlideFold(TextRange(start, end), slide.index + 1, title)
            }
            return folds
        }

        private fun placeholder(fold: SlideFold): String {
            // Strings, so that a slide number of 1000 or more is not formatted as "1,000".
            val number = fold.number.toString()
            val title = fold.title ?: return MarpBundle.message("folding.slide.untitled", number)
            return MarpBundle.message("folding.slide.titled", number, StringUtil.shortenTextWithEllipsis(title, MAX_TITLE_LENGTH, 0))
        }
    }
}
