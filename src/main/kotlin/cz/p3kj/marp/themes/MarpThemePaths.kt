package cz.p3kj.marp.themes

import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Helpers for theme entries as they are stored in settings: URLs, project-relative and absolute paths. */
object MarpThemePaths {
    private val schemeRegex = Regex("^[a-zA-Z][a-zA-Z0-9+.-]+://")

    /** True for anything that looks like `scheme://...` (a drive letter such as `C:\` is not a scheme). */
    fun hasScheme(entry: String): Boolean = schemeRegex.containsMatchIn(entry)

    fun isHttpUrl(entry: String): Boolean =
        entry.startsWith("http://", ignoreCase = true) || entry.startsWith("https://", ignoreCase = true)

    /**
     * Resolves a stored entry against [baseDir]. Accepts `x.css`, `./x.css` and absolute paths; `null` for an invalid
     * path, and for a relative one when there is no [baseDir] (it would otherwise resolve against the IDE's working
     * directory).
     */
    fun resolve(baseDir: Path?, entry: String): Path? = try {
        val path = Path.of(entry.trim())
        when {
            path.isAbsolute -> path.normalize()
            baseDir != null -> baseDir.resolve(path).normalize()
            else -> null
        }
    } catch (_: InvalidPathException) {
        null
    }

    /**
     * Entry text for settings, and the name shown to the user and sent to the preview: project-relative with `/`
     * separators when inside [baseDir], otherwise absolute.
     */
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

    /**
     * `true` when [path] is [dir] or inside it: lexically, and by real path once it exists, so a symlink cannot lead
     * out of [dir]. A path that does not exist yet passes when it is lexically inside; it is checked again when it
     * appears. Does file I/O.
     */
    fun isConfinedTo(path: Path, dir: Path): Boolean {
        val normalizedDir = dir.toAbsolutePath().normalize()
        val normalized = path.toAbsolutePath().normalize()
        if (!normalized.startsWith(normalizedDir)) return false
        if (!Files.exists(normalized)) return true
        return try {
            normalized.toRealPath().startsWith(normalizedDir.toRealPath())
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

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
