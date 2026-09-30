package cz.p3kj.marp.preview

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarpSlideMoveTest {

    private fun json(text: String): JsonObject = JsonParser.parseString(text).asJsonObject

    private fun parse(text: String): MarpSlideMove? = MarpSlideMove.parse(json(text))

    @Test
    fun aWellFormedMessageIsParsed() {
        assertEquals(MarpSlideMove(0, 2, 5, 3), parse("""{"type":"didMoveSlide","from":0,"to":2,"line":5,"count":3}"""))
        assertEquals(MarpSlideMove(3, 1, 0, 4), parse("""{"from":3,"to":1,"line":0,"count":4}"""))
        // A whole number written with a fraction part is still whole.
        assertEquals(MarpSlideMove(1, 0, 2, 2), parse("""{"from":1.0,"to":0,"line":2,"count":2}"""))
    }

    @Test
    fun aMissingFieldGivesNothing() {
        assertNull(parse("""{"to":2,"line":5,"count":3}"""))
        assertNull(parse("""{"from":0,"line":5,"count":3}"""))
        assertNull(parse("""{"from":0,"to":2,"count":3}"""))
        assertNull(parse("""{"from":0,"to":2,"line":5}"""))
        assertNull(parse("{}"))
    }

    @Test
    fun fieldsThatAreNotWholeNonNegativeNumbersGiveNothing() {
        assertNull(parse("""{"from":-1,"to":2,"line":5,"count":3}"""))
        assertNull(parse("""{"from":0,"to":-2,"line":5,"count":3}"""))
        assertNull(parse("""{"from":0,"to":2,"line":-5,"count":3}"""))
        assertNull(parse("""{"from":0,"to":2,"line":5,"count":-3}"""))
        assertNull(parse("""{"from":0.5,"to":2,"line":5,"count":3}"""))
        assertNull(parse("""{"from":0,"to":1.5,"line":5,"count":3}"""))
        assertNull(parse("""{"from":"0","to":2,"line":5,"count":3}"""))
        assertNull(parse("""{"from":0,"to":null,"line":5,"count":3}"""))
        assertNull(parse("""{"from":0,"to":2,"line":true,"count":3}"""))
        assertNull(parse("""{"from":0,"to":2,"line":[5],"count":3}"""))
        assertNull(parse("""{"from":0,"to":2,"line":5,"count":{"n":3}}"""))
        assertNull(parse("""{"from":0,"to":2,"line":9999999999,"count":3}"""))
    }

    @Test
    fun aMoveNeedsTwoDifferentSlidesOfThePage() {
        assertNull(parse("""{"from":1,"to":1,"line":5,"count":3}"""))
        assertNull(parse("""{"from":3,"to":1,"line":5,"count":3}"""))
        assertNull(parse("""{"from":1,"to":3,"line":5,"count":3}"""))
        assertNull(parse("""{"from":0,"to":1,"line":5,"count":0}"""))
    }
}
