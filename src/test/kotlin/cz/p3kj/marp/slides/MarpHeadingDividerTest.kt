package cz.p3kj.marp.slides

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Expectations follow Marpit's `headingDivider` directive (`src/markdown/directives/directives.js`). */
class MarpHeadingDividerTest {

    private fun levels(vararg values: Int): Set<Int> = values.toSet()

    private fun resolve(frontMatter: String?, vararg comments: String): Set<Int> = MarpHeadingDivider.resolve(frontMatter, comments.toList())

    @Test
    fun aNumberMeansAllLevelsUpToIt() {
        assertEquals(levels(1), MarpHeadingDivider.parseValue("1"))
        assertEquals(levels(1, 2), MarpHeadingDivider.parseValue("2"))
        assertEquals(levels(1, 2, 3, 4, 5, 6), MarpHeadingDivider.parseValue("6"))
        assertEquals(levels(1, 2, 3), MarpHeadingDivider.parseValue("  3  "))
    }

    @Test
    fun valuesAreReadWithParseInt() {
        assertEquals(levels(1, 2), MarpHeadingDivider.parseValue("2.5"))
        assertEquals(levels(1, 2), MarpHeadingDivider.parseValue("2 # a comment"))
        assertEquals(levels(1, 2), MarpHeadingDivider.parseValue("2abc"))
        assertEquals(levels(1, 2), MarpHeadingDivider.parseValue("+2"))
    }

    @Test
    fun quotedValuesAreStrings() {
        assertEquals(levels(1, 2, 3), MarpHeadingDivider.parseValue("\"3\""))
        assertEquals(levels(1, 2), MarpHeadingDivider.parseValue("'2'"))
        assertEquals(emptySet<Int>(), MarpHeadingDivider.parseValue("\"false\""))
    }

    @Test
    fun falseTurnsDividingOff() {
        assertEquals(emptySet<Int>(), MarpHeadingDivider.parseValue("false"))
        assertEquals(emptySet<Int>(), MarpHeadingDivider.parseValue("false # off"))
    }

    @Test
    fun invalidValuesAreIgnored() {
        for (value in listOf("0", "7", "-1", "abc", "true", "False", "", "  ", "# only a comment", "e1", "99999999999999")) {
            assertNull("'$value'", MarpHeadingDivider.parseValue(value))
        }
    }

    @Test
    fun listsKeepTheLevelsTheyContain() {
        assertEquals(levels(1, 3), MarpHeadingDivider.parseValue("[1, 3]"))
        assertEquals(levels(3, 1), MarpHeadingDivider.parseValue("[3,1,3]"))
        assertEquals(levels(1, 4), MarpHeadingDivider.parseValue("[1, 9, x, 4, 0]"))
        assertEquals(levels(2, 5), MarpHeadingDivider.parseValue("[\"2\", '5']"))
        assertEquals(levels(1, 2), MarpHeadingDivider.parseValue("[1, 2] # comment"))
        assertEquals(emptySet<Int>(), MarpHeadingDivider.parseValue("[]"))
        assertEquals(emptySet<Int>(), MarpHeadingDivider.parseValue("[7, 8]"))
    }

    @Test
    fun frontMatterValue() {
        assertEquals(levels(1, 2), resolve("marp: true\nheadingDivider: 2\n"))
        assertEquals(levels(1, 3), resolve("headingDivider: [1, 3]\nmarp: true\n"))
        assertEquals(levels(1, 2), resolve("marp: true\r\nheadingDivider: 2\r\n"))
        assertEquals(levels(1, 2), resolve("headingDivider : 2\n"))
        assertEquals(emptySet<Int>(), resolve("marp: true\n"))
        assertEquals(emptySet<Int>(), resolve(null))
    }

    @Test
    fun blockSequence() {
        assertEquals(levels(1, 3), resolve("headingDivider:\n  - 1\n  - 3\nmarp: true\n"))
        assertEquals(levels(2), resolve("headingDivider:\n- 2\n"))
        assertEquals(levels(1, 4), resolve("headingDivider:\n  - '1'\n  - \"4\"\n  - x\n"))
        // Nothing after the key: null in YAML, ignored.
        assertEquals(emptySet<Int>(), resolve("headingDivider:\nmarp: true\n"))
        assertEquals(levels(2, 3), resolve("headingDivider: # levels\n  - 2\n  - 3\n"))
    }

    @Test
    fun onlyTopLevelKeysCount() {
        assertEquals(emptySet<Int>(), resolve("note: headingDivider: 2\n"))
        assertEquals(emptySet<Int>(), resolve("  headingDivider: 2\n"))
        assertEquals(emptySet<Int>(), resolve("_headingDivider: 2\n"))
        assertEquals(emptySet<Int>(), resolve("headingDividers: 2\n"))
        assertEquals(emptySet<Int>(), resolve("xheadingDivider: 2\n"))
    }

    @Test
    fun commentValue() {
        assertEquals(levels(1), resolve(null, "<!-- headingDivider: 1 -->"))
        assertEquals(levels(1), resolve(null, "<!--- headingDivider: 1 --->"))
        assertEquals(levels(1, 2), resolve(null, "<!--headingDivider: 2-->"))
        assertEquals(levels(1, 2), resolve(null, "<!-- headingDivider: 2 ---->"))
        assertEquals(levels(1, 2), resolve(null, "<!--\nheadingDivider: 2\n-->"))
        assertEquals(levels(1, 2), resolve(null, "<!--\ntheme: gaia\nheadingDivider: 2\n-->\nmore text after"))
        assertEquals(levels(1, 3), resolve(null, "<!-- headingDivider: [1, 3] -->"))
    }

    @Test
    fun commentsOverrideTheFrontMatterAndTheLastValidValueWins() {
        assertEquals(emptySet<Int>(), resolve("headingDivider: 2\n", "<!-- headingDivider: false -->"))
        assertEquals(levels(1, 2, 3), resolve("headingDivider: 1\n", "<!-- headingDivider: 2 -->", "<!-- headingDivider: 3 -->"))
        assertEquals(levels(1), resolve("headingDivider: 3\n", "<!-- headingDivider: 2 -->", "<!-- headingDivider: 1 -->"))
    }

    @Test
    fun invalidValuesKeepThePreviousOne() {
        assertEquals(levels(1, 2), resolve("headingDivider: 2\n", "<!-- headingDivider: abc -->"))
        assertEquals(levels(1, 2), resolve("headingDivider: 2\nheadingDivider: 9\n"))
        assertEquals(levels(1), resolve(null, "<!-- headingDivider: 1 -->", "<!-- headingDivider: 7 -->"))
    }

    @Test
    fun commentsWithoutTheKeyAreIgnored() {
        assertEquals(levels(1, 2), resolve("headingDivider: 2\n", "<!-- _class: lead -->", "<!-- just a note -->", "<!-- note: headingDivider: 1 -->"))
        assertEquals(emptySet<Int>(), resolve(null, "<!-- _headingDivider: 2 -->"))
        assertEquals(emptySet<Int>(), resolve(null, "<!-- -->", "<!---->", "not a comment", "<!-- unterminated headingDivider: 2"))
    }

    @Test
    fun longCommentsAreScannedInLinearTime() {
        val spaces = " ".repeat(200_000)
        val started = System.nanoTime()
        val result = resolve(null, "<!--$spaces headingDivider: 2 $spaces-->", "<!--${spaces}x$spaces-->", "<!--$spaces")
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $elapsedMs ms", elapsedMs < 2_000)
        assertEquals(levels(1, 2), result)
    }
}
