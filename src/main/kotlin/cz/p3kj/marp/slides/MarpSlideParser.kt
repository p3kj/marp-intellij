package cz.p3kj.marp.slides

import com.intellij.lang.ASTNode
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import cz.p3kj.marp.MarpDetector
import org.intellij.plugins.markdown.lang.MarkdownElementTypes
import org.intellij.plugins.markdown.lang.MarkdownTokenTypes
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownFile
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownHeader

/**
 * Turns a Markdown PSI file into a [MarpDeck].
 *
 * The block structure comes from the Markdown plugin's parser, which follows CommonMark like the markdown-it parser of
 * Marpit does. That gives the right answer for everything a line scanner would have to redo: fenced and indented code
 * that contains `---`, setext headings (`Title` above `---` is a heading, not a slide break), `---` inside a
 * multi-line HTML comment or `<style>`, thematic breaks inside blockquotes and lists. The Marp rules on top of it live
 * in [MarpSlideSplitter]. The front matter is located by [MarpDetector.findFrontMatter], not by the PSI, so that it
 * follows the same rules as deck detection.
 *
 * Around `---` and `===` the Markdown plugin is not CommonMark, and the parser corrects it (see [Misread] and
 * [setextUnderline]): the plugin builds a setext heading from any single line above the underline, not only from a
 * paragraph line, so `# Title`, `---` or a one-line HTML comment directly above `---` is a setext heading for it and a
 * heading, break or comment plus a break for markdown-it. And it leaves a paragraph of several lines above `---` as a
 * paragraph and a break, where markdown-it makes a setext heading. The gaps that remain are listed in docs/ARCHITECTURE.md.
 */
object MarpSlideParser {

    /** The slides of [file], cached until the PSI changes. Call in a read action; the result reflects the committed PSI. */
    fun deck(file: MarkdownFile): MarpDeck = CachedValuesManager.getCachedValue(file) {
        CachedValueProvider.Result.create(compute(file), file)
    }

    private fun compute(file: MarkdownFile): MarpDeck {
        val text = file.text
        val frontMatter = MarpDetector.findFrontMatter(text)
        val blocks = ArrayList<MarpBlock>()
        collectTopLevel(file.node, frontMatter?.endOffset ?: 0, blocks)
        return MarpSlideSplitter.split(text, frontMatter, blocks)
    }

    private val HEADER_TYPES: Set<IElementType> = setOf(
        MarkdownElementTypes.ATX_1, MarkdownElementTypes.ATX_2, MarkdownElementTypes.ATX_3,
        MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6,
        MarkdownElementTypes.SETEXT_1, MarkdownElementTypes.SETEXT_2,
    )

    /** Blocks that Marpit does not show and that do not count as visible content: blank space and reference definitions. */
    private val INVISIBLE_TYPES: Set<IElementType> = setOf(
        TokenType.WHITE_SPACE, MarkdownTokenTypes.EOL, MarkdownElementTypes.LINK_DEFINITION,
    )

    private fun collectTopLevel(fileNode: ASTNode, skipBefore: Int, out: MutableList<MarpBlock>) {
        var offset = 0
        // The node with the `---` line that turns the paragraph before it into a heading, that line is not a break.
        var underline: ASTNode? = null
        for (child in fileNode.getChildren(null)) {
            val start = offset
            offset += child.textLength
            // Everything that starts inside the front matter is front matter.
            if (start < skipBefore) continue
            val type = child.elementType
            when {
                child === underline && type == MarkdownTokenTypes.HORIZONTAL_RULE -> Unit
                type == MarkdownElementTypes.PARAGRAPH && setextUnderline(child) != null -> {
                    underline = setextUnderline(child)
                    out += MarpBlock.Heading(start, 2, paragraphText(child))
                    collectNested(child, start, out)
                }
                type == MarkdownTokenTypes.HORIZONTAL_RULE -> out += MarpBlock.Break(start)
                type in HEADER_TYPES -> {
                    headerBlocks(child, start, out, ruleIsUnderline = child === underline)
                    collectNested(child, start, out)
                }
                type == MarkdownElementTypes.HTML_BLOCK && isComment(child) -> out += MarpBlock.Comment(start, child.text)
                // Marpit turns a block-level <style> into a hidden token, it is not visible content.
                type == MarkdownElementTypes.HTML_BLOCK && isStyle(child) -> Unit
                type in INVISIBLE_TYPES -> Unit
                else -> {
                    out += MarpBlock.Content(start)
                    collectNested(child, start, out)
                }
            }
        }
    }

    /** Headings (Marpit divides on those at any depth) and comments (they can hold directives) below a block. */
    private fun collectNested(node: ASTNode, nodeStart: Int, out: MutableList<MarpBlock>) {
        var offset = nodeStart
        for (child in node.getChildren(null)) {
            val start = offset
            offset += child.textLength
            val type = child.elementType
            when {
                type in HEADER_TYPES -> {
                    heading(child, start)?.let { out += it }
                    collectNested(child, start, out)
                }
                (type == MarkdownElementTypes.HTML_BLOCK || type == MarkdownTokenTypes.HTML_TAG) && isComment(child) ->
                    out += MarpBlock.Comment(start, child.text)
                else -> collectNested(child, start, out)
            }
        }
    }

    /** What markdown-it makes of the single content line of a setext node, when that is not a setext heading. */
    private sealed interface Misread {
        /** A thematic break (`---`, `***`, `_ _ _`) above the underline: markdown-it reads a break, then the underline itself. */
        data object Rule : Misread

        /** An ATX heading (`# Title`) above the underline: markdown-it reads that heading, then the underline itself. */
        data class Atx(val level: Int) : Misread

        /**
         * A one-line HTML comment or `<style>` element above the underline: an HTML block for markdown-it, hidden in the
         * slide, then the underline itself. Comments in it are still found by [collectNested].
         */
        data object Hidden : Misread
    }

    private val THEMATIC_BREAK = Regex("^ {0,3}([-*_])(?:[ \\t]*\\1){2,}[ \\t]*$")
    private val ATX_OPENING = Regex("^ {0,3}(#{1,6})(?:[ \\t]|$)")
    private val ATX_LEADING_MARKER = Regex("^\\s*#{1,6}")
    private val ATX_CLOSING_MARKER = Regex("(?:^|[ \\t])#+[ \\t]*$")

    /** An HTML block that starts and ends on the line: `<!-- ... -->` (type 2) or `<style ...>...</style>` (type 1). */
    private val HIDDEN_HTML = Regex("^ {0,3}(?:<!--.*-->|<style(?=[\\s>]|$).*</style>)", RegexOption.IGNORE_CASE)

    /** `null` for an ATX heading and for a real setext heading: one whose content is a paragraph, or several lines. */
    private fun misread(setext: ASTNode): Misread? {
        val line = setext.findChildByType(MarkdownTokenTypes.SETEXT_CONTENT)?.text ?: return null
        if ('\n' in line) return null
        if (HIDDEN_HTML.containsMatchIn(line)) return Misread.Hidden
        if (THEMATIC_BREAK.matches(line)) return Misread.Rule
        return ATX_OPENING.find(line)?.let { Misread.Atx(it.groupValues[1].length) }
    }

    /**
     * The blocks of a top-level heading node. A real heading is one block. A misread setext node is split as markdown-it
     * reads it: a break, an ATX heading or nothing (a hidden HTML block) first, then the underline. `---` is a thematic
     * break of its own, a slide separator, `===` is a paragraph. [ruleIsUnderline]: the rule line of the node underlines the
     * paragraph before it, so it is not a break.
     */
    private fun headerBlocks(node: ASTNode, start: Int, out: MutableList<MarpBlock>, ruleIsUnderline: Boolean) {
        val misread = misread(node)
        val heading = heading(node, start, misread)
        if (heading != null) out += heading else if (misread is Misread.Rule && !ruleIsUnderline) out += MarpBlock.Break(start)
        if (misread == null) return
        val equals = node.elementType == MarkdownElementTypes.SETEXT_1
        val underline = node.findChildByType(if (equals) MarkdownTokenTypes.SETEXT_1 else MarkdownTokenTypes.SETEXT_2) ?: return
        // The underline token starts after its indentation, a block starts at the line.
        val underlineStart = start + node.text.lastIndexOf('\n', underline.startOffsetInParent) + 1
        out += if (equals) MarpBlock.Content(underlineStart) else MarpBlock.Break(underlineStart)
    }

    /** The heading of [node] with the level and text markdown-it gives it, `null` when it is a break or hidden HTML instead. */
    private fun heading(node: ASTNode, start: Int, misread: Misread? = misread(node)): MarpBlock.Heading? = when (misread) {
        null -> MarpBlock.Heading(start, (node.psi as MarkdownHeader).level, headingText(node))
        Misread.Rule, Misread.Hidden -> null
        is Misread.Atx -> MarpBlock.Heading(start, misread.level, atxText(headingText(node)))
    }

    /** Drops the `#` markers (opening, and the optional closing sequence) that a setext node keeps in its content text. */
    private fun atxText(text: String): String =
        text.replaceFirst(ATX_LEADING_MARKER, "").replace(ATX_CLOSING_MARKER, "")

    /** `-` characters only, up to three spaces of indentation: not `- - -` or `-- -` (thematic breaks), `***` or `___`. */
    private val SETEXT_UNDERLINE = Regex("^ {0,3}-+[ \\t]*$")

    /**
     * The node with the `---` line that makes the top-level [paragraph] a setext heading for markdown-it, `null` if there
     * is none. The plugin builds a setext node from a one-line paragraph only and leaves a longer one as a paragraph
     * followed by a thematic break; when another `---` follows, it reads that one as the content of a setext node
     * ([Misread.Rule]) instead. The underline must be on the very next line: one line break between the paragraph and
     * it (a blank line makes two). Lazy lines of quotes and lists are inside those blocks in the PSI, so a top-level
     * paragraph is one for markdown-it too.
     */
    private fun setextUnderline(paragraph: ASTNode): ASTNode? {
        var next = paragraph.treeNext
        var lineBreaks = 0
        while (next != null && (next.elementType == TokenType.WHITE_SPACE || next.elementType == MarkdownTokenTypes.EOL)) {
            lineBreaks += next.text.count { it == '\n' }
            next = next.treeNext
        }
        if (next == null || lineBreaks != 1) return null
        val line = when (next.elementType) {
            MarkdownTokenTypes.HORIZONTAL_RULE -> next.text
            MarkdownElementTypes.SETEXT_1, MarkdownElementTypes.SETEXT_2 ->
                if (misread(next) == Misread.Rule) next.findChildByType(MarkdownTokenTypes.SETEXT_CONTENT)?.text else null
            else -> null
        }
        return next.takeIf { line != null && SETEXT_UNDERLINE.matches(line) }
    }

    /** The text of a paragraph without inline markup, like [headingText]. The lines are joined by the whitespace collapse of the splitter. */
    private fun paragraphText(paragraph: ASTNode): String {
        val text = StringBuilder()
        for (child in paragraph.getChildren(null)) appendInlineText(child, text)
        return text.toString()
    }

    private fun isComment(node: ASTNode): Boolean = node.text.trimStart().startsWith("<!--")

    /** Marpit's `marpit_style_parse` opening: `<style` followed by white space, `>` or the end of the line. */
    private val STYLE_OPENING = Regex("^<style(?=[\\s>]|$)", RegexOption.IGNORE_CASE)

    private fun isStyle(node: ASTNode): Boolean = STYLE_OPENING.containsMatchIn(node.text.trimStart())

    private val LINK_TYPES: Set<IElementType> = setOf(
        MarkdownElementTypes.INLINE_LINK, MarkdownElementTypes.FULL_REFERENCE_LINK, MarkdownElementTypes.SHORT_REFERENCE_LINK,
    )

    /** Markup characters that are not part of the text: `*` / `_` of emphasis, backticks, `~~`. */
    private val MARKUP_TOKENS: Set<IElementType> = setOf(
        MarkdownTokenTypes.EMPH, MarkdownTokenTypes.BACKTICK, MarkdownTokenTypes.TILDE,
    )

    /**
     * The text of a heading without inline markup, from the token types of its content: emphasis, code and
     * strikethrough markers, inline HTML (including comments such as `# <!-- fit --> Title`) and images are left out and
     * a link is reduced to its text. Text that only looks like markup, such as `snake_case`, is plain text for the parser and stays.
     */
    private fun headingText(header: ASTNode): String {
        val content = header.getChildren(null).firstOrNull {
            it.elementType == MarkdownTokenTypes.ATX_CONTENT || it.elementType == MarkdownTokenTypes.SETEXT_CONTENT
        } ?: return ""
        val text = StringBuilder()
        for (child in content.getChildren(null)) appendInlineText(child, text)
        return text.toString()
    }

    private fun appendInlineText(node: ASTNode, text: StringBuilder) {
        val type = node.elementType
        when {
            type in MARKUP_TOKENS || type == MarkdownElementTypes.IMAGE || type == MarkdownTokenTypes.HTML_TAG -> Unit
            type in LINK_TYPES -> {
                for (part in node.getChildren(null)) {
                    if (part.elementType != MarkdownElementTypes.LINK_TEXT) continue
                    for (inner in part.getChildren(null)) {
                        if (inner.elementType != MarkdownTokenTypes.LBRACKET && inner.elementType != MarkdownTokenTypes.RBRACKET) {
                            appendInlineText(inner, text)
                        }
                    }
                }
            }
            node.firstChildNode == null -> text.append(node.text)
            else -> for (child in node.getChildren(null)) appendInlineText(child, text)
        }
    }
}
