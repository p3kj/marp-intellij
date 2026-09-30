package cz.p3kj.marp.export

import cz.p3kj.marp.MarpBundle
import org.jetbrains.annotations.PropertyKey
import java.nio.file.Path

private const val BUNDLE = "messages.MarpBundle"

/** What a deck can be exported to. Both go through the live preview page, see `docs/ARCHITECTURE.md`. */
enum class MarpExportFormat(
    /** File extension without the dot, also what the save dialog filters on. */
    val extension: String,
    @PropertyKey(resourceBundle = BUNDLE) private val dialogTitleKey: String,
) {
    HTML("html", "export.dialog.title.html"),
    PDF("pdf", "export.dialog.title.pdf");

    val dialogTitle: String get() = MarpBundle.message(dialogTitleKey)

    /** `deck.md` -> `deck.html`. The extension of the Markdown file is replaced, a name without one gets the new one. */
    fun defaultName(markdownName: String): String {
        val base = markdownName.substringBeforeLast('.', markdownName).ifEmpty { DEFAULT_BASE_NAME }
        return "$base.$extension"
    }

    /** [path] as it is when its name already ends in `.html` / `.pdf` (any case), otherwise with the extension appended. */
    fun withExtension(path: Path): Path {
        val name = path.fileName?.toString() ?: return path
        return if (name.endsWith(".$extension", ignoreCase = true)) path else path.resolveSibling("$name.$extension")
    }

    private companion object {
        /** For a Markdown file that is nothing but an extension (`.md`). */
        const val DEFAULT_BASE_NAME = "deck"
    }
}
