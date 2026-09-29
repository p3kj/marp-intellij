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
 * https://github.com/marp-team/marp-vscode, MIT License, Copyright (c) 2019- Marp team (marp-team@marp.app)).
 * The JavaScript regular expressions are translated so that Java's regex engine matches exactly the same strings:
 * JavaScript `\s` is spelled out, and the multiline `^` / `$` anchors become look-arounds over JavaScript's line
 * terminators (Java would otherwise refuse to match between `\r` and `\n`).
 *
 * Consequences of following marp-vscode exactly: the value must be the literal `true` up to the end of the line, so
 * `marp: true # comment`, `marp: "true"` and trailing whitespace after `true` are not detected.
 * A leading byte order mark is ignored, like in the editors (neither VS Code nor IntelliJ documents contain it).
 */
object MarpDetector {

    /** How much of a file that is not loaded into a document is read from disk. */
    const val HEAD_BYTES: Int = 8 * 1024

    private const val BOM = '﻿'

    /** JavaScript `\s`: WhiteSpace and LineTerminator code points. */
    private const val JS_WS = "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"

    /** JavaScript line terminators. */
    private const val JS_LT = "\\n\\r\\u2028\\u2029"

    /** JavaScript multiline `^`. */
    private const val LINE_START = "(?<![^$JS_LT])"

    /** JavaScript multiline `$`. */
    private const val LINE_END = "(?![^$JS_LT])"

    /** marp-vscode: `/^(-{3,}\s*$\n)([\s\S]*?)^(\s*[-.]{3})/m` */
    private val FRONT_MATTER: Pattern =
        Pattern.compile("$LINE_START(-{3,}$JS_WS*$LINE_END\\n)([\\s\\S]*?)$LINE_START($JS_WS*[-.]{3})")

    /** marp-vscode: `/^(marp\s*: +)(.*)\s*$/m` */
    private val MARP_DIRECTIVE: Pattern =
        Pattern.compile("$LINE_START(marp$JS_WS*: +)([^$JS_LT]*)$JS_WS*$LINE_END")

    /** The front matter body if [markdown] starts with front matter, otherwise `null`. */
    fun detectFrontMatter(markdown: CharSequence): String? {
        val matcher = FRONT_MATTER.matcher(withoutBom(markdown))
        return if (matcher.lookingAt()) matcher.group(2) else null
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
     * otherwise reads only the first [HEAD_BYTES] of the file. Call in a read action.
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

    /** Decodes the first [HEAD_BYTES] of [bytes] like the file would be decoded. */
    internal fun decodeHead(bytes: ByteArray, charset: Charset): String {
        val length = minOf(bytes.size, HEAD_BYTES)
        return String(bytes, 0, length, charset)
    }

    private fun readHead(file: VirtualFile): String? {
        return try {
            val bytes = file.inputStream.use { it.readNBytes(HEAD_BYTES) }
            decodeHead(bytes, file.charset)
        }
        catch (_: IOException) {
            null
        }
    }

    private fun withoutBom(text: CharSequence): CharSequence =
        if (text.isNotEmpty() && text[0] == BOM) text.subSequence(1, text.length) else text
}
