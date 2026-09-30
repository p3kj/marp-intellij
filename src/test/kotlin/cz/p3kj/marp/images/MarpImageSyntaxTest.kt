package cz.p3kj.marp.images

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ResourceBundle

class MarpImageSyntaxTest {

    /** The alt text spot at the `|` of [textWithCaret], which is not part of the text. */
    private fun spot(textWithCaret: String): MarpImageAltSpot? {
        val caret = textWithCaret.indexOf('|')
        return MarpImageSyntax.altSpot(textWithCaret.removeRange(caret, caret + 1), caret)
    }

    private fun keyword(word: String): String? = MarpImageKeywordCatalog.resolve(word)?.name

    @Test
    fun theFirstWordHasNoOtherWords() {
        val spot = spot("![b|")!!
        assertEquals("b", spot.prefix)
        assertEquals("b", spot.word)
        assertEquals(emptyList<String>(), spot.otherWords)
        assertEquals(2, spot.altStart)
        assertEquals(3, spot.altEnd)
    }

    @Test
    fun anEmptyAltTextHasAnEmptyPrefix() {
        val spot = spot("![|](x.png)")!!
        assertEquals("", spot.prefix)
        assertEquals("", spot.word)
        assertEquals(2, spot.altEnd)
    }

    @Test
    fun theOtherWordsAreTheRestOfTheAltText() {
        val spot = spot("![bg le|](x.png)")!!
        assertEquals("le", spot.prefix)
        assertEquals("le", spot.word)
        assertEquals(listOf("bg"), spot.otherWords)
    }

    @Test
    fun aWordAfterASpaceStartsEmpty() {
        val spot = spot("![bg |](x.png)")!!
        assertEquals("", spot.prefix)
        assertEquals(listOf("bg"), spot.otherWords)
    }

    @Test
    fun theWordAroundTheCaretIsWholeAndThePrefixEndsAtTheCaret() {
        val spot = spot("![bg le|ft blur](x.png)")!!
        assertEquals("le", spot.prefix)
        assertEquals("left", spot.word)
        assertEquals(listOf("bg", "blur"), spot.otherWords)
    }

    @Test
    fun anyWhitespaceSeparatesWords() {
        val spot = spot("![bg\tleft   sep|ia](x.png)")!!
        assertEquals("sep", spot.prefix)
        assertEquals(listOf("bg", "left"), spot.otherWords)
    }

    @Test
    fun theCaretDirectlyBehindABracketIsInTheAltText() {
        assertNotNull(spot("text ![|"))
        assertNotNull(spot("![bg](x.png) and ![|](y.png)"))
    }

    @Test
    fun theCaretBehindTheClosingBracketIsNotInTheAltText() {
        assertNull(spot("![bg]|(x.png)"))
        assertNull(spot("![bg](x|.png)"))
        assertNull(spot("![bg](x.png) |"))
        assertNull(spot("![bg](x.png) some te|xt"))
    }

    @Test
    fun aLinkIsNotAnImage() {
        assertNull(spot("[link|"))
        assertNull(spot("[link|](x)"))
        assertNull(spot("see [the bg|](x)"))
    }

    @Test
    fun theCaretBeforeTheImageIsNotInTheAltText() {
        assertNull(spot("|![bg](x.png)"))
        assertNull(spot("!|[bg](x.png)"))
        assertNull(spot("plain te|xt"))
    }

    @Test
    fun theAltTextEndsAtTheLine() {
        assertNull(spot("![bg\nle|"))
        val spot = spot("![bg le|\nnext ](x)")!!
        assertEquals(listOf("bg"), spot.otherWords)
        assertEquals(7, spot.altEnd)
    }

    @Test
    fun aBracketInTheAltTextEndsIt() {
        assertNull(spot("![a [b] c|]"))
        assertNull(spot("![a [b c|]"))
    }

    @Test
    fun keywordsResolveByTheirName() {
        assertEquals("left", keyword("left:40%"))
        assertEquals("left", keyword("left"))
        assertEquals("w", keyword("w:400"))
        assertEquals("width", keyword("width:auto"))
        assertEquals("blur", keyword("blur"))
        assertEquals("blur", keyword("blur:5px"))
        assertEquals("drop-shadow", keyword("drop-shadow:0,5px,10px"))
        assertEquals("bg", keyword("bg"))
        assertNull(keyword("photo"))
        assertNull(keyword("Blur"))
        assertNull(keyword(""))
    }

    @Test
    fun percentagesResolveToTheScaleKeyword() {
        assertSame(MarpImageKeywordCatalog.PERCENTAGE, MarpImageKeywordCatalog.resolve("80%"))
        assertSame(MarpImageKeywordCatalog.PERCENTAGE, MarpImageKeywordCatalog.resolve("33.5%"))
        assertSame(MarpImageKeywordCatalog.PERCENTAGE, MarpImageKeywordCatalog.resolve(".5%"))
        assertNull(MarpImageKeywordCatalog.resolve("%"))
        assertNull(MarpImageKeywordCatalog.resolve("80"))
        assertNull(MarpImageKeywordCatalog.resolve("8x%"))
    }

    @Test
    fun theLookupStringOfAKeyValueKeywordEndsInAColon() {
        assertEquals("w:", MarpImageKeywordCatalog.resolve("w")!!.lookupString)
        assertEquals("height:", MarpImageKeywordCatalog.resolve("height")!!.lookupString)
        assertEquals("left", MarpImageKeywordCatalog.resolve("left")!!.lookupString)
        assertEquals("sepia", MarpImageKeywordCatalog.resolve("sepia")!!.lookupString)
    }

    @Test
    fun optionsAreLikelyWhileEveryOtherWordIsAKeyword() {
        assertTrue(MarpImageSyntax.optionsLikely(spot("![b|")!!))
        assertTrue(MarpImageSyntax.optionsLikely(spot("![bg left:40% b|")!!))
        assertFalse(MarpImageSyntax.optionsLikely(spot("![A photo b|")!!))
        assertFalse(MarpImageSyntax.optionsLikely(spot("![bg photo b|")!!))
    }

    @Test
    fun backgroundIsAnotherWordOfTheAltText() {
        assertTrue(MarpImageSyntax.hasBackground(spot("![bg |")!!))
        assertTrue(MarpImageSyntax.hasBackground(spot("![blur bg co|ver")!!))
        assertFalse(MarpImageSyntax.hasBackground(spot("![b|")!!))
        assertFalse(MarpImageSyntax.hasBackground(spot("![bg|")!!))
    }

    @Test
    fun catalogIsCompleteAndDocumented() {
        val names = MarpImageKeywordCatalog.ALL.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertEquals(
            listOf(
                "bg", "left", "right", "fit", "contain", "cover", "auto", "vertical", "horizontal", "w", "h", "width", "height",
                "blur", "brightness", "contrast", "drop-shadow", "grayscale", "hue-rotate", "invert", "opacity", "saturate", "sepia",
            ),
            names,
        )
        val bundle = ResourceBundle.getBundle("messages.MarpBundle")
        for (keyword in MarpImageKeywordCatalog.ALL + MarpImageKeywordCatalog.PERCENTAGE) {
            assertTrue("no documentation for ${keyword.name}", bundle.containsKey(keyword.docKey))
        }
        for (key in listOf("hint", "default", "origin", "origin.marpit")) {
            assertTrue("no message image.doc.$key", bundle.containsKey("image.doc.$key"))
        }
        for (key in listOf("background", "size", "filter")) {
            assertTrue("no message image.completion.type.$key", bundle.containsKey("image.completion.type.$key"))
        }
    }

    @Test
    fun onlyTheBackgroundWordsNeedBg() {
        val onlyWithBg = MarpImageKeywordCatalog.ALL.filter { it.backgroundOnly }.map { it.name }
        assertEquals(listOf("left", "right", "fit", "contain", "cover", "auto", "vertical", "horizontal"), onlyWithBg)
        assertTrue(MarpImageKeywordCatalog.PERCENTAGE.backgroundOnly)
    }
}
