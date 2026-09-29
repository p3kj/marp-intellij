package cz.p3kj.marp.sync

import kotlin.math.abs

/**
 * Echo suppression for [MarpScrollSync]: a programmatic scroll of one side must not bounce back from the other.
 * - editor scroll events are ignored while, and [EDITOR_ECHO_MS] after, the editor is scrolled for the preview;
 * - `revealLine` from the preview is ignored for [PREVIEW_ECHO_MS] after a `scrollToLine` was sent to it;
 * - a `scrollToLine` that would repeat the last known line (within [LINE_EPSILON]) is not sent unless forced.
 *
 * Not thread-safe, used on the EDT only. [now] is monotonic milliseconds.
 */
internal class MarpScrollEchoGuard(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {

    private var revealing = false
    private var lastRevealAt = NEVER
    private var lastScrollSentAt = NEVER
    private var lastSentLine = Double.NaN

    /** The editor scrolled: `false` when that is the echo of [reveal]. */
    fun acceptEditorScroll(): Boolean = !revealing && elapsedSince(lastRevealAt) >= EDITOR_ECHO_MS

    /** Whether `scrollToLine(line)` should be sent to the preview; records the send when it should. */
    fun beforeSendToPreview(line: Double, force: Boolean): Boolean {
        if (!force && abs(line - lastSentLine) < LINE_EPSILON) return false
        lastSentLine = line
        lastScrollSentAt = now()
        return true
    }

    /** The preview scrolled (`revealLine`): `false` when that is the echo of the last `scrollToLine`. */
    fun acceptPreviewScroll(): Boolean = elapsedSince(lastScrollSentAt) >= PREVIEW_ECHO_MS

    /**
     * Runs [scrollEditor], which scrolls the editor for the preview and returns the line now at the top of the editor,
     * with editor scroll events suppressed. That line counts as known to the preview.
     */
    fun reveal(scrollEditor: () -> Double) {
        revealing = true
        val line = try {
            scrollEditor()
        }
        finally {
            revealing = false
        }
        lastRevealAt = now()
        lastSentLine = line
    }

    private fun elapsedSince(time: Long): Long = if (time == NEVER) Long.MAX_VALUE else now() - time

    companion object {
        /** Ignore editor scroll events this long after a preview-driven scroll. */
        const val EDITOR_ECHO_MS: Long = 150L

        /** Ignore `revealLine` this long after sending `scrollToLine`. */
        const val PREVIEW_ECHO_MS: Long = 400L

        const val LINE_EPSILON: Double = 0.001

        private const val NEVER = Long.MIN_VALUE
    }
}
