package cz.p3kj.marp.export

import cz.p3kj.marp.MarpBundle
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.MarpBundle"

/**
 * What a deck can be exported to by Marp CLI, see [MarpCliExporter]. HTML and PDF are not here: the preview page does
 * them without an external process, see [MarpExportFormat].
 */
enum class MarpCliFormat(
    override val extension: String,
    /** The format options of the CLI, before `-o`. */
    val cliArgs: List<String>,
    @PropertyKey(resourceBundle = BUNDLE) private val dialogTitleKey: String,
) : MarpExportTarget {
    PPTX("pptx", listOf("--pptx"), "export.dialog.title.pptx"),
    PNG("png", listOf("--images", "png"), "export.dialog.title.png"),
    JPEG("jpg", listOf("--images", "jpeg"), "export.dialog.title.jpeg");

    override val dialogTitle: String get() = MarpBundle.message(dialogTitleKey)

    /** One file per slide (`deck.001.png`, ...) next to the chosen name instead of the chosen file itself. */
    val isImages: Boolean get() = this != PPTX
}
