package cz.p3kj.marp.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.nio.file.Path

class MarpExportFormatTest {

    @Test
    fun theExtensionsAreTheFileExtensions() {
        assertEquals("html", MarpExportFormat.HTML.extension)
        assertEquals("pdf", MarpExportFormat.PDF.extension)
    }

    @Test
    fun defaultNameReplacesTheMarkdownExtension() {
        assertEquals("deck.html", MarpExportFormat.HTML.defaultName("deck.md"))
        assertEquals("deck.pdf", MarpExportFormat.PDF.defaultName("deck.md"))
        assertEquals("talk.html", MarpExportFormat.HTML.defaultName("talk.markdown"))
        assertEquals("my.talk.pdf", MarpExportFormat.PDF.defaultName("my.talk.md"))
    }

    @Test
    fun defaultNameOfAFileWithoutExtensionAppendsOne() {
        assertEquals("notes.html", MarpExportFormat.HTML.defaultName("notes"))
    }

    @Test
    fun defaultNameOfABareExtensionUsesAPlaceholderName() {
        assertEquals("deck.html", MarpExportFormat.HTML.defaultName(".md"))
        assertEquals("deck.pdf", MarpExportFormat.PDF.defaultName(""))
    }

    @Test
    fun withExtensionKeepsAPathThatHasIt() {
        val path = Path.of("out", "deck.html")
        assertSame(path, MarpExportFormat.HTML.withExtension(path))
        assertEquals(Path.of("out", "Deck.PDF"), MarpExportFormat.PDF.withExtension(Path.of("out", "Deck.PDF")))
    }

    @Test
    fun withExtensionAppendsAMissingOne() {
        assertEquals(Path.of("out", "deck.html"), MarpExportFormat.HTML.withExtension(Path.of("out", "deck")))
        assertEquals(Path.of("out", "deck.pdf"), MarpExportFormat.PDF.withExtension(Path.of("out", "deck")))
        assertEquals(Path.of("deck.html"), MarpExportFormat.HTML.withExtension(Path.of("deck")))
    }

    @Test
    fun withExtensionDoesNotReplaceAnotherExtension() {
        assertEquals(Path.of("out", "deck.pdf.html"), MarpExportFormat.HTML.withExtension(Path.of("out", "deck.pdf")))
        assertEquals(Path.of("out", "deck.v2.pdf"), MarpExportFormat.PDF.withExtension(Path.of("out", "deck.v2")))
    }

    @Test
    fun aNameThatMerelyEndsInTheLettersNeedsTheDot() {
        assertEquals(Path.of("myhtml.html"), MarpExportFormat.HTML.withExtension(Path.of("myhtml")))
    }
}
