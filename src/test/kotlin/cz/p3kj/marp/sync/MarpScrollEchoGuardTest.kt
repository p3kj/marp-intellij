package cz.p3kj.marp.sync

import cz.p3kj.marp.sync.MarpScrollEchoGuard.Companion.EDITOR_ECHO_MS
import cz.p3kj.marp.sync.MarpScrollEchoGuard.Companion.PREVIEW_ECHO_MS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarpScrollEchoGuardTest {

    private var now = 10_000L
    private val guard = MarpScrollEchoGuard { now }

    @Test
    fun acceptsEverythingInitially() {
        assertTrue(guard.acceptEditorScroll())
        assertTrue(guard.acceptPreviewScroll())
        assertTrue(guard.beforeSendToPreview(0.0, force = false))
    }

    @Test
    fun previewEchoOfScrollToLineIsIgnored() {
        assertTrue(guard.beforeSendToPreview(12.5, force = false))
        assertFalse(guard.acceptPreviewScroll())
        now += PREVIEW_ECHO_MS - 1
        assertFalse(guard.acceptPreviewScroll())
        now += 1
        assertTrue(guard.acceptPreviewScroll())
    }

    @Test
    fun editorEchoOfRevealIsIgnored() {
        var duringReveal: Boolean? = null
        guard.reveal {
            duringReveal = guard.acceptEditorScroll()
            7.0
        }
        assertFalse("scroll events caused by the reveal itself", duringReveal!!)
        assertFalse(guard.acceptEditorScroll())
        now += EDITOR_ECHO_MS
        assertTrue(guard.acceptEditorScroll())
        // The preview is already at the line the editor ended up on, so it is not sent back.
        assertFalse(guard.beforeSendToPreview(7.0, force = false))
        assertTrue(guard.beforeSendToPreview(7.0, force = true))
    }

    @Test
    fun revealEndsSuppressionEvenWhenScrollingFails() {
        try {
            guard.reveal { error("boom") }
        }
        catch (_: IllegalStateException) {
        }
        now += EDITOR_ECHO_MS
        assertTrue(guard.acceptEditorScroll())
    }

    @Test
    fun unchangedLinesAreNotResent() {
        assertTrue(guard.beforeSendToPreview(3.25, force = false))
        assertFalse(guard.beforeSendToPreview(3.2501, force = false))
        assertTrue(guard.beforeSendToPreview(3.26, force = false))
        assertTrue(guard.beforeSendToPreview(3.26, force = true))
    }
}
