package cz.p3kj.marp.themes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarpThemeNamesTest {

    @Test
    fun singleLineComment() {
        assertEquals("a", MarpThemeNames.nameOf("/* @theme a */\nsection { color: red }"))
    }

    @Test
    fun importantComment() {
        assertEquals("b", MarpThemeNames.nameOf("/*! @theme b */"))
    }

    @Test
    fun lineInABlockComment() {
        assertEquals("c", MarpThemeNames.nameOf("/**\n * Company theme\n * @theme c\n * @auto-scaling true\n */\nsection {}"))
    }

    @Test
    fun namesWithDashesAndSpacesAreKeptWholeAndTrimmed() {
        assertEquals("my-theme", MarpThemeNames.nameOf("/* @theme my-theme  */"))
    }

    @Test
    fun noThemeMeta() {
        assertNull(MarpThemeNames.nameOf("section { color: red }"))
        assertNull(MarpThemeNames.nameOf("/* just a comment */"))
        assertNull(MarpThemeNames.nameOf("/* @author me */"))
        assertNull(MarpThemeNames.nameOf("section::after { content: '@theme not-a-meta' }"))
    }

    @Test
    fun lastDeclarationWins() {
        assertEquals("second", MarpThemeNames.nameOf("/* @theme first */\n/* @theme second */"))
    }

    @Test
    fun declarationHasTheNameAndItsOffset() {
        assertEquals(MarpThemeDeclaration("foo", 10), MarpThemeNames.declarationOf("/* @theme foo */"))
        val css = "/* a */\nsection {}\n/*!   @theme   my theme  */"
        val declaration = MarpThemeNames.declarationOf(css)!!
        assertEquals("my theme", declaration.name)
        assertEquals("my theme", css.substring(declaration.nameOffset, declaration.nameOffset + declaration.name.length))
    }

    @Test
    fun declarationOfTheLastDeclarationWins() {
        val css = "/* @theme first */\n/* @theme second */"
        val declaration = MarpThemeNames.declarationOf(css)!!
        assertEquals("second", declaration.name)
        assertEquals(css.indexOf("second"), declaration.nameOffset)
    }

    @Test
    fun declarationInALineOfABlockComment() {
        val css = "/**\n * Company theme\n * @theme c\n * @auto-scaling true\n */\nsection {}"
        assertEquals(MarpThemeDeclaration("c", css.indexOf("@theme c") + "@theme ".length), MarpThemeNames.declarationOf(css))
        val important = "/*!\n! @theme d\n*/"
        assertEquals(MarpThemeDeclaration("d", important.indexOf("d\n")), MarpThemeNames.declarationOf(important))
    }

    @Test
    fun noDeclaration() {
        assertNull(MarpThemeNames.declarationOf("section { color: red }"))
        assertNull(MarpThemeNames.declarationOf("/* just a comment */"))
    }
}
