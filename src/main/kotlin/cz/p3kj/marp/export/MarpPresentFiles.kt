package cz.p3kj.marp.export

import java.nio.file.Files
import java.nio.file.Path

/**
 * The file side of Present Deck: where the presentation page is written, what its base URL is and how the page of Marp
 * CLI is made to start at a slide. Pure JDK, no platform.
 */
internal object MarpPresentFiles {

    /** The `<base href>` for a presentation of a deck in [directory]: the `file:` URL of the folder, always ending in `/`. */
    fun baseHref(directory: Path): String {
        val uri = directory.toUri().toString()
        return if (uri.endsWith("/")) uri else "$uri/"
    }

    /**
     * Creates the temporary file of a presentation: empty, readable by its owner only on POSIX file systems, deleted when
     * the IDE exits. Present Deck uses a new file every time and keeps the earlier ones, so that a browser tab that is
     * still open keeps working. Blocking I/O, call it off the EDT.
     */
    fun newFile(): Path {
        val file = Files.createTempFile("marp-present-", ".html")
        file.toFile().deleteOnExit()
        return file
    }

    /** Writes [html] (UTF-8) to a [newFile] and returns it. Blocking I/O, call it off the EDT. */
    fun write(html: String): Path = Files.writeString(newFile(), html)

    private val HEAD = Regex("<head(\\s[^>]*)?>", RegexOption.IGNORE_CASE)

    /**
     * The page Marp CLI wrote (its `bespoke` template) made ready to open from the temporary folder: right after the
     * first `<head>` tag it gets a `<base href="[baseHref]">`, so that relative images and theme `url()`s resolve from
     * the folder of the deck like they do for the CLI itself, and a script that puts `#N` (the slide [start], zero-based,
     * the first slide for a negative number) into the address when there is none, which the template reads at startup.
     * The fragment cannot be part of the URL that is opened, browsers drop it for `file:` URLs on Windows and macOS. The
     * address is an absolute one, a relative `#N` would resolve against the base and leave the page. Links to `#N` are
     * followed by hand for the same reason. Returns [html] unchanged when it has no `<head>` tag, the deck then starts at
     * the first slide and relative files are not found.
     */
    fun cliPresentation(html: String, baseHref: String, start: Int): String {
        val head = HEAD.find(html) ?: return html
        val slide = if (start < 0) 1 else start + 1
        val base = baseHref.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
        val script = "if(!location.hash)history.replaceState(null,\"\",location.href.split(\"#\")[0]+\"#$slide\");" +
            "document.addEventListener(\"click\",function(e){var a=e.target&&e.target.closest&&e.target.closest('a[href^=\"#\"]');" +
            "if(a){e.preventDefault();location.hash=a.getAttribute(\"href\")}},true);"
        return html.substring(0, head.range.last + 1) + "<base href=\"$base\"><script>$script</script>" + html.substring(head.range.last + 1)
    }
}
