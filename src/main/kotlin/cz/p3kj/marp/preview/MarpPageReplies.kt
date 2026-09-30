package cz.p3kj.marp.preview

import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Matches the `reply` messages of the preview page with the commands that are waiting for them (see the Commands
 * section of `docs/ARCHITECTURE.md`). Thread-safe: replies arrive on a JCEF thread.
 */
internal class MarpPageReplies {
    private val nextId = AtomicInteger()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()

    /** A new command id and the reply to wait for. Pass the id to the page. */
    fun open(): Pair<Int, Deferred<JsonObject>> {
        val id = nextId.incrementAndGet()
        val reply = CompletableDeferred<JsonObject>()
        pending[id] = reply
        return id to reply
    }

    /**
     * A `reply` message arrived: completes the command it belongs to, with a [MarpExportException] when it carries an
     * `error`. Unknown ids (already answered, cancelled, or failed by [failAll]) and messages without an id are ignored.
     */
    fun onReply(json: JsonObject) {
        val idElement = json.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber } ?: return
        val reply = pending.remove(idElement.asInt) ?: return
        val error = json.get("error")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        if (error != null) reply.completeExceptionally(MarpExportException(error)) else reply.complete(json)
    }

    /**
     * Completes [id] like a reply would, for work that the page does not answer by message (the PDF print callback of
     * the browser). With an [error] it fails like an error reply.
     */
    fun complete(id: Int, error: String? = null) {
        onReply(JsonObject().apply {
            addProperty("id", id)
            if (error != null) addProperty("error", error)
        })
    }

    /** Forgets the command, for example after a timeout. A late reply is ignored. */
    fun cancel(id: Int) {
        pending.remove(id)?.cancel()
    }

    /** The page is gone (reload, crash, dispose): every command that still waits fails with [reason] and `pageGone` set. */
    fun failAll(reason: String) {
        for (id in pending.keys.toList()) {
            pending.remove(id)?.completeExceptionally(MarpExportException(reason, pageGone = true))
        }
    }
}
