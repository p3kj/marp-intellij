package cz.p3kj.marp.preview

import junit.framework.TestCase

class MarpPresentOptionsTest : TestCase() {

    fun testJsonHasBaseHrefAndStart() {
        val json = MarpPresentOptions("file:///work/talk/", 3).toJson()
        assertEquals("file:///work/talk/", json.get("baseHref").asString)
        assertEquals(3, json.get("start").asInt)
        assertEquals(setOf("baseHref", "start"), json.keySet())
    }

    fun testJsonOmitsAMissingBaseHref() {
        val json = MarpPresentOptions(null, 0).toJson()
        assertFalse(json.has("baseHref"))
        assertEquals(0, json.get("start").asInt)
        assertEquals("""{"start":0}""", json.toString())
    }

    fun testJsonEscapesTheBaseHref() {
        val json = MarpPresentOptions("file:///a\"b/", 1).toJson()
        assertEquals("""{"baseHref":"file:///a\"b/","start":1}""", json.toString())
    }
}
