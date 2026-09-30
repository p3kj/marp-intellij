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
 * front matter line of the first slide, the `---` line) and ends after the last visible character of the slide, so the
 * folded text reads `---[Slide 3: Agenda]` and blank lines before the next slide stay visible. A slide that
 * `headingDivider` started begins with its heading, so its region starts at the heading and replaces it (`[Slide 4: B]`),
 * which is the range the Markdown plugin folds for that heading, so there is no second marker on the line. The slides
 * are the same as in the preview and the Structure view, and slides that would fold nothing (no second line) have no
 * region. Regions are expanded by default and only exist in Marp decks.
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

    /** A slide region: [number] is one-based, [title] is the first heading inside the region. */
    internal data class SlideFold(val range: TextRange, val number: Int, val title: String?)

    companion object {
        private const val MAX_TITLE_LENGTH = 60

        /** The regions of [deck], ascending and without overlaps. Offsets are clamped to [document] defensively. */
        internal fun folds(deck: MarpDeck, document: Document): List<SlideFold> {
            val text = document.charsSequence
            val length = text.length
            val folds = ArrayList<SlideFold>(deck.slides.size)
            for (slide in deck.slides) {
                val start = if (slide.bodyOffset == slide.startOffset) {
                    // Started by a divider heading: the region covers the heading, like the Markdown plugin's.
                    slide.startOffset.coerceIn(0, length)
                } else {
                    // Started by the front matter or a `---` line: the region begins at the end of that visible line.
                    document.getLineEndOffset(document.getLineNumber((slide.bodyOffset - 1).coerceIn(0, length)))
                }
                // endOffset is the start of the next slide, so trimming blank space never reaches its `---` or heading line.
                var end = minOf(slide.endOffset, length)
                while (end > start && text[end - 1].isWhitespace()) end--
                if (end <= start || document.getLineNumber(end) == document.getLineNumber(start)) continue
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
