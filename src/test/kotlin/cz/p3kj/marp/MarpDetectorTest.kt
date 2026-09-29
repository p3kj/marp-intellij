package cz.p3kj.marp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        val bytes = ("\uFEFF---\nmarp: true\n---\n" + "x".repeat(20_000)).toByteArray(Charsets.UTF_8)
        val head = MarpDetector.decodeHead(bytes, Charsets.UTF_8)
        assertTrue(head.length <= MarpDetector.HEAD_BYTES)
        assertTrue(MarpDetector.isMarp(head))
        assertFalse(MarpDetector.isMarp(MarpDetector.decodeHead("# no".toByteArray(), Charsets.UTF_8)))
    }
}
