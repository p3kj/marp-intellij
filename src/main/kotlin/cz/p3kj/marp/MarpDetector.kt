package cz.p3kj.marp

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.vfs.VirtualFile
import org.intellij.plugins.markdown.lang.MarkdownFileType
import java.io.IOException
import java.nio.charset.Charset
import java.util.regex.Pattern

/**
 * Detects Marp decks: Markdown whose front matter, starting at the very first character, contains `marp: true`.
 *
 * The rules are a port of marp-vscode's `detectMarpFromMarkdown` (`src/utils.ts`,
 * https://github.com/marp-team/marp-vscode, MIT License, Copyright (c) 2019- Marp team (marp-team@marp.app)):
 * the front matter is `/^(-{3,}\s*$\n)([\s\S]*?)^(\s*[-.]{3})/m` and the directive `/^(marp\s*: +)(.*)\s*$/m`, with
 * JavaScript's `\s`, line terminators and multiline `^` / `$`. The front matter pattern is matched by a hand-written
 * scanner ([findFrontMatter]) that finds exactly what the regular expression finds, in linear time: run by a
 * backtracking engine, that expression needs seconds for a file starting with `---` and a thousand blank lines, and
 * detection runs inside read actions. The directive is a regular expression again, translated so that Java's engine
 * matches the same strings (JavaScript `\s` spelled out, `^` / `$` as look-arounds over JavaScript's line terminators).
 *
 * Only the first [FRONT_MATTER_SCAN_CHARS] characters are looked at: the front matter has to end within them, which
 * keeps every keystroke cheap however large the document is.
 *
 * Consequences of following marp-vscode exactly: the value must be the literal `true` up to the end of the line, so
 * `marp: true # comment`, `marp: "true"` and trailing whitespace after `true` are not detected.
 * A leading byte order mark is ignored, like in the editors (neither VS Code nor IntelliJ documents contain it).
 */
object MarpDetector {

    /**
     * How much of a document is searched for the front matter, in characters (after a byte order mark). Files that are
     * not loaded into a document are read from disk up to this many bytes, which never decode to more characters.
     */
    const val FRONT_MATTER_SCAN_CHARS: Int = 64 * 1024

    private const val BOM = '\uFEFF'

    /** JavaScript `\s`: WhiteSpace and LineTerminator code points. */
    private const val JS_WS = "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"

    /** JavaScript line terminators. */
    private const val JS_LT = "\\n\\r\\u2028\\u2029"

    /** JavaScript multiline `^`. */
    private const val LINE_START = "(?<![^$JS_LT])"

    /** JavaScript multiline `$`. */
    private const val LINE_END = "(?![^$JS_LT])"

    /** marp-vscode: `/^(marp\s*: +)(.*)\s*$/m` */
    private val MARP_DIRECTIVE: Pattern =
        Pattern.compile("$LINE_START(marp$JS_WS*: +)([^$JS_LT]*)$JS_WS*$LINE_END")

    /**
     * The front matter of a document: [body] is group 2 of marp-vscode's front matter expression, [endOffset] the offset
     * in the original text just past the line of the closing fence (after its line break, or the text length at the end).
     */
    data class FrontMatter(val body: String, val endOffset: Int)

    /** The front matter body, see [findFrontMatter]. */
    fun detectFrontMatter(markdown: CharSequence): String? = findFrontMatter(markdown)?.body

    /**
     * The front matter (group 2 of marp-vscode's `/^(-{3,}\s*$\n)([\s\S]*?)^(\s*[-.]{3})/m`, and where it ends) if
     * [markdown] starts with front matter that ends within [FRONT_MATTER_SCAN_CHARS] characters, otherwise `null`.
     * Linear time.
     */
    fun findFrontMatter(markdown: CharSequence): FrontMatter? {
        val text = head(markdown)
        val length = text.length
        // A byte order mark skipped by head(): offsets in `text` are one less than in `markdown`.
        val shift = if (markdown.isNotEmpty() && markdown[0] == BOM) 1 else 0
        // Opening fence: `-{3,}\s*$\n`. The greedy `\s*` ends at the last `\n` of the whitespace after the dashes. A
        // shorter choice (backtracking) cannot find a closing fence the longest one misses: all it adds are line starts
        // inside that same whitespace run, whose `\s*` reaches the same first non-space character.
        var dashes = 0
        while (dashes < length && text[dashes] == '-') dashes++
        if (dashes < 3) return null
        val afterOpening = skipWhitespace(text, dashes)
        var lastLineFeed = -1
        for (i in dashes until afterOpening) {
            if (text[i] == '\n') lastLineFeed = i
        }
        if (lastLineFeed < 0) return null
        val bodyStart = lastLineFeed + 1

        // Lazy body, then `^\s*[-.]{3}` at the first line start where it matches. Every line start up to the first
        // non-space character after it gives the same answer, so a failed try skips to the line start after that.
        var lineStart = bodyStart
        while (true) {
            val fence = skipWhitespace(text, lineStart)
            if (fence + 3 <= length && isFenceChar(text[fence]) && isFenceChar(text[fence + 1]) && isFenceChar(text[fence + 2])) {
                return FrontMatter(text.subSequence(bodyStart, lineStart).toString(), endOfLine(markdown, fence + shift))
            }
            var terminator = fence
            while (terminator < length && !isLineTerminator(text[terminator])) terminator++
            if (terminator >= length) return null
            lineStart = terminator + 1
        }
    }

    /** `true` when [markdown] is a Marp deck. */
    fun isMarp(markdown: CharSequence): Boolean {
        val frontMatter = detectFrontMatter(markdown)
        if (frontMatter.isNullOrEmpty()) return false
        val matcher = MARP_DIRECTIVE.matcher(frontMatter)
        return matcher.find() && matcher.group(2) == "true"
    }

    /**
     * `true` when [file] is a Marp deck. Uses the loaded document when there is one (so unsaved edits count),
     * otherwise reads only the first [FRONT_MATTER_SCAN_CHARS] bytes of the file. Call in a read action.
     */
    fun isMarp(file: VirtualFile): Boolean {
        if (!file.isValid || file.isDirectory) return false
        val document = FileDocumentManager.getInstance().getCachedDocument(file)
        if (document != null) return isMarp(document.immutableCharSequence)
        return isMarp(readHead(file) ?: return false)
    }

    /** Markdown files: the Markdown plugin's file type, or a `.md` / `.markdown` extension mapped to something else. */
    fun isMarkdown(file: VirtualFile): Boolean {
        if (file.isDirectory) return false
        if (FileTypeRegistry.getInstance().isFileOfType(file, MarkdownFileType.INSTANCE)) return true
        val extension = file.extension ?: return false
        return extension.equals("md", ignoreCase = true) || extension.equals("markdown", ignoreCase = true)
    }

    /** A Markdown file that is a Marp deck. Call in a read action. */
    fun isMarpFile(file: VirtualFile): Boolean = isMarkdown(file) && isMarp(file)

    /**
     * Decodes the first [FRONT_MATTER_SCAN_CHARS] bytes of [bytes] like the file would be decoded. A multi-byte
     * character cut at the end becomes U+FFFD, which only matters if the closing fence sits exactly there.
     */
    internal fun decodeHead(bytes: ByteArray, charset: Charset): String {
        val length = minOf(bytes.size, FRONT_MATTER_SCAN_CHARS)
        return String(bytes, 0, length, charset)
    }

    private fun readHead(file: VirtualFile): String? {
        return try {
            val bytes = file.inputStream.use { it.readNBytes(FRONT_MATTER_SCAN_CHARS) }
            decodeHead(bytes, file.charset)
        }
        catch (_: IOException) {
            null
        }
    }

    /** [text] without a leading byte order mark, cut to [FRONT_MATTER_SCAN_CHARS]. */
    private fun head(text: CharSequence): CharSequence {
        val start = if (text.isNotEmpty() && text[0] == BOM) 1 else 0
        val end = minOf(text.length, start + FRONT_MATTER_SCAN_CHARS)
        return if (start == 0 && end == text.length) text else text.subSequence(start, end)
    }

    /**
     * Offset just past the line that contains [from]: after its line terminator (`\r\n` counts as one), or the text
     * length. Looks at the whole [text], not only the scanned head, so a fence line that crosses the head limit is
     * measured completely.
     */
    private fun endOfLine(text: CharSequence, from: Int): Int {
        var i = from
        while (i < text.length && !isLineTerminator(text[i])) i++
        if (i >= text.length) return text.length
        return if (text[i] == '\r' && i + 1 < text.length && text[i + 1] == '\n') i + 2 else i + 1
    }

    /** Index of the first character at or after [from] that is not JavaScript `\s`. */
    private fun skipWhitespace(text: CharSequence, from: Int): Int {
        var i = from
        while (i < text.length && isJsWhitespace(text[i])) i++
        return i
    }

    private fun isFenceChar(c: Char): Boolean = c == '-' || c == '.'

    /** JavaScript LineTerminator. */
    private fun isLineTerminator(c: Char): Boolean = c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029'

    /** JavaScript `\s`, the same set as [JS_WS]. */
    private fun isJsWhitespace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ', '\u00A0', '\u1680', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000', '\uFEFF' -> true
        else -> c in '\u2000'..'\u200A'
    }
}
