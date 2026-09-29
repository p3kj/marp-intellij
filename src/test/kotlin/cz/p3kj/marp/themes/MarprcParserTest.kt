package cz.p3kj.marp.themes

import junit.framework.TestCase

class MarprcParserTest : TestCase() {
    fun testYamlScalar() {
        assertEquals(listOf("themes"), MarprcParser.parseThemeSet(".marprc.yml", "# c\nthemeSet: themes\n"))
        assertEquals(listOf("my themes"), MarprcParser.parseThemeSet(".marprc.yml", "themeSet: \"my themes\" # x\n"))
        assertEquals(listOf("a"), MarprcParser.parseThemeSet(".marprc", "themeSet: 'a'"))
    }

    fun testYamlLists() {
        assertEquals(listOf("a.css", "b c"), MarprcParser.parseThemeSet(".marprc.yml", "themeSet: [a.css, \"b c\"]"))
        assertEquals(
            listOf("a", "b"),
            MarprcParser.parseThemeSet(".marprc.yml", "themeSet:\n  - a\n  - 'b'\nother: 1\n"),
        )
        assertEquals(listOf("a", "b"), MarprcParser.parseThemeSet(".marprc.yml", "themeSet:\n- a\n- b\n"))
        assertEquals(listOf("a", "b"), MarprcParser.parseThemeSet(".marprc.yml", "themeSet: [a,\n  b]\n"))
    }

    fun testAbsent() {
        assertEquals(emptyList<String>(), MarprcParser.parseThemeSet(".marprc.yml", "html: true\n"))
    }

    fun testJson() {
        assertEquals(listOf("t"), MarprcParser.parseThemeSet(".marprc.json", """{"html": true, "n": 1.5, "themeSet": "t"}"""))
        assertEquals(
            listOf("a", "b\"c"),
            MarprcParser.parseThemeSet(".marprc.json", """{"themeSet": ["a", "b\"c"], "x": {"y": [1, null]}}"""),
        )
        assertEquals(listOf("t"), MarprcParser.parseThemeSet(".marprc", """{"themeSet": "t"}"""))
    }

    fun testBrokenJson() {
        try {
            MarprcParser.parseThemeSet(".marprc.json", """{"themeSet": """)
            fail("expected failure")
        } catch (_: IllegalArgumentException) {
        }
    }
}
