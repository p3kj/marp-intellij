package cz.p3kj.marp.preview

import com.google.gson.JsonObject

/**
 * The `present` argument of the `exportHtml` command, see `docs/ARCHITECTURE.md`: the export document becomes a
 * presentation that shows one slide at a time.
 */
data class MarpPresentOptions(
    /** `file:` URL of the folder of the deck with a trailing `/`, or `null` when the deck is not on the local file system. */
    val baseHref: String?,
    /** Zero-based slide the presentation starts at. */
    val start: Int,
) {
    fun toJson(): JsonObject = JsonObject().apply {
        baseHref?.let { addProperty("baseHref", it) }
        addProperty("start", start)
    }
}
