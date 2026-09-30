package cz.p3kj.marp.preview

/**
 * An export step failed: the preview page answered a command with an error, did not answer in time, went away, or
 * Chromium could not print. The message is for the log, the export shows its own text to the user: [timedOut] and
 * [pageGone] pick theirs.
 *
 * @property timedOut the page or the browser did not answer within the time allowed
 * @property pageGone the preview was closed, reloaded or was never ready, so there was nothing to export from
 */
internal class MarpExportException(
    message: String,
    val timedOut: Boolean = false,
    val pageGone: Boolean = false,
) : Exception(message)
