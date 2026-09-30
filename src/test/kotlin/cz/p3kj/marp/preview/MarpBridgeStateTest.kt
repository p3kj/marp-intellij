package cz.p3kj.marp.preview

import cz.p3kj.marp.preview.MarpBridgeState.Companion.COMMANDS
import cz.p3kj.marp.preview.MarpBridgeState.Companion.EXPORT_HTML
import cz.p3kj.marp.preview.MarpBridgeState.Companion.FLUSH_RENDER
import cz.p3kj.marp.preview.MarpBridgeState.Companion.MAX_RELOADS
import cz.p3kj.marp.preview.MarpBridgeState.Companion.REPLAY_ORDER
import cz.p3kj.marp.preview.MarpBridgeState.Companion.SCROLL_TO_LINE
import cz.p3kj.marp.preview.MarpBridgeState.Companion.SET_ACTIVE_LINE
import cz.p3kj.marp.preview.MarpBridgeState.Companion.SET_IDE_THEME
import cz.p3kj.marp.preview.MarpBridgeState.Companion.SET_STRINGS
import cz.p3kj.marp.preview.MarpBridgeState.Companion.SET_THEMES
import cz.p3kj.marp.preview.MarpBridgeState.Companion.STABLE_MS
import cz.p3kj.marp.preview.MarpBridgeState.Companion.UPDATE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarpBridgeStateTest {

    private var now = 1_000L
    private val executed = mutableListOf<Pair<String, String>>()
    private val bridge = MarpBridgeState(now = { now }) { method, json -> executed += method to json }

    @Test
    fun callsBeforeReadyAreQueuedAndTheLatestArgumentWins() {
        bridge.call(UPDATE, "1")
        bridge.call(SCROLL_TO_LINE, "3")
        bridge.call(UPDATE, "2")
        assertEquals(emptyList<Pair<String, String>>(), executed)

        bridge.onReady()
        assertEquals(listOf(UPDATE to "2", SCROLL_TO_LINE to "3"), executed)
    }

    @Test
    fun replayFollowsTheContractOrderNotTheCallOrder() {
        for (method in REPLAY_ORDER.reversed()) bridge.call(method, "\"$method\"")
        bridge.onReady()
        assertEquals(REPLAY_ORDER, executed.map { it.first })
        assertEquals(listOf(SET_STRINGS, SET_IDE_THEME, SET_THEMES, UPDATE, SCROLL_TO_LINE, SET_ACTIVE_LINE), REPLAY_ORDER)
    }

    @Test
    fun callsWhileReadyExecuteImmediately() {
        bridge.onReady()
        assertTrue(bridge.isReady)
        bridge.call(SET_ACTIVE_LINE, "4")
        bridge.call(SET_ACTIVE_LINE, "5")
        assertEquals(listOf(SET_ACTIVE_LINE to "4", SET_ACTIVE_LINE to "5"), executed)
    }

    @Test
    fun unchangedArgumentsCanBeSkipped() {
        bridge.onReady()
        bridge.call(UPDATE, "u1", skipUnchanged = true)
        bridge.call(UPDATE, "u1", skipUnchanged = true)
        bridge.call(UPDATE, "u2", skipUnchanged = true)
        bridge.call(SCROLL_TO_LINE, "1")
        bridge.call(SCROLL_TO_LINE, "1")
        assertEquals(listOf(UPDATE to "u1", UPDATE to "u2", SCROLL_TO_LINE to "1", SCROLL_TO_LINE to "1"), executed)
    }

    @Test
    fun reloadResetsReadyAndReplaysTheLatestStateAgain() {
        bridge.call(SET_THEMES, "t1")
        bridge.call(UPDATE, "u1")
        bridge.call(SET_STRINGS, "s")
        bridge.onReady()
        executed.clear()

        bridge.onLoadStart()
        assertFalse(bridge.isReady)
        bridge.call(UPDATE, "u2")
        assertEquals("nothing reaches a page that is loading", emptyList<Pair<String, String>>(), executed)

        bridge.onReady()
        assertEquals("strings first on every ready", listOf(SET_STRINGS to "s", SET_THEMES to "t1", UPDATE to "u2"), executed)
    }

    @Test
    fun nothingExecutesAfterDispose() {
        bridge.call(UPDATE, "u1")
        bridge.onReady()
        executed.clear()

        bridge.dispose()
        bridge.call(UPDATE, "u2")
        bridge.onReady()
        assertFalse(bridge.isReady)
        assertFalse(bridge.onPageGone())
        assertEquals(emptyList<Pair<String, String>>(), executed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownMethodsAreRejected() {
        bridge.call("eval", "1")
    }

    @Test
    fun commandsAreRejectedBeforeReadyAndAfterAReload() {
        assertFalse(bridge.command(EXPORT_HTML, "{\"id\":1}"))
        bridge.onReady()
        bridge.onLoadStart()
        assertFalse(bridge.command(FLUSH_RENDER, "{\"id\":2}"))
        assertEquals(emptyList<Pair<String, String>>(), executed)
    }

    @Test
    fun commandsRunOnceWhileReadyAndAreNeverReplayed() {
        bridge.call(UPDATE, "u1")
        bridge.onReady()
        executed.clear()

        assertTrue(bridge.command(EXPORT_HTML, "{\"id\":1}"))
        assertTrue(bridge.command(FLUSH_RENDER, "{\"id\":2}"))
        assertEquals(listOf(EXPORT_HTML to "{\"id\":1}", FLUSH_RENDER to "{\"id\":2}"), executed)

        executed.clear()
        bridge.onLoadStart()
        bridge.onReady()
        assertEquals("only the state comes back after a reload", listOf(UPDATE to "u1"), executed)
    }

    @Test
    fun commandsKeepTheOrderOfTheCallsAroundThem() {
        bridge.onReady()
        bridge.call(UPDATE, "u1")
        bridge.command(FLUSH_RENDER, "1")
        bridge.call(UPDATE, "u2")
        bridge.command(EXPORT_HTML, "2")
        assertEquals(listOf(UPDATE to "u1", FLUSH_RENDER to "1", UPDATE to "u2", EXPORT_HTML to "2"), executed)
    }

    @Test
    fun noCommandExecutesAfterDispose() {
        bridge.onReady()
        bridge.dispose()
        assertFalse(bridge.command(EXPORT_HTML, "{}"))
        assertEquals(emptyList<Pair<String, String>>(), executed)
    }

    @Test
    fun commandsAreNotStateSetters() {
        assertEquals(setOf(EXPORT_HTML, FLUSH_RENDER), COMMANDS)
        assertTrue(COMMANDS.none { it in REPLAY_ORDER })
        assertEquals("exportHtml", EXPORT_HTML)
        assertEquals("flushRender", FLUSH_RENDER)
    }

    @Test(expected = IllegalArgumentException::class)
    fun stateSettersAreNotCommands() {
        bridge.onReady()
        bridge.command(UPDATE, "1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun commandsCannotBeRecordedAsState() {
        bridge.call(EXPORT_HTML, "1")
    }

    @Test
    fun crashReloadsAreCapped() {
        bridge.onReady()
        repeat(MAX_RELOADS) {
            assertTrue("reload #${it + 1}", bridge.onPageGone())
            assertFalse(bridge.isReady)
            // The reloaded page comes up and crashes again right away.
            bridge.onReady()
        }
        assertFalse(bridge.onPageGone())
    }

    @Test
    fun crashesDuringLoadCountTowardsTheCap() {
        bridge.onReady()
        now += STABLE_MS
        repeat(MAX_RELOADS) { assertTrue(bridge.onPageGone()) }
        now += STABLE_MS
        assertFalse("never ready again, so the old ready does not count as stable", bridge.onPageGone())
    }

    @Test
    fun aPageThatStayedUpStartsANewRowOfReloads() {
        bridge.onReady()
        repeat(MAX_RELOADS) {
            assertTrue(bridge.onPageGone())
            bridge.onReady()
        }
        now += STABLE_MS
        assertTrue(bridge.onPageGone())
    }
}
