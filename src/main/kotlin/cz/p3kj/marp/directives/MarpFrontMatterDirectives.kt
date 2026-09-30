package cz.p3kj.marp.directives

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import cz.p3kj.marp.MarpDetector

/**
 * The front matter of a deck read as directives. [bodyRange] is the body between the fences and [entries] its
 * `key: value` lines, all as offsets into the document text. [isMapping] is false when the body is not a YAML mapping
 * (a list, prose, a repeated key, ...), in which case Marp ignores it too.
 */
data class MarpParsedFrontMatter(val bodyRange: TextRange, val entries: List<MarpDirectiveEntry>, val isMapping: Boolean)

/**
 * Directive support for the front matter of Marp decks. It reuses the directive comment code: the catalog, the line
 * parser, the value checks and the documentation. What differs is where the text is found and a few rules, see below.
 *
 * The front matter is located in the text of the document by [MarpDetector.findFrontMatter], the rule that decides
 * whether a file is a deck at all (and that the slide model uses), not through the PSI. That works with and without the
 * YAML injection of the Markdown plugin, and it agrees with Marp where the Markdown plugin is stricter: the plugin needs
 * an opening line of exactly dashes and a closing line of exactly dashes or dots, Marp for VS Code accepts trailing
 * white space after the opening fence and a closing fence that is indented or followed by text.
 *
 * Differences from a directive comment: `marp` is a known key ([MarpDirectiveCatalog.MARP]), `size` and `math` take the
 * whole rest of the line ([MarpDirectiveComments.readEntries]), and anything nested (indented lines, lists) is left to YAML.
 *
 * With the YAML plugin the Markdown plugin injects YAML into the front matter, and completion, hover documentation
 * and completion confidence are then asked about the injected file and its offsets. [hostOf] maps those back to the
 * Markdown file, so that everything else works on the document text.
 */
object MarpFrontMatter {

    /** The front matter of [text] with offsets into [text], `null` when it has none. */
    fun parse(text: CharSequence): MarpParsedFrontMatter? {
        val frontMatter = MarpDetector.findFrontMatter(text) ?: return null
        val start = frontMatter.bodyStart
        val end = start + frontMatter.body.length
        val (entries, isMapping) = MarpDirectiveComments.readEntries(text, start, end, frontMatter = true)
        return MarpParsedFrontMatter(TextRange(start, end), entries, isMapping)
    }

    /**
     * What can be completed at [caret] in the front matter of [text]: a key (`pa`) or the value of a key
     * (`paginate: h`). `null` outside the front matter (the fence lines are outside), on a nested line (indented, or a
     * list item), and where the text before the caret is neither. Only the line up to the caret counts.
     */
    fun completionSpot(text: CharSequence, caret: Int): MarpCompletionSpot? {
        val frontMatter = MarpDetector.findFrontMatter(text) ?: return null
        val start = frontMatter.bodyStart
        val bodyEnd = start + frontMatter.body.length
        // Blank lines right before the closing fence are not part of the body, but they are inside the front matter.
        var limit = bodyEnd
        while (limit < text.length && text[limit].isWhitespace()) limit++
        if (caret < start || caret >= limit || caret > text.length) return null
        var lineStart = caret
        while (lineStart > start && text[lineStart - 1] != '\n' && text[lineStart - 1] != '\r') lineStart--
        val prefix = text.substring(lineStart, caret)
        if (prefix.isNotEmpty() && (prefix[0] == ' ' || prefix[0] == '\t' || prefix[0] == '-')) return null
        return MarpDirectiveComments.spotInLine(prefix)
    }

    /**
     * The keys of the unindented lines of the front matter of [text], except the line with [caret]. A key that is already
     * there is not offered again: a repeated key makes the YAML parser of Marp reject the whole front matter.
     */
    fun keysOutsideLine(text: CharSequence, caret: Int): Set<String> {
        val frontMatter = MarpDetector.findFrontMatter(text) ?: return emptySet()
        val end = frontMatter.bodyStart + frontMatter.body.length
        val keys = LinkedHashSet<String>()
        var lineStart = frontMatter.bodyStart
        while (lineStart < end) {
            var lineEnd = lineStart
            while (lineEnd < end && text[lineEnd] != '\n') lineEnd++
            if (caret !in lineStart..lineEnd) {
                val line = text.substring(lineStart, lineEnd)
                if (line.isNotEmpty() && line[0] != ' ' && line[0] != '\t') MarpDirectiveComments.keyOfLine(line)?.let { keys += it }
            }
            lineStart = lineEnd + 1
        }
        return keys
    }

    /**
     * The Markdown file and offset that [offset] of [file] stands for: [file] itself when it is the Markdown file, and
     * for an injected file (the YAML of the front matter) its host and the matching host offset.
     */
    fun hostOf(file: PsiFile, offset: Int): Pair<PsiFile, Int> {
        val manager = InjectedLanguageManager.getInstance(file.project)
        val host = manager.getTopLevelFile(file)
        if (host === file) return file to offset
        return host to manager.injectedToHost(file, offset)
    }

    /** The text of the Markdown file of [file], `null` when [file] has no Markdown tree. */
    fun text(file: PsiFile): CharSequence? = MarpDirectiveComments.markdownFile(file)?.viewProvider?.contents
}
