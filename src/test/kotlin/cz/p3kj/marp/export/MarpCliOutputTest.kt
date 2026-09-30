package cz.p3kj.marp.export

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MarpCliOutputTest {

    @Test
    fun aShortTextIsKeptWhole() {
        val output = MarpCliOutput(limit = 10)
        output.append("abc")
        output.append("def")
        assertEquals("abcdef", output.toString())
    }

    @Test
    fun onlyTheLastCharactersAreKept() {
        val output = MarpCliOutput(limit = 10)
        output.append("0123456789")
        assertEquals("0123456789", output.toString())
        output.append("ab")
        assertEquals("23456789ab", output.toString())
        output.append("x".repeat(100) + "END")
        assertEquals("xxxxxxxEND", output.toString())
    }

    @Test
    fun threadsCanAppendAtTheSameTime() {
        val output = MarpCliOutput(limit = 1000)
        val pool = Executors.newFixedThreadPool(4)
        repeat(4) { pool.execute { repeat(2000) { output.append("ab") } } }
        pool.shutdown()
        assertEquals(true, pool.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals(1000, output.toString().length)
    }
}
