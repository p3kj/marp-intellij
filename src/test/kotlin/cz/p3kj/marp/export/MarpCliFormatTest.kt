package cz.p3kj.marp.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class MarpCliFormatTest {

    @Test
    fun theExtensionsAreTheFileExtensions() {
        assertEquals("pptx", MarpCliFormat.PPTX.extension)
        assertEquals("png", MarpCliFormat.PNG.extension)
        assertEquals("jpg", MarpCliFormat.JPEG.extension)
    }

    @Test
    fun defaultNameReplacesTheMarkdownExtension() {
        assertEquals("deck.pptx", MarpCliFormat.PPTX.defaultName("deck.md"))
        assertEquals("deck.png", MarpCliFormat.PNG.defaultName("deck.md"))
        assertEquals("deck.jpg", MarpCliFormat.JPEG.defaultName("deck.md"))
        assertEquals("deck.pptx", MarpCliFormat.PPTX.defaultName(".md"))
    }

    @Test
    fun withExtensionAppendsAMissingOneAndKeepsAPresentOne() {
        assertEquals(Path.of("out", "deck.pptx"), MarpCliFormat.PPTX.withExtension(Path.of("out", "deck")))
        assertEquals(Path.of("out", "deck.jpg"), MarpCliFormat.JPEG.withExtension(Path.of("out", "deck")))
        val path = Path.of("out", "deck.png")
        assertSame(path, MarpCliFormat.PNG.withExtension(path))
        assertEquals(Path.of("out", "Deck.PPTX"), MarpCliFormat.PPTX.withExtension(Path.of("out", "Deck.PPTX")))
    }

    @Test
    fun onlyTheImageFormatsWriteOneFilePerSlide() {
        assertFalse(MarpCliFormat.PPTX.isImages)
        assertTrue(MarpCliFormat.PNG.isImages)
        assertTrue(MarpCliFormat.JPEG.isImages)
    }
}
