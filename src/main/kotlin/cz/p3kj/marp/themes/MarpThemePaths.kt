package cz.p3kj.marp.themes

import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Helpers for theme entries as they are stored in settings: URLs, project-relative and absolute paths. */
object MarpThemePaths {
    private val schemeRegex = Regex("^[a-zA-Z][a-zA-Z0-9+.-]+://")

    /** True for anything that looks like `scheme://...` (a drive letter such as `C:\` is not a scheme). */
    fun hasScheme(entry: String): Boolean = schemeRegex.containsMatchIn(entry)

    fun isHttpUrl(entry: String): Boolean =
        entry.startsWith("http://", ignoreCase = true) || entry.startsWith("https://", ignoreCase = true)

    /** Resolves a stored entry against [baseDir]. Accepts `x.css`, `./x.css` and absolute paths. */
    fun resolve(baseDir: Path, entry: String): Path? = try {
        val path = Path.of(entry.trim())
        (if (path.isAbsolute) path else baseDir.resolve(path)).normalize()
    } catch (_: InvalidPathException) {
        null
    }

    /** Entry text for settings: project-relative with `/` separators when inside [baseDir], otherwise absolute. */
    fun toStored(path: Path, baseDir: Path?): String {
        val normalized = path.toAbsolutePath().normalize()
        if (baseDir != null) {
            val base = baseDir.toAbsolutePath().normalize()
            if (normalized != base && normalized.startsWith(base)) {
                return base.relativize(normalized).toString().replace('\\', '/')
            }
        }
        return normalized.toString().replace('\\', '/')
    }

    /** Name shown to the user and sent to the preview: project-relative when inside [baseDir]. */
    fun display(path: Path, baseDir: Path?): String = toStored(path, baseDir)

    /** Stable key for deduplication and watch matching, `/` separated. */
    fun key(path: Path): String {
        val real = try {
            path.toRealPath()
        } catch (_: Exception) {
            path.toAbsolutePath().normalize()
        }
        return real.toString().replace('\\', '/')
    }

    fun normalizedKey(path: Path): String = path.toAbsolutePath().normalize().toString().replace('\\', '/')
}
