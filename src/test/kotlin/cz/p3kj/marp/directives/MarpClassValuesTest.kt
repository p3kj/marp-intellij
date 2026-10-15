package cz.p3kj.marp.directives

import org.junit.Assert.assertEquals
import org.junit.Test

class MarpClassValuesTest {

    private fun themes(frontMatter: String?, vararg comments: String): List<String> =
        MarpClassValues.themeValues(frontMatter?.let { MarpFrontMatter.parse("---\n$it\n---\n") }, comments.toList())

    private val custom = mapOf("alpha" to listOf("one", "two", "lead"), "beta" to listOf("three"), "gaia" to listOf("mine"))

    @Test
    fun themeValuesComeFromTheFrontMatterThenTheCommentsInOrder() {
        assertEquals(listOf("a", "b", "c"), themes("marp: true\ntheme: a", "<!-- theme: b -->", "<!--\npaginate: true\ntheme: c\n-->"))
    }

    @Test
    fun onlyTheGlobalThemeDirectiveCounts() {
        assertEquals(emptyList<String>(), themes("marp: true\n_theme: a", "<!-- _theme: b -->", "<!-- class: c -->"))
    }

    @Test
    fun notesEmptyValuesAndRepeatedKeysAreSkipped() {
        assertEquals(emptyList<String>(), themes(null, "<!-- the theme: nice -->", "<!-- theme: -->"))
        assertEquals(emptyList<String>(), themes(null, "<!-- theme: a\ntheme: b -->"))
    }

    @Test
    fun quotedValuesAreUnquoted() {
        assertEquals(listOf("my theme", "b"), themes("marp: true\ntheme: \"my theme\"", "<!-- theme: 'b' -->"))
    }

    @Test
    fun noThemeOffersTheClassesOfTheDefaultTheme() {
        assertEquals(listOf("lead", "invert"), MarpClassValues.offered(emptyList(), emptyMap()))
        assertEquals(listOf("lead", "invert"), MarpClassValues.offered(emptyList(), custom))
    }

    @Test
    fun builtInThemes() {
        assertEquals(listOf("lead", "invert", "gaia"), MarpClassValues.offered(listOf("gaia"), emptyMap()))
        assertEquals(listOf("lead", "invert"), MarpClassValues.offered(listOf("uncover"), emptyMap()))
    }

    @Test
    fun aCustomThemeAddsItsClassesWithoutDuplicates() {
        assertEquals(listOf("lead", "invert", "one", "two"), MarpClassValues.offered(listOf("alpha"), custom))
    }

    @Test
    fun theLastKnownThemeWins() {
        assertEquals(listOf("lead", "invert", "one", "two"), MarpClassValues.offered(listOf("alpha", "nope"), custom))
        val noGaia = custom - "gaia"
        assertEquals(listOf("lead", "invert", "gaia"), MarpClassValues.offered(listOf("beta", "gaia"), noGaia))
        assertEquals(listOf("lead", "invert", "three"), MarpClassValues.offered(listOf("gaia", "beta"), noGaia))
    }

    @Test
    fun aCustomThemeOverridesABuiltInOneOfTheSameName() {
        assertEquals(listOf("lead", "invert", "mine"), MarpClassValues.offered(listOf("gaia"), custom))
    }

    @Test
    fun onlyUnknownNamesOfferTheClassesOfAllCustomThemes() {
        assertEquals(listOf("lead", "invert", "one", "two", "three", "mine"), MarpClassValues.offered(listOf("nope"), custom))
        assertEquals(listOf("lead", "invert"), MarpClassValues.offered(listOf("nope"), emptyMap()))
    }
}
