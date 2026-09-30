package cz.p3kj.marp.export

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import cz.p3kj.marp.settings.MarpHtmlMode
import cz.p3kj.marp.settings.MarpMathMode
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Where Marp CLI is, see [MarpCliLocator]. */
sealed interface MarpCliLocation {
    data class Found(val executable: Path) : MarpCliLocation

    /** [configured] is the path from the settings, `null` when the setting is empty and there is no `marp` on the PATH. */
    data class Missing(val configured: String?) : MarpCliLocation
}

/**
 * Finds the Marp CLI executable from the `marpCliPath` of [cz.p3kj.marp.settings.MarpAppSettings]: empty means `marp`
 * on the PATH, an absolute path is used as it is, anything else (`marp.cmd`) is a name to look up on the PATH. Touches
 * the file system, so call it off the EDT.
 */
object MarpCliLocator {

    private const val DEFAULT_NAME = "marp"

    /** [onPath] finds an executable by name on the PATH; replaced in tests. */
    fun locate(configured: String, onPath: (String) -> Path? = ::findOnPath): MarpCliLocation {
        val text = configured.trim()
        if (text.isEmpty()) return onPath(DEFAULT_NAME)?.let(MarpCliLocation::Found) ?: MarpCliLocation.Missing(null)
        val path = try {
            Path.of(text)
        } catch (_: InvalidPathException) {
            return MarpCliLocation.Missing(text)
        }
        if (path.isAbsolute) {
            return if (Files.isRegularFile(path)) MarpCliLocation.Found(path) else MarpCliLocation.Missing(text)
        }
        return onPath(text)?.let(MarpCliLocation::Found) ?: MarpCliLocation.Missing(text)
    }
}

/** Handles Windows: `marp` is found as `marp.cmd`. */
private fun findOnPath(name: String): Path? = PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS(name)?.toPath()

/** The command line and the configuration of a Marp CLI run. Pure, the process is started by [runMarpCli]. */
object MarpCliArgs {

    /**
     * The arguments after the executable. The config file goes first and `--` before the deck, so that a deck named like
     * an option is still a file. [output] is the file for PPTX and the name the images are numbered from.
     */
    fun arguments(format: MarpCliFormat, deck: Path, output: Path, config: Path): List<String> =
        listOf("--config-file", config.toString()) + format.cliArgs + listOf("-o", output.toString(), "--", deck.toString())

    /**
     * The JSON of the config file. Passing one stops the CLI from looking for the project's `.marprc*`,
     * `marp.config.*` and `package.json#marp`, which would add themes twice, run project scripts and differ from the
     * preview, and it is the only way to set the math library. [themeFiles] are the custom theme stylesheets, [html]
     * the effective mode (untrusted projects are already `OFF`), [allowLocalFiles] lets the browser read local images.
     */
    fun config(themeFiles: List<Path>, html: MarpHtmlMode, math: MarpMathMode, allowLocalFiles: Boolean): String {
        val json = JsonObject()
        if (themeFiles.isNotEmpty()) {
            json.add("themeSet", JsonArray().apply { themeFiles.forEach { add(it.toString()) } })
        }
        // Default: leave it out, the marp-core allowlist is the default of the preview as well.
        when (html) {
            MarpHtmlMode.ALL -> json.addProperty("html", true)
            MarpHtmlMode.OFF -> json.addProperty("html", false)
            MarpHtmlMode.DEFAULT -> {}
        }
        json.addProperty("allowLocalFiles", allowLocalFiles)
        val options = JsonObject()
        when (math) {
            MarpMathMode.MATHJAX -> options.addProperty("math", "mathjax")
            MarpMathMode.KATEX -> options.addProperty("math", "katex")
            MarpMathMode.OFF -> options.addProperty("math", false)
        }
        json.add("options", options)
        return json.toString()
    }

    private val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")
    private const val MAX_LINES = 15
    private const val MAX_CHARS = 1500

    /** The end of the CLI output for a notification: no colors, no blank lines, the last [MAX_LINES] lines, [MAX_CHARS] characters at most. */
    fun outputTail(text: String): String {
        val lines = ANSI.replace(text, "").lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        return lines.takeLast(MAX_LINES).joinToString("\n").takeLast(MAX_CHARS).trim()
    }
}
