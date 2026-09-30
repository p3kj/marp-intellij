package cz.p3kj.marp.preview

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MarpPageRepliesTest {

    private val replies = MarpPageReplies()

    private fun json(text: String): JsonObject = JsonParser.parseString(text).asJsonObject

    @Test
    fun everyCommandGetsItsOwnId() {
        val ids = List(50) { replies.open().first }
        assertEquals(50, ids.toSet().size)
    }

    @Test
    fun aReplyCompletesTheCommandWithTheSameId() {
        val (id, first) = replies.open()
        val (otherId, second) = replies.open()
        assertNotEquals(id, otherId)

        replies.onReply(json("""{"type":"reply","id":$otherId,"html":"<p>two</p>"}"""))
        assertFalse(first.isCompleted)
        assertTrue(second.isCompleted)
        assertEquals("<p>two</p>", runBlocking { second.await() }.get("html").asString)

        replies.onReply(json("""{"type":"reply","id":$id}"""))
        assertTrue(first.isCompleted)
        assertEquals(id, runBlocking { first.await() }.get("id").asInt)
    }

    @Test
    fun anErrorReplyFailsTheCommandWithTheMessage() {
        val (id, reply) = replies.open()
        replies.onReply(json("""{"type":"reply","id":$id,"error":"boom"}"""))
        assertTrue(reply.isCompleted)
        try {
            runBlocking { reply.await() }
            fail("expected a failure")
        }
        catch (e: MarpExportException) {
            assertEquals("boom", e.message)
            assertFalse(e.timedOut)
        }
    }

    @Test
    fun aSecondReplyIsIgnored() {
        val (id, reply) = replies.open()
        replies.onReply(json("""{"type":"reply","id":$id,"html":"first"}"""))
        replies.onReply(json("""{"type":"reply","id":$id,"html":"second"}"""))
        assertEquals("first", runBlocking { reply.await() }.get("html").asString)
    }

    @Test
    fun unknownOrMissingIdsAreIgnored() {
        val (_, reply) = replies.open()
        replies.onReply(json("""{"type":"reply","id":9999,"html":"x"}"""))
        replies.onReply(json("""{"type":"reply","html":"x"}"""))
        replies.onReply(json("""{"type":"reply","id":"1","html":"x"}"""))
        replies.onReply(json("""{"type":"reply","id":null}"""))
        assertFalse(reply.isCompleted)
    }

    @Test
    fun aReplyWithANonTextErrorCountsAsSuccess() {
        val (id, reply) = replies.open()
        replies.onReply(json("""{"type":"reply","id":$id,"error":null}"""))
        assertTrue(reply.isCompleted)
        assertEquals(id, runBlocking { reply.await() }.get("id").asInt)
    }

    @Test
    fun failAllFailsEveryWaitingCommandOnly() {
        val (doneId, done) = replies.open()
        val (_, waiting1) = replies.open()
        val (_, waiting2) = replies.open()
        replies.onReply(json("""{"type":"reply","id":$doneId}"""))

        replies.failAll("page is gone")

        assertTrue(done.isCompleted)
        assertFalse(done.getCompletionExceptionOrNull() != null)
        for (waiting in listOf(waiting1, waiting2)) {
            assertEquals("page is gone", waiting.getCompletionExceptionOrNull()?.message)
            assertTrue(waiting.getCompletionExceptionOrNull() is MarpExportException)
        }
    }

    @Test
    fun commandsOpenedAfterFailAllWorkAgain() {
        replies.failAll("gone")
        val (id, reply) = replies.open()
        replies.onReply(json("""{"type":"reply","id":$id}"""))
        assertTrue(reply.isCompleted)
        assertEquals(null, reply.getCompletionExceptionOrNull())
    }

    @Test
    fun cancelForgetsTheCommandAndIgnoresALateReply() {
        val (id, reply) = replies.open()
        replies.cancel(id)
        assertTrue(reply.isCancelled)
        replies.onReply(json("""{"type":"reply","id":$id,"html":"late"}"""))
        assertTrue(reply.isCancelled)

        replies.cancel(id)
        replies.cancel(12345)
    }
}
