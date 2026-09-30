package cz.p3kj.marp.export

import cz.p3kj.marp.MarpBundle
import org.jetbrains.annotations.PropertyKey
import java.nio.file.Path

private const val BUNDLE = "messages.MarpBundle"

/** For a Markdown file that is nothing but an extension (`.md`). */
private const val DEFAULT_BASE_NAME = "deck"

/** A file a deck can be exported to: what the save dialog needs to know about it. */
interface MarpExportTarget {
    /** File extension without the dot, also what the save dialog filters on. */
    val extension: String

    /** Title of the save dialog. */
    val dialogTitle: String

    /** `deck.md` -> `deck.html`. The extension of the Markdown file is replaced, a name without one gets the new one. */
    fun defaultName(markdownName: String): String {
        val base = markdownName.substringBeforeLast('.', markdownName).ifEmpty { DEFAULT_BASE_NAME }
        return "$base.$extension"
    }

    /** [path] as it is when its name already ends in the [extension] (any case), otherwise with the extension appended. */
    fun withExtension(path: Path): Path {
        val name = path.fileName?.toString() ?: return path
        return if (name.endsWith(".$extension", ignoreCase = true)) path else path.resolveSibling("$name.$extension")
    }
}

/** What a deck can be exported to by the live preview page: HTML and PDF, see `docs/ARCHITECTURE.md`. Marp CLI formats are [MarpCliFormat]. */
enum class MarpExportFormat(
    override val extension: String,
    @PropertyKey(resourceBundle = BUNDLE) private val dialogTitleKey: String,
) : MarpExportTarget {
    HTML("html", "export.dialog.title.html"),
    PDF("pdf", "export.dialog.title.pdf");

    override val dialogTitle: String get() = MarpBundle.message(dialogTitleKey)
}
