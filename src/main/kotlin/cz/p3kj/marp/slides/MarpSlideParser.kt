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
        for (child in fileNode.getChildren(null)) {
            val start = offset
            offset += child.textLength
            // Everything that starts inside the front matter is front matter.
            if (start < skipBefore) continue
            val type = child.elementType
            when {
                type == MarkdownTokenTypes.HORIZONTAL_RULE -> out += MarpBlock.Break(start)
                type in HEADER_TYPES -> {
                    out += heading(child, start)
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
                    out += heading(child, start)
                    collectNested(child, start, out)
                }
                (type == MarkdownElementTypes.HTML_BLOCK || type == MarkdownTokenTypes.HTML_TAG) && isComment(child) ->
                    out += MarpBlock.Comment(start, child.text)
                else -> collectNested(child, start, out)
            }
        }
    }

    private fun heading(node: ASTNode, start: Int): MarpBlock.Heading {
        val header = node.psi as MarkdownHeader
        return MarpBlock.Heading(start, header.level, headingText(node))
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
