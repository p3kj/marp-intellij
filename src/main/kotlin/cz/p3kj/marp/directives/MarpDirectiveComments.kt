package cz.p3kj.marp.directives

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

/** One `key: value` line of a directive comment. Ranges are offsets into the parsed comment text. */
data class MarpDirectiveEntry(
    val key: String,
    val keyRange: TextRange,
    /** The value as written, without a trailing ` # comment`, quotes kept. */
    val rawValue: String,
    /** [rawValue] without its quotes. */
    val value: String,
    /** Where [rawValue] is in the text, `null` when the value is empty (no text after the colon). */
    val valueRange: TextRange?,
) {
    val resolved: MarpDirectiveKey get() = MarpDirectiveCatalog.resolve(key)
}

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
 * The first half of this object works on text and is independent of the IDE, the second half finds comments in the
 * Markdown PSI. Comments are only interpreted in Marp decks ([isMarpDeck]).
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
     * neither a key line nor a continuation means the body is prose.
     */
    private fun readEntries(text: CharSequence, bodyStart: Int, bodyEnd: Int): Pair<List<MarpDirectiveEntry>, Boolean> {
        val entries = ArrayList<MarpDirectiveEntry>()
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
                line[0] == ' ' || line[0] == '\t' || isSequenceItem(line) -> if (entries.isEmpty()) return entries to false
                else -> {
                    val entry = readKeyLine(line, lineStart) ?: return entries to false
                    entries += entry
                }
            }
            lineStart = lineEnd + 1
        }
        return entries to entries.isNotEmpty()
    }

    private fun isSequenceItem(line: String): Boolean = line[0] == '-' && (line.length == 1 || line[1] == ' ' || line[1] == '\t')

    private fun readKeyLine(line: String, lineOffset: Int): MarpDirectiveEntry? {
        val match = KEY_LINE.find(line) ?: return null
        val keyGroup = match.groups[2]!!
        val key = keyGroup.value
        val afterColon = match.range.last + 1
        var valueStart = afterColon
        while (valueStart < line.length && (line[valueStart] == ' ' || line[valueStart] == '\t')) valueStart++
        val rawValue = rawValueOf(line.substring(valueStart))
        val keyRange = TextRange(lineOffset + keyGroup.range.first, lineOffset + keyGroup.range.last + 1)
        if (rawValue.isEmpty()) return MarpDirectiveEntry(key, keyRange, "", "", null)
        val valueRange = TextRange(lineOffset + valueStart, lineOffset + valueStart + rawValue.length)
        return MarpDirectiveEntry(key, keyRange, rawValue, MarpHeadingDivider.unquote(rawValue), valueRange)
    }

    /** The value up to the end of a closing quote, or up to a ` # comment`, without trailing white space. */
    private fun rawValueOf(rest: String): String {
        val text = rest.trimEnd()
        if (text.isEmpty()) return ""
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
        val prefix = text.substring(lineStart, caret).trimStart()
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

    /** The comment element that contains [offset] (the caret may sit right after the comment), or `null`. */
    fun commentElementAt(markdown: MarkdownFile, offset: Int): PsiElement? {
        for (candidate in intArrayOf(offset, offset - 1)) {
            if (candidate < 0) continue
            var element: PsiElement? = markdown.findElementAt(candidate)
            while (element != null && element !is PsiFile) {
                if (isCommentElement(element)) return element
                element = element.parent
            }
        }
        return null
    }

    /** `<!-- fit -->` inside a heading: marp-core's fitting header, neither a directive nor a presenter note. */
    fun isFitComment(element: PsiElement, comment: MarpParsedComment): Boolean =
        comment.body == "fit" && PsiTreeUtil.getParentOfType(element, MarkdownHeader::class.java) != null
}
