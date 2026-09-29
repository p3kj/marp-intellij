package cz.p3kj.marp.preview

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * URL <-> path mapping, access guard and MIME types for the `https://marp.localhost` origin served inside JCEF.
 * Pure functions, no IntelliJ services, so it can be unit-tested directly.
 */
object MarpResourcePaths {
    const val HOST: String = "marp.localhost"
    const val ORIGIN: String = "https://$HOST"
    const val APP_URL_PREFIX: String = "$ORIGIN/app/"
    const val DOC_URL_PREFIX: String = "$ORIGIN/doc/"
    const val APP_INDEX_URL: String = "${APP_URL_PREFIX}index.html"

    private const val APP_PATH_PREFIX = "/app/"
    private const val DOC_PATH_PREFIX = "/doc/"
    private const val CLASSPATH_ROOT = "/webview/"
    private const val HEX = "0123456789ABCDEF"

    /** What a request URL points at. */
    sealed interface Target {
        /** A plugin resource; [resourceName] is an absolute classpath name like `/webview/index.html`. */
        data class App(val resourceName: String) : Target

        /** A local file (absolute, not yet checked against the allowed roots). */
        data class Doc(val path: Path) : Target
    }

    /** `true` for any `https://marp.localhost/...` URL. */
    fun isMarpUrl(url: String?): Boolean = url != null && url.startsWith("$ORIGIN/", ignoreCase = true)

    /** Classifies [url]; `null` when it is not ours or is malformed (answered with 404). */
    fun parse(url: String): Target? {
        val uri = try {
            URI(url)
        }
        catch (_: URISyntaxException) {
            return null
        }
        if (!"https".equals(uri.scheme, ignoreCase = true) || !HOST.equals(uri.host, ignoreCase = true)) return null
        if (uri.port != -1 && uri.port != 443) return null
        val rawPath = uri.rawPath ?: return null
        return when {
            rawPath.startsWith(APP_PATH_PREFIX) -> appResourceName(rawPath.substring(APP_PATH_PREFIX.length))?.let { Target.App(it) }
            rawPath.startsWith(DOC_PATH_PREFIX) -> docPath(rawPath.substring(DOC_PATH_PREFIX.length))?.let { Target.Doc(it) }
            else -> null
        }
    }

    /**
     * `https://marp.localhost/doc/<path>` for a system-independent absolute path (`/home/me/deck` or `C:/Users/me/deck`),
     * with a trailing slash when [directory] is `true`.
     */
    fun docUrl(systemIndependentPath: String, directory: Boolean): String {
        val segments = systemIndependentPath.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return DOC_URL_PREFIX
        val joined = segments.joinToString("/") { encodeSegment(it) }
        return DOC_URL_PREFIX + joined + if (directory) "/" else ""
    }

    /** Classpath resource for the part of an `/app/` URL path after the prefix, or `null` when it is not a safe name. */
    fun appResourceName(rawRelativePath: String): String? {
        val segments = decodeSegments(rawRelativePath) ?: return null
        if (segments.isEmpty()) return null
        return CLASSPATH_ROOT + segments.joinToString("/")
    }

    /**
     * Absolute path for the part of a `/doc/` URL path after the prefix. Rejects `.` / `..` segments, encoded separators
     * and NUL; Windows drive paths look like `C:/Users/...`. Does not touch the file system.
     */
    fun docPath(rawRelativePath: String): Path? {
        val segments = decodeSegments(rawRelativePath) ?: return null
        if (segments.isEmpty()) return null
        val first = segments.first()
        val joined = if (isDriveSegment(first)) segments.joinToString("/") else "/" + segments.joinToString("/")
        val path = try {
            Path.of(joined)
        }
        catch (_: InvalidPathException) {
            return null
        }
        return path.takeIf { it.isAbsolute }
    }

    /**
     * The real path of [path] when it is an existing regular file inside one of [roots] (compared by real path, so
     * symlinks cannot escape and `/project-evil` is not inside `/project`); otherwise `null`. Does file I/O.
     */
    fun resolveAllowedFile(path: Path, roots: Collection<Path>): Path? {
        val real = try {
            path.toRealPath()
        }
        catch (_: IOException) {
            return null
        }
        catch (_: SecurityException) {
            return null
        }
        if (!Files.isRegularFile(real)) return null
        return real.takeIf { isInside(it, roots) }
    }

    /** `true` when the (already real) [file] is inside one of [roots]. */
    fun isInside(file: Path, roots: Collection<Path>): Boolean = roots.any { root ->
        val realRoot = try {
            root.toRealPath()
        }
        catch (_: IOException) {
            return@any false
        }
        catch (_: SecurityException) {
            return@any false
        }
        file.startsWith(realRoot)
    }

    /** MIME type by file extension, `null` for types the preview does not serve. */
    fun mimeType(fileName: String): String? {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return MIME_TYPES[extension]
    }

    private val MIME_TYPES: Map<String, String> = mapOf(
        "html" to "text/html",
        "htm" to "text/html",
        "js" to "text/javascript",
        "mjs" to "text/javascript",
        "css" to "text/css",
        "json" to "application/json",
        "txt" to "text/plain",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "avif" to "image/avif",
        "svg" to "image/svg+xml",
        "ico" to "image/x-icon",
        "bmp" to "image/bmp",
        "woff" to "font/woff",
        "woff2" to "font/woff2",
        "ttf" to "font/ttf",
        "otf" to "font/otf",
        "mp4" to "video/mp4",
        "webm" to "video/webm",
        "ogg" to "audio/ogg",
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
    )

    private fun isDriveSegment(segment: String): Boolean =
        segment.length == 2 && segment[1] == ':' && segment[0].let { it in 'A'..'Z' || it in 'a'..'z' }

    /** Splits a raw (percent-encoded) path on `/` and decodes every segment; `null` if any segment is unsafe. */
    private fun decodeSegments(rawPath: String): List<String>? {
        val result = ArrayList<String>()
        for (raw in rawPath.split('/')) {
            if (raw.isEmpty()) continue
            val segment = percentDecode(raw) ?: return null
            if (segment == "." || segment == ".." || segment.any { it == '/' || it == '\\' || it == '\u0000' }) return null
            result += segment
        }
        return result
    }

    /** RFC 3986 percent-decoding as UTF-8 (`+` stays `+`); `null` for malformed escapes or invalid UTF-8. */
    fun percentDecode(value: String): String? {
        if (value.indexOf('%') < 0) return value
        val bytes = ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%') {
                if (i + 2 >= value.length) return null
                val hi = Character.digit(value[i + 1], 16)
                val lo = Character.digit(value[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                bytes.write(hi * 16 + lo)
                i += 3
            }
            else {
                val end = value.indexOf('%', i).let { if (it < 0) value.length else it }
                val encoded = value.substring(i, end).toByteArray(Charsets.UTF_8)
                bytes.write(encoded, 0, encoded.size)
                i = end
            }
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString()
        }
        catch (_: CharacterCodingException) {
            null
        }
    }

    /** Percent-encodes one path segment, keeping RFC 3986 unreserved characters and `:` `@` `!` `$` `&` `'` `(` `)` `*` `+` `,` `;` `=`. */
    fun encodeSegment(segment: String): String {
        val out = StringBuilder(segment.length)
        for (byte in segment.toByteArray(Charsets.UTF_8)) {
            val b = byte.toInt() and 0xFF
            val c = b.toChar()
            if (b < 0x80 && (c.isLetterOrDigit() || c in "-._~:@!$&'()*+,;=")) {
                out.append(c)
            }
            else {
                out.append('%').append(HEX[b shr 4]).append(HEX[b and 0x0F])
            }
        }
        return out.toString()
    }
}
