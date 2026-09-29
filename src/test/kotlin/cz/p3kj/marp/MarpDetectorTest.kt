package cz.p3kj.marp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern
import kotlin.random.Random

/**
 * Expected values were produced by running marp-vscode's `detectMarpFromMarkdown` / `detectFrontMatter`
 * (src/utils.ts) in Node.js on the same inputs.
 */
class MarpDetectorTest {

    private fun assertMarp(expected: Boolean, markdown: String) {
        assertEquals("isMarp(${markdown.escaped()})", expected, MarpDetector.isMarp(markdown))
    }

    private fun String.escaped() = replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t").replace("\uFEFF", "<BOM>")

    @Test
    fun noFrontMatter() {
        assertMarp(false, "")
        assertMarp(false, "# Title\n\nmarp: true\n")
        assertNull(MarpDetector.detectFrontMatter("# Title\n"))
    }

    @Test
    fun marpFalse() {
        assertMarp(false, "---\nmarp: false\n---\n# Hi")
    }

    @Test
    fun marpTrue() {
        assertMarp(true, "---\nmarp: true\n---\n# Hi")
        assertEquals("marp: true\n", MarpDetector.detectFrontMatter("---\nmarp: true\n---\n# Hi"))
    }

    @Test
    fun marpTrueWithOtherKeys() {
        assertMarp(true, "---\ntitle: Deck\nmarp: true\ntheme: gaia\n---\n# Hi")
    }

    @Test
    fun frontMatterMustStartAtIndexZero() {
        assertMarp(false, "\n---\nmarp: true\n---\n")
        assertMarp(false, "Intro\n---\nmarp: true\n---\n")
        assertMarp(false, " ---\nmarp: true\n---\n")
    }

    @Test
    fun crlf() {
        assertMarp(true, "---\r\nmarp: true\r\n---\r\n# Hi")
        assertMarp(true, "---\r\ntheme: gaia\r\nmarp: true\r\n---\r\n")
        // Same capture as JavaScript: the closing `\s*` swallows the last line feed.
        assertEquals("marp: true\r", MarpDetector.detectFrontMatter("---\r\nmarp: true\r\n---\r\n# Hi"))
    }

    @Test
    fun bareCarriageReturnsAreNotAFrontMatter() {
        // The opening fence needs a real `\n` in marp-vscode too.
        assertMarp(false, "---\rmarp: true\r---\r")
    }

    @Test
    fun byteOrderMarkIsIgnored() {
        // Editors never expose the BOM as document text (VS Code neither), so a UTF-8 file with BOM is a deck.
        assertMarp(true, "\uFEFF---\nmarp: true\n---\n")
    }

    @Test
    fun valueMustBeExactlyTrue() {
        assertMarp(false, "---\nmarp: true # comment\n---\n")
        assertMarp(false, "---\nmarp: \"true\"\n---\n")
        assertMarp(false, "---\nmarp: 'true'\n---\n")
        assertMarp(false, "---\nmarp: True\n---\n")
        assertMarp(false, "---\nmarp: true   \n---\n")
        assertMarp(false, "---\nmarp: true\t\n---\n")
    }

    @Test
    fun directiveSpacing() {
        assertMarp(false, "---\nmarp:true\n---\n")
        assertMarp(true, "---\nmarp:  true\n---\n")
        assertMarp(true, "---\nmarp : true\n---\n")
        assertMarp(false, "---\nmarp:\ttrue\n---\n")
        assertMarp(false, "---\n  marp: true\n---\n")
        assertMarp(false, "---\nmarpit: true\n---\n")
    }

    @Test
    fun firstDirectiveWins() {
        assertMarp(false, "---\nmarp: false\nmarp: true\n---\n")
        assertMarp(true, "---\nmarp: true\nmarp: false\n---\n")
    }

    @Test
    fun fences() {
        assertMarp(true, "---\nmarp: true\n...\n")
        assertMarp(true, "----\nmarp: true\n----\n")
        assertMarp(true, "---  \nmarp: true\n---\n")
        assertMarp(true, "---\n\nmarp: true\n---\n")
        assertMarp(true, "---\nmarp: true\n---")
        assertMarp(false, "---\nmarp: true\n")
        assertMarp(false, "---\n---\nmarp: true\n")
    }

    @Test
    fun decodeHeadStripsNothingButLimitsLength() {
        val bytes = ("\uFEFF---\nmarp: true\n---\n" + "x".repeat(100_000)).toByteArray(Charsets.UTF_8)
        val head = MarpDetector.decodeHead(bytes, Charsets.UTF_8)
        assertTrue(head.length <= MarpDetector.FRONT_MATTER_SCAN_CHARS)
        assertTrue(MarpDetector.isMarp(head))
        assertFalse(MarpDetector.isMarp(MarpDetector.decodeHead("# no".toByteArray(), Charsets.UTF_8)))
    }

    @Test
    fun frontMatterMustEndWithinTheScannedHead() {
        val limit = MarpDetector.FRONT_MATTER_SCAN_CHARS
        val filler = "title: x\n"
        fun deck(fillerLines: Int) = "---\nmarp: true\n" + filler.repeat(fillerLines) + "---\n# Slide\n"
        val fits = (limit - "---\nmarp: true\n---".length) / filler.length
        assertMarp(true, deck(fits))
        assertMarp(false, deck(fits + 1))
        // The byte order mark does not count.
        assertMarp(true, "\uFEFF" + deck(fits))
    }

    @Test
    fun longWhitespaceRunsAreDetectedQuickly() {
        // A backtracking regex needs seconds for a thousand blank lines after `---`, and this runs in read actions.
        val inputs = listOf(
            "---\n" + "\n".repeat(200_000),
            "---" + " \n".repeat(200_000) + "x",
            "---\n" + "\n".repeat(200_000) + "marp: true\n---\n",
            "---\n" + "\r\n".repeat(100_000) + "---\n",
            "---\n" + "a\n".repeat(200_000),
            "---" + "-".repeat(200_000) + "\n" + " ".repeat(200_000),
        )
        val started = System.nanoTime()
        for (input in inputs) MarpDetector.isMarp(input)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $elapsedMs ms", elapsedMs < 2_000)
        // Blank lines before the closing fence belong to the fence, as in JavaScript.
        assertEquals("", MarpDetector.detectFrontMatter("---\n" + "\n".repeat(1_000) + "---\n"))
        assertEquals("marp: true\n", MarpDetector.detectFrontMatter("---\nmarp: true\n" + "\n".repeat(1_000) + "---\n"))
    }

    /** marp-vscode's front matter regular expression, translated to Java: the reference for the linear scanner. */
    private val referencePattern: Pattern = run {
        val ws = "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"
        val lt = "\\n\\r\\u2028\\u2029"
        val lineStart = "(?<![^$lt])"
        val lineEnd = "(?![^$lt])"
        Pattern.compile("$lineStart(-{3,}$ws*$lineEnd\\n)([\\s\\S]*?)$lineStart($ws*[-.]{3})")
    }

    private fun referenceFrontMatter(markdown: String): String? {
        val matcher = referencePattern.matcher(markdown)
        return if (matcher.lookingAt()) matcher.group(2) else null
    }

    @Test
    fun scannerFindsWhatTheRegularExpressionFinds() {
        val alphabet = charArrayOf('-', '-', '-', '.', '\n', '\n', '\r', ' ', '\t', '\u2028', '\u00A0', 'm', 'x', ':')
        val random = Random(20260929)
        repeat(20_000) {
            val text = buildString { repeat(random.nextInt(0, 18)) { append(alphabet[random.nextInt(alphabet.size)]) } }
            // Most random strings do not start with a fence; give half of them one.
            val input = if (random.nextBoolean()) "---" + text else text
            assertEquals(input.escaped(), referenceFrontMatter(input), MarpDetector.detectFrontMatter(input))
        }
    }
}
