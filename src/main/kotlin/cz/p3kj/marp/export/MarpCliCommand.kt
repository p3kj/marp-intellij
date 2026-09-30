package cz.p3kj.marp.export

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import cz.p3kj.marp.settings.MarpHtmlMode
import cz.p3kj.marp.settings.MarpMathMode
import java.io.File
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
 * on the PATH (`marp.cmd` on Windows), an absolute path is used as it is, anything else (`marp.cmd`) is a name to look
 * up on the PATH. Touches the file system, so call it off the EDT.
 */
object MarpCliLocator {

    private const val DEFAULT_NAME = "marp"

    /**
     * [onPath] finds an executable by name on the PATH, [windowsExtensions] are the `PATHEXT` entries on Windows and `null`
     * elsewhere; both are replaced in tests.
     */
    fun locate(
        configured: String,
        onPath: (String) -> Path? = ::findOnPath,
        windowsExtensions: List<String>? = pathExtensions(),
    ): MarpCliLocation {
        val text = configured.trim()
        if (text.isEmpty()) return onPath(DEFAULT_NAME)?.let(MarpCliLocation::Found) ?: MarpCliLocation.Missing(null)
        val path = try {
            Path.of(text)
        } catch (_: InvalidPathException) {
            return MarpCliLocation.Missing(text)
        }
        if (path.isAbsolute) return findAbsolute(path, windowsExtensions)?.let(MarpCliLocation::Found) ?: MarpCliLocation.Missing(text)
        return onPath(text)?.let(MarpCliLocation::Found) ?: MarpCliLocation.Missing(text)
    }

    /**
     * [path] when it is a file that can be started. On Windows that needs a `PATHEXT` extension, so `node_modules/.bin/marp`
     * (the shell script npm writes) stands for its sibling `marp.cmd`.
     */
    private fun findAbsolute(path: Path, windowsExtensions: List<String>?): Path? {
        if (windowsExtensions == null) return path.takeIf { Files.isRegularFile(it) }
        val name = path.fileName?.toString() ?: return null
        if (windowsExtensions.any { name.endsWith(it, ignoreCase = true) }) return path.takeIf { Files.isRegularFile(it) }
        return windowsExtensions.map { path.resolveSibling(name + it) }.firstOrNull { Files.isRegularFile(it) }
    }

    /**
     * The first file [name] names in a directory of [pathVariable] (the value of the PATH), `null` when there is none.
     * Only absolute directories count: a relative entry (`.`, an empty entry) would make the result depend on the working
     * directory of the IDE. [windowsExtensions] are the `PATHEXT` entries on Windows, `null` elsewhere. Windows starts only
     * files with such an extension, so `marp` is found as `marp.cmd` and the extensionless shell script npm writes next to
     * it is skipped; elsewhere the file must be executable. (`PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS` does
     * this too, but it is scheduled for removal.)
     */
    fun findExecutable(name: String, pathVariable: String?, windowsExtensions: List<String>?): Path? {
        val candidates = when {
            windowsExtensions == null -> listOf(name)
            windowsExtensions.any { name.endsWith(it, ignoreCase = true) } -> listOf(name)
            else -> windowsExtensions.map { name + it }
        }
        for (entry in pathVariable.orEmpty().split(File.pathSeparatorChar)) {
            val text = entry.trim().trim('"').trim()
            if (text.isEmpty()) continue
            val directory = try {
                Path.of(text)
            } catch (_: InvalidPathException) {
                continue
            }
            if (!directory.isAbsolute) continue
            for (candidate in candidates) {
                val file = try {
                    directory.resolve(candidate)
                } catch (_: InvalidPathException) {
                    continue
                }
                if (Files.isRegularFile(file) && (windowsExtensions != null || Files.isExecutable(file))) return file
            }
        }
        return null
    }
}

/** [MarpCliLocator.findExecutable] on the PATH the IDE gives to the processes it starts (the shell environment on macOS). */
private fun findOnPath(name: String): Path? = MarpCliLocator.findExecutable(name, EnvironmentUtil.getValue("PATH"), pathExtensions())

/** The `PATHEXT` entries (`.CMD`, ...) on Windows, `null` on other systems. */
private fun pathExtensions(): List<String>? =
    if (SystemInfo.isWindows) (EnvironmentUtil.getValue("PATHEXT") ?: DEFAULT_PATHEXT).split(';').map { it.trim() }.filter { it.isNotEmpty() } else null

private const val DEFAULT_PATHEXT = ".COM;.EXE;.BAT;.CMD"

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

    private fun lines(text: String): List<String> = ANSI.replace(text, "").lines().map { it.trimEnd() }.filter { it.isNotBlank() }

    /** The end of the CLI output for a notification: no colors, no blank lines, the last [MAX_LINES] lines, [MAX_CHARS] characters at most. */
    fun outputTail(text: String): String = lines(text).takeLast(MAX_LINES).joinToString("\n").takeLast(MAX_CHARS).trim()

    /** The first line of [text] that has anything on it, without colors; `null` when there is none. */
    fun firstLine(text: String): String? = lines(text).firstOrNull()?.trim()
}
