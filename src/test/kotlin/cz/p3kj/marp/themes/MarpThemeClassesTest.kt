package cz.p3kj.marp.themes

import org.junit.Assert.assertEquals
import org.junit.Test

class MarpThemeClassesTest {

    private fun classes(css: String): List<String> = MarpThemeClasses.classesOf(css)

    @Test
    fun classesOnTheSlideElement() {
        assertEquals(listOf("two"), classes("section.two ul{}"))
        assertEquals(listOf("two"), classes("section.two h1, section.two li{}"))
        assertEquals(listOf("a", "b"), classes("section.a.b{}"))
        assertEquals(listOf("x"), classes(":is(section.x){}"))
        assertEquals(listOf("y"), classes(":where(section.y) p{}"))
        assertEquals(listOf("z"), classes(":where(section).z{}"))
        assertEquals(listOf("lead"), classes("section:where(.lead){}"))
        assertEquals(listOf("a", "b"), classes("section:is(.a, .b){}"))
        assertEquals(listOf("invert"), classes(":root.invert{}"))
        assertEquals(listOf("Upper"), classes("SECTION.Upper{}"))
    }

    @Test
    fun eachClassOnceInOrderOfFirstAppearance() {
        assertEquals(listOf("lead"), classes("section.lead{} section.lead h1{}"))
        assertEquals(listOf("b", "a"), classes("section.b{} section.a{} section.b{}"))
    }

    @Test
    fun noSlideClasses() {
        for (css in listOf(
            "section{}", "section h1{}", "section > .note{}", "section .columns{}", ".hljs-x{}", "sections.x{}",
            ".section.x{}", "#section.x{}", "section{background:url(a.b.png); --x:.5em}", "section:not(.skip){}",
        )) {
            assertEquals(css, emptyList<String>(), classes(css))
        }
    }

    @Test
    fun commentsAndStringsAreIgnored() {
        assertEquals(listOf("real"), classes("/* section.ghost{} */ section.real{}"))
        assertEquals(listOf("ok"), classes("section::after{content:\"section.fake {\"} section.ok{}"))
        assertEquals(listOf("ok"), classes("section::after{content:'it\\'s {'} section.ok{}"))
        assertEquals(emptyList<String>(), classes("/* section.unterminated{}"))
    }

    @Test
    fun attributeSelectorsDoNotHideTheClass() {
        assertEquals(listOf("real"), classes("section[data-a=\"x.y\"].real{}"))
    }

    @Test
    fun atRules() {
        assertEquals(listOf("p"), classes("@import 'default'; @media print{section.p{}}"))
        assertEquals(emptyList<String>(), classes("@keyframes k{from{opacity:0} to{opacity:1}}"))
    }

    @Test
    fun nestedRules() {
        assertEquals(listOf("n"), classes("section{color:red; &.n{} & h1{}}"))
        assertEquals(listOf("lead", "invert"), classes("section.lead{&.invert{}}"))
        assertEquals(emptyList<String>(), classes(".box{&.no{}}"))
        assertEquals(listOf("m"), classes("section{@media print{&.m{}}}"))
        assertEquals(emptyList<String>(), classes("section{ .x{} }"))
    }

    @Test
    fun emptyCss() {
        assertEquals(emptyList<String>(), classes(""))
    }
}
