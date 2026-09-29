package cz.p3kj.marp.directives

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ResourceBundle

class MarpDirectiveCatalogTest {

    private fun known(key: String): MarpDirectiveKey.Known = MarpDirectiveCatalog.resolve(key) as MarpDirectiveKey.Known

    private fun suggestion(key: String): String? = (MarpDirectiveCatalog.resolve(key) as MarpDirectiveKey.Unknown).suggestion

    private fun valid(name: String, raw: String): Boolean {
        val directive = MarpDirectiveCatalog.find(name)!!
        return MarpDirectiveCatalog.isValid(directive, raw, cz.p3kj.marp.slides.MarpHeadingDivider.unquote(raw))
    }

    @Test
    fun globalAndLocalNamesResolve() {
        assertEquals(MarpDirectiveKey.Known(MarpDirectiveCatalog.find("theme")!!, spot = false), MarpDirectiveCatalog.resolve("theme"))
        assertEquals(MarpDirectiveKey.Known(MarpDirectiveCatalog.find("class")!!, spot = false), MarpDirectiveCatalog.resolve("class"))
    }

    @Test
    fun localNamesHaveASpotForm() {
        val key = known("_class")
        assertEquals("class", key.directive.name)
        assertTrue(key.spot)
    }

    @Test
    fun globalNamesHaveNoSpotForm() {
        val key = MarpDirectiveCatalog.resolve("_theme") as MarpDirectiveKey.GlobalWithUnderscore
        assertEquals("theme", key.directive.name)
    }

    @Test
    fun namesAreCaseSensitiveAndMisspellingsGetASuggestion() {
        assertEquals("class", suggestion("Class"))
        assertEquals("_class", suggestion("_clas"))
        assertEquals("paginate", suggestion("paginat"))
        assertEquals("color", suggestion("colour"))
        assertEquals("backgroundColor", suggestion("backgroundcolor"))
    }

    @Test
    fun ordinaryWordsAreNotSuggestedAsDirectives() {
        assertNull(suggestion("Note"))
        assertNull(suggestion("Todo"))
        assertNull(suggestion("Say hello"))
        assertNull(suggestion("Time"))
        assertNull(suggestion("Idea"))
    }

    @Test
    fun paginateIgnoresCase() {
        assertTrue(valid("paginate", "true"))
        assertTrue(valid("paginate", "TRUE"))
        assertTrue(valid("paginate", "hold"))
        assertFalse(valid("paginate", "yes"))
    }

    @Test
    fun mathIsCaseSensitive() {
        assertTrue(valid("math", "katex"))
        assertTrue(valid("math", "mathjax"))
        assertFalse(valid("math", "KaTeX"))
        assertFalse(valid("math", "true"))
    }

    @Test
    fun headingDividerFollowsMarpit() {
        assertTrue(valid("headingDivider", "2"))
        assertTrue(valid("headingDivider", "[1, 3]"))
        assertTrue(valid("headingDivider", "false"))
        assertTrue(valid("headingDivider", "'2'"))
        assertFalse(valid("headingDivider", "7"))
        assertFalse(valid("headingDivider", "yes"))
    }

    @Test
    fun freeTextDirectivesAcceptAnything() {
        assertTrue(valid("class", "whatever you like"))
        assertTrue(valid("size", "custom"))
        assertTrue(valid("backgroundColor", "#fff"))
    }

    @Test
    fun catalogIsCompleteAndDocumented() {
        assertEquals(16, MarpDirectiveCatalog.ALL.size)
        assertEquals(16, MarpDirectiveCatalog.ALL.map { it.name }.toSet().size)
        val bundle = ResourceBundle.getBundle("messages.MarpBundle")
        for (directive in MarpDirectiveCatalog.ALL) {
            assertTrue("no documentation for ${directive.name}", bundle.containsKey(directive.docKey))
            assertNotNull(MarpDirectiveCatalog.find(directive.name))
        }
        assertEquals(
            setOf("theme", "style", "headingDivider", "lang", "size", "math"),
            MarpDirectiveCatalog.ALL.filter { it.scope == MarpDirectiveScope.GLOBAL }.map { it.name }.toSet(),
        )
        assertEquals(
            setOf("size", "math"),
            MarpDirectiveCatalog.ALL.filter { it.origin == MarpDirectiveOrigin.MARP_CORE }.map { it.name }.toSet(),
        )
    }

    @Test
    fun validKeysHaveSpotFormsOfLocalDirectivesOnly() {
        assertTrue("_paginate" in MarpDirectiveCatalog.VALID_KEYS)
        assertFalse("_theme" in MarpDirectiveCatalog.VALID_KEYS)
        assertEquals(6 + 10 + 10, MarpDirectiveCatalog.VALID_KEYS.size)
    }
}
