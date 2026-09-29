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
}
