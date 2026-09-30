package cz.p3kj.marp.export

import java.nio.file.Files
import java.nio.file.Path

/** The file side of Present Deck: where the presentation page is written and what its base URL is. Pure JDK, no platform. */
internal object MarpPresentFiles {

    /** The `<base href>` for a presentation of a deck in [directory]: the `file:` URL of the folder, always ending in `/`. */
    fun baseHref(directory: Path): String {
        val uri = directory.toUri().toString()
        return if (uri.endsWith("/")) uri else "$uri/"
    }

    /**
     * Writes [html] (UTF-8) to a new temporary file and returns it. The JDK creates it readable by its owner only on POSIX
     * file systems, and it is deleted when the IDE exits. Present Deck writes a new file every time and keeps the earlier
     * ones, so that a browser tab that is still open keeps working. Blocking I/O, call it off the EDT.
     */
    fun write(html: String): Path {
        val file = Files.createTempFile("marp-present-", ".html")
        file.toFile().deleteOnExit()
        return Files.writeString(file, html)
    }
}
