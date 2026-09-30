package cz.p3kj.marp.preview

/**
 * An export step failed: the preview page answered a command with an error, did not answer in time, went away, or
 * Chromium could not print. The message is for the log, the export shows its own text to the user ([timedOut] picks it).
 */
internal class MarpExportException(message: String, val timedOut: Boolean = false) : Exception(message)
