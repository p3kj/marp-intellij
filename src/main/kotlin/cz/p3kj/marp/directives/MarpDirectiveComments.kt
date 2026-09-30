package cz.p3kj.marp.directives

import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import cz.p3kj.marp.MarpDetector
import cz.p3kj.marp.slides.MarpHeadingDivider
import org.intellij.plugins.markdown.lang.MarkdownElementTypes
import org.intellij.plugins.markdown.lang.MarkdownTokenTypes
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownFile
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownHeader

/** One `key: value` line of a directive comment or of the front matter. Ranges are offsets into the parsed text. */
data class MarpDirectiveEntry(
    val key: String,
    val keyRange: TextRange,
    /**
     * The value as written, quotes kept. Without a trailing ` # comment`, except for the Marpit directives: their
     * value is taken up to the end of the line, see [MarpDirectiveComments].
     */
    val rawValue: String,
    /** [rawValue] without its quotes. */
    val value: String,
    /** Where [rawValue] is in the text, `null` when the value is empty (no text after the colon). */
    val valueRange: TextRange?,
    /** What [key] means, resolved once when the entry is created (the front matter also knows `marp`). */
    val resolved: MarpDirectiveKey = MarpDirectiveCatalog.resolve(key),
)

/**
 * A parsed HTML comment. [range] covers `<!-- ... -->`, [bodyRange] and [body] the text between the markers without
 * the surrounding white space and extra dashes (what Marpit feeds to its YAML parser). [entries] are the `key: value`
 * lines when the body has the shape of a YAML mapping ([isMapping]).
 */
data class MarpParsedComment(
    val range: TextRange,
    val bodyRange: TextRange,
    val body: String,
    val entries: List<MarpDirectiveEntry>,
    val isMapping: Boolean,
) {
    /** Marp treats the comment as directives: a mapping with at least one recognised key. Anything else is a note. */
    val isDirective: Boolean
        get() = isMapping && entries.any { it.resolved is MarpDirectiveKey.Known }

    /** Written as directives, even when Marp ignores it (`_theme: gaia`): what the highlighting and hints care about. */
    val looksLikeDirective: Boolean
        get() = isMapping && entries.any { it.resolved.let { key -> key is MarpDirectiveKey.Known || key is MarpDirectiveKey.GlobalWithUnderscore } }

    /** Comments of other tools that Marpit passes over (prettier, markdownlint, remark-lint): neither directive nor note. */
    val isMagic: Boolean get() = MAGIC.any { it.matches(body) }

    private companion object {
        val MAGIC = listOf(
            Regex("prettier-ignore(-(start|end))?"),
            Regex("markdownlint-((disable|enable).*|capture|restore)"),
            Regex("lint (disable|enable|ignore).*"),
        )
    }
}

/** What can be completed at the caret of a directive comment. [prefix] is the text typed so far. */
sealed interface MarpCompletionSpot {
    data class Key(val prefix: String) : MarpCompletionSpot
    data class Value(val key: String, val prefix: String) : MarpCompletionSpot
}

/**
 * Finds and reads directive comments. The parser is a line scanner for the `key: value` shape of a directive comment,
 * not a YAML parser: it follows what Marpit does with the comment body (Marpit's regular expression
 * `<!--+\s*([\s\S]*?)\s*--+>`, then YAML) closely enough to tell directives from presenter notes. Flow mappings
 * (`{ class: lead }`) and other exotic YAML are read as notes.
 *
 * marp-core parses with Marpit's loose YAML: for the Marpit directives (with or without `_`) a value that does not start
 * with one of ``["'{|>~&*`` is quoted as a whole, so `paginate: true # c` has the value `true # c` (which is not
 * `true`). Every other key follows plain YAML, where ` #` starts a comment. A body that YAML rejects makes Marp read
 * the whole comment as a note: a value that starts with `*` (an alias, as in `header: **Bold**`), a repeated key, and
 * a line that continues a value that is already complete.
 *
 * The first half of this object works on text and is independent of the IDE, the second half finds comments in the
 * Markdown PSI. Comments are only interpreted in Marp decks ([isMarpDeck]). The line reader ([readEntries]) and the
 * completion spot of a line ([spotInLine]) are shared with the front matter, see [MarpFrontMatter].
 */
object MarpDirectiveComments {

    private const val OPEN = "<!--"
    private const val CLOSE = "-->"

    /** A `key:` at the start of a line: optionally quoted key, then a colon followed by white space or the line end. */
    private val KEY_LINE = Regex("^([\"']?)([^\\s\"':#][^:\"']*?)\\1[ \\t]*:(?=[ \\t]|$)")

    private val KEY_PREFIX = Regex("^_?[\\w-]*$")
    private val VALUE_PREFIX = Regex("^([\"']?)([^\\s\"':#][^:\"']*?)\\1[ \\t]*:[ \\t]+([^\\s\"']*)$")

    // Text part -------------------------------------------------------------------------------------------------------

    /** Reads the first HTML comment in [text], `null` when there is no complete `<!-- ... -->`. */
    fun parse(text: CharSequence): MarpParsedComment? {
        val open = text.indexOf(OPEN)
        if (open < 0) return null
        val close = text.indexOf(CLOSE, open + OPEN.length)
        if (close < 0) return null
        val (bodyStart, bodyEnd) = bodyBounds(text, open, close)
        val body = text.substring(bodyStart, bodyEnd)
        val range = TextRange(open, close + CLOSE.length)
        val (entries, isMapping) = readEntries(text, bodyStart, bodyEnd)
        return MarpParsedComment(range, TextRange(bodyStart, bodyEnd), body, entries, isMapping)
    }

    /** Marpit's `<!--+\s*(body)\s*--+>`: the body starts after the dashes and white space and ends before white space and dashes. */
    private fun bodyBounds(text: CharSequence, open: Int, close: Int): Pair<Int, Int> {
        var start = open + 2
        while (start < close && text[start] == '-') start++
        while (start < close && text[start].isWhitespace()) start++
        var end = close
        while (end > start && text[end - 1] == '-') end--
        while (end > start && text[end - 1].isWhitespace()) end--
        return start to end
    }

    /**
     * The `key: value` lines of the body and whether the whole body is a mapping. Blank and `#` lines are skipped;
     * indented lines and `- item` lines continue the previous entry (a block scalar or sequence); a line that is
     * neither a key line nor a continuation means the body is prose. So does what YAML rejects: a value that starts
     * with `*`, a repeated key, and an indented line after a value that is already complete (see the class comment).
     * The ranges are offsets into [text], so the body of the front matter of a document gives document offsets.
     *
     * With [frontMatter] the body is the front matter of a deck, where `marp` is a known key. Values are read the same
     * way as in a comment (loose YAML for the Marpit directives only, see [isLooseKey]).
     */
    internal fun readEntries(
        text: CharSequence, bodyStart: Int, bodyEnd: Int, frontMatter: Boolean = false,
    ): Pair<List<MarpDirectiveEntry>, Boolean> {
        val entries = ArrayList<MarpDirectiveEntry>()
        val keys = HashSet<String>()
        var lineStart = bodyStart
        while (lineStart <= bodyEnd) {
            var lineEnd = lineStart
            while (lineEnd < bodyEnd && text[lineEnd] != '\n') lineEnd++
            // The line without its `\r`.
            val contentEnd = if (lineEnd > lineStart && text[lineEnd - 1] == '\r') lineEnd - 1 else lineEnd
            val line = text.substring(lineStart, contentEnd)
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() || trimmed.startsWith("#") -> Unit
                isSequenceItem(trimmed) -> if (entries.isEmpty()) return entries to false
                line[0] == ' ' || line[0] == '\t' -> {
                    val previous = entries.lastOrNull() ?: return entries to false
                    if (isCompleteScalar(previous)) return entries to false
                }
                else -> {
                    val entry = readKeyLine(line, lineStart, frontMatter) ?: return entries to false
                    if (entry.rawValue.startsWith("*") || !keys.add(entry.key)) return entries to false
                    entries += entry
                }
            }
            lineStart = lineEnd + 1
        }
        return entries to entries.isNotEmpty()
    }

    private fun isSequenceItem(trimmed: String): Boolean = trimmed[0] == '-' && (trimmed.length == 1 || trimmed[1] == ' ' || trimmed[1] == '\t')

    /**
     * Whether the value of [entry] is a scalar that ends on its own line, so that an indented line after it is a YAML
     * error: a closed quoted scalar, or a value of a Marpit directive that loose YAML quotes as a whole. Block scalars,
     * flow collections, an unclosed quote and plain scalars of other keys can continue on the next lines.
     */
    private fun isCompleteScalar(entry: MarpDirectiveEntry): Boolean {
        val raw = entry.rawValue
        if (raw.isEmpty()) return false
        val first = raw[0]
        if (isLooseKey(entry.key) && first !in MarpHeadingDivider.YAML_SPECIAL_START) return true
        return (first == '"' || first == '\'') && raw.length >= 2 && raw.last() == first
    }

    /**
     * The directives (`_` form included) that are parsed with loose YAML: the Marpit directives, in comments and in the
     * front matter alike. marp-core's own `size` and `math` are plain YAML (its custom directive lists are empty at
     * runtime), and so are `marp` and unknown keys.
     */
    private fun isLooseKey(key: String): Boolean =
        MarpDirectiveCatalog.find(key.removePrefix("_"))?.origin == MarpDirectiveOrigin.MARPIT

    private fun readKeyLine(line: String, lineOffset: Int, frontMatter: Boolean): MarpDirectiveEntry? {
        val match = KEY_LINE.find(line) ?: return null
        val keyGroup = match.groups[2]!!
        val key = keyGroup.value
        val afterColon = match.range.last + 1
        var valueStart = afterColon
        while (valueStart < line.length && (line[valueStart] == ' ' || line[valueStart] == '\t')) valueStart++
        val rawValue = rawValueOf(line.substring(valueStart), isLooseKey(key))
        val keyRange = TextRange(lineOffset + keyGroup.range.first, lineOffset + keyGroup.range.last + 1)
        val resolved = if (frontMatter) MarpDirectiveCatalog.resolveInFrontMatter(key) else MarpDirectiveCatalog.resolve(key)
        if (rawValue.isEmpty()) return MarpDirectiveEntry(key, keyRange, "", "", null, resolved)
        val valueRange = TextRange(lineOffset + valueStart, lineOffset + valueStart + rawValue.length)
        return MarpDirectiveEntry(key, keyRange, rawValue, MarpHeadingDivider.unquote(rawValue), valueRange, resolved)
    }

    /** The key of a `key: value` line ([KEY_LINE]), quotes removed, or `null` when [line] is not a key line. */
    internal fun keyOfLine(line: String): String? = KEY_LINE.find(line)?.groups?.get(2)?.value

    /**
     * The value at the end of a key line, without trailing white space. With [loose] (a Marpit directive) a value that
     * does not start with a YAML special character is the whole rest of the line, ` # comment` included. Otherwise it
     * ends after a closing quote, or before a ` # comment`.
     */
    private fun rawValueOf(rest: String, loose: Boolean): String {
        val text = rest.trimEnd()
        if (text.isEmpty()) return ""
        if (loose && text[0] !in MarpHeadingDivider.YAML_SPECIAL_START) return text
        val quote = text[0]
        if (quote == '"' || quote == '\'') {
            val end = text.indexOf(quote, 1)
            if (end > 0) return text.substring(0, end + 1)
        }
        return MarpHeadingDivider.withoutYamlComment(text)
    }

    /**
     * What can be completed at [caret], an offset into the comment [text]: a directive name (`_pa`) or the value of a
     * directive (`paginate: h`). `null` outside the body of the comment, or when the text before the caret is neither
     * (a sentence of a presenter note). Only the line up to the caret counts.
     */
    fun completionSpot(text: CharSequence, caret: Int): MarpCompletionSpot? {
        val open = text.indexOf(OPEN)
        if (open < 0 || caret < open + OPEN.length || caret > text.length) return null
        val close = text.indexOf(CLOSE, open + OPEN.length)
        if (close in 0 until caret) return null
        // A comment that is still being typed has no closing marker yet, its body runs to the end.
        val bodyLimit = if (close >= 0) close else text.length
        var afterMarker = open + OPEN.length
        while (afterMarker < bodyLimit && text[afterMarker] == '-') afterMarker++
        if (caret < afterMarker) return null
        var lineStart = caret
        while (lineStart > afterMarker && text[lineStart - 1] != '\n') lineStart--
        return spotInLine(text.substring(lineStart, caret).trimStart())
    }

    /**
     * The completion spot at the end of [prefix], the text of a line from its first non-blank character to the caret:
     * a key (`_pa`), the value of a key (`paginate: h`), or `null` for anything else.
     */
    internal fun spotInLine(prefix: String): MarpCompletionSpot? {
        if (KEY_PREFIX.matches(prefix)) return MarpCompletionSpot.Key(prefix)
        val value = VALUE_PREFIX.matchEntire(prefix) ?: return null
        return MarpCompletionSpot.Value(value.groupValues[2], value.groupValues[3])
    }

    // PSI part --------------------------------------------------------------------------------------------------------

    /** The Markdown tree of [file]: the file itself, or the Markdown side of a multi-language view provider. */
    fun markdownFile(file: PsiFile): MarkdownFile? = file.viewProvider.allFiles.firstOrNull { it is MarkdownFile } as? MarkdownFile

    /** `true` when [file] is Markdown with `marp: true` in its front matter. Cached until the file changes. */
    fun isMarpDeck(file: PsiFile): Boolean {
        val markdown = markdownFile(file) ?: return false
        return CachedValuesManager.getCachedValue(markdown) {
            CachedValueProvider.Result.create(MarpDetector.isMarp(markdown.viewProvider.contents), markdown)
        }
    }

    /** An HTML block or inline HTML that starts with `<!--`, the same rule the slide parser uses. */
    fun isCommentElement(element: PsiElement): Boolean {
        val type = element.node?.elementType
        if (type != MarkdownElementTypes.HTML_BLOCK && type != MarkdownTokenTypes.HTML_TAG) return false
        return element.text.trimStart().startsWith(OPEN)
    }

    /**
     * The comment element that contains [offset] (the caret may sit right after the comment), or `null`. Walks the
     * syntax tree of the Markdown file itself: `PsiFile.findElementAt` would return the HTML leaf of the second tree
     * that the IDE builds for the HTML in a Markdown file.
     */
    fun commentElementAt(markdown: MarkdownFile, offset: Int): PsiElement? {
        for (candidate in intArrayOf(offset, offset - 1)) {
            if (candidate < 0) continue
            var node: ASTNode? = markdown.node.findLeafElementAt(candidate)
            while (node != null && node.psi !is PsiFile) {
                val element = node.psi
                if (isCommentElement(element)) return element
                node = node.treeParent
            }
        }
        return null
    }

    /**
     * The directive entries of the front matter or the directive comment that contains [offset] of [file], and the
     * offset in the coordinates of those entries (a document offset for the front matter, an offset into the comment
     * text for a comment). Only what Marp reads as directives: `null` when [file] is not a Marp deck, the offset is in
     * neither, or the front matter or comment is not a YAML mapping (a note) or is a magic comment. With the YAML
     * plugin the front matter is an injected file: [offset] may belong to it, it is mapped to the Markdown file first
     * ([MarpFrontMatter.hostOf]). The end of the front matter body counts as inside.
     */
    fun entriesAt(file: PsiFile, offset: Int): Pair<List<MarpDirectiveEntry>, Int>? {
        val (host, hostOffset) = MarpFrontMatter.hostOf(file, offset)
        if (!isMarpDeck(host)) return null
        val text = MarpFrontMatter.text(host) ?: return null
        val frontMatter = MarpFrontMatter.parse(text)
        if (frontMatter != null && hostOffset >= frontMatter.bodyRange.startOffset && hostOffset <= frontMatter.bodyRange.endOffset) {
            return if (frontMatter.isMapping) frontMatter.entries to hostOffset else null
        }
        val markdown = markdownFile(host) ?: return null
        val element = commentElementAt(markdown, hostOffset) ?: return null
        val comment = parse(element.text)?.takeIf { it.isMapping && !it.isMagic } ?: return null
        return comment.entries to hostOffset - element.textRange.startOffset
    }

    /** `<!-- fit -->` inside a heading: marp-core's fitting header, neither a directive nor a presenter note. */
    fun isFitComment(element: PsiElement, comment: MarpParsedComment): Boolean =
        comment.body == "fit" && PsiTreeUtil.getParentOfType(element, MarkdownHeader::class.java) != null
}
