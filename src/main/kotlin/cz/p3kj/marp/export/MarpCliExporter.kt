package cz.p3kj.marp.export

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.ide.BrowserUtil
import com.intellij.ide.actions.RevealFileAction
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import cz.p3kj.marp.MarpBundle
import cz.p3kj.marp.editor.MarpPreviewFileEditor
import cz.p3kj.marp.editor.MarpProjectScope
import cz.p3kj.marp.settings.MarpAppSettings
import cz.p3kj.marp.settings.MarpSettings
import cz.p3kj.marp.themes.MarpThemeService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

private val LOG = logger<MarpCliExporter>()

/**
 * Exports the deck to PPTX or images with Marp CLI, see the Export section of `docs/ARCHITECTURE.md`. The preview page
 * is not involved: the CLI reads the deck and the theme files from disk, renders them with its own marp-core and, for
 * PPTX and images, a headless browser. The themes, HTML mode, math library and local file access of the project are
 * handed over through a temporary config file, so the result follows the settings like the preview does.
 *
 * Only trusted projects are exported: the CLI is an external program that reads the files of the project (on Windows
 * `marp.cmd` even runs through `cmd.exe`, which parses `%`, `&` and `^` in the file names of a repository).
 *
 * [export] runs on the EDT (save dialog), the rest in the project scope under a background progress. The process is
 * killed with the progress, see [runMarpCli].
 */
internal object MarpCliExporter {

    private const val TIMEOUT_MINUTES = MARP_CLI_TIMEOUT_MS / 60_000

    /** Whether the export may run in a project. Replaced in tests. */
    internal var trustedProvider: (Project) -> Boolean = { TrustedProjects.isProjectTrusted(it) }

    /** Notifications are HTML, file names, paths and messages are not. */
    private fun html(text: String): String = StringUtil.escapeXmlEntities(text)

    /** `false` after telling the user why nothing happens, when [project] is not trusted. */
    private fun requireTrusted(project: Project): Boolean {
        if (trustedProvider(project)) return true
        MarpExporter.notify(project, NotificationType.WARNING, MarpBundle.message("export.cli.untrusted"))
        return false
    }

    /** EDT. Asks where to save, saves the open files (the CLI reads from disk), then exports in the background. Nothing happens when the dialog is cancelled. */
    fun export(project: Project, markdown: VirtualFile, format: MarpCliFormat) {
        if (!requireTrusted(project)) return
        val deck = markdown.fileSystem.getNioPath(markdown)
        if (deck == null) {
            MarpExporter.notify(project, NotificationType.WARNING, MarpBundle.message("export.cli.notLocal"))
            return
        }
        val target = MarpExporter.chooseTarget(project, markdown, format) ?: return
        // After the dialog: a cancelled export must not save anything.
        FileDocumentManager.getInstance().saveAllDocuments()
        MarpProjectScope.getInstance(project).scope.launch { run(project, deck, format, target) }
    }

    /** Runs the export under a progress and notifies about the result. Failures are notified, never thrown. */
    internal suspend fun run(project: Project, deck: Path, format: MarpCliFormat, target: Path) {
        if (!requireTrusted(project)) return
        val name = target.fileName.toString()
        try {
            withBackgroundProgress(project, MarpBundle.message("export.progress", name)) {
                convert(project, deck, format, target)
            }
        }
        catch (e: CancellationException) {
            throw e
        }
        catch (e: Exception) {
            LOG.warn("Cannot export $deck to $target", e)
            MarpExporter.notify(project, NotificationType.ERROR, MarpBundle.message("export.failed", html(name), html(e.message ?: e.javaClass.simpleName)))
        }
    }

    private suspend fun convert(project: Project, deck: Path, format: MarpCliFormat, target: Path) {
        val executable = when (val location = withContext(Dispatchers.IO) { MarpCliLocator.locate(MarpAppSettings.getInstance().marpCliPath) }) {
            is MarpCliLocation.Found -> location.executable
            is MarpCliLocation.Missing -> {
                val message = location.configured?.let { MarpBundle.message("export.cli.missingPath", html(it)) } ?: MarpBundle.message("export.cli.notFound")
                MarpExporter.notifyWithSettings(project, NotificationType.WARNING, message)
                return
            }
        }
        val themes = MarpThemeService.getInstance(project).loadThemes().themes
        val settings = MarpSettings.getInstance(project)

        val tempDir = withContext(Dispatchers.IO) { Files.createTempDirectory("marp-cli") }
        try {
            val commandLine = withContext(Dispatchers.IO) {
                // A theme from a URL has no file, the CLI gets a copy. Themes are listed in the order of the set.
                val themeFiles = themes.mapIndexed { index, theme ->
                    theme.path?.toAbsolutePath() ?: tempDir.resolve("url-theme-$index.css").also { Files.writeString(it, theme.css) }
                }
                val config = tempDir.resolve("marp-config.json")
                val json = MarpCliArgs.config(
                    themeFiles = themeFiles,
                    // The project is trusted (see requireTrusted): the HTML setting counts, as it does in the preview.
                    html = MarpPreviewFileEditor.effectiveHtmlMode(settings.html, trusted = true),
                    math = settings.math,
                    allowLocalFiles = true,
                )
                Files.writeString(config, json)
                // Every input is an absolute path and Marp CLI resolves the images from the deck file, so the working
                // directory can be one that holds nothing of the project.
                GeneralCommandLine(listOf(executable.toString()) + MarpCliArgs.arguments(format, deck, target, config))
                    .withWorkingDirectory(tempDir)
                    .withCharset(Charsets.UTF_8)
            }
            val result = try {
                runMarpCli(commandLine, MARP_CLI_TIMEOUT_MS)
            }
            catch (e: ExecutionException) {
                LOG.warn("Cannot start Marp CLI for $deck: ${commandLine.commandLineString}", e)
                val reason = e.message ?: e.javaClass.simpleName
                MarpExporter.notifyWithSettings(project, NotificationType.ERROR, MarpBundle.message("export.cli.cannotStart", html(executable.toString()), html(reason)))
                return
            }
            val produced = result.exitCode == 0 && Files.isRegularFile(if (format.isImages) firstImage(target, format) else target)
            if (produced) {
                LOG.info("Marp CLI exported $deck: ${commandLine.commandLineString}")
                finished(project, format, target)
            }
            else {
                LOG.warn("Marp CLI did not export $deck (exit code ${result.exitCode}): ${commandLine.commandLineString}\n${result.output}")
                failed(project, target, result)
            }
        }
        finally {
            withContext(Dispatchers.IO + NonCancellable) { tempDir.toFile().deleteRecursively() }
        }
    }

    /** The first image of an image export: Marp CLI numbers them itself, `deck.png` becomes `deck.001.png`, `deck.002.png`, ... */
    private fun firstImage(target: Path, format: MarpCliFormat): Path =
        target.resolveSibling("${target.fileName.toString().substringBeforeLast('.')}.001.${format.extension}")

    private suspend fun finished(project: Project, format: MarpCliFormat, target: Path) {
        // Makes the files show up in the project view when they are written inside the project.
        val folder = target.parent
        withContext(Dispatchers.IO) { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(folder)?.refresh(true, false) }
        val name = target.fileName.toString()
        if (format.isImages) {
            MarpExporter.notify(
                project, NotificationType.INFORMATION, MarpBundle.message("export.cli.done.images", html(firstImage(target, format).fileName.toString())),
                MarpBundle.message("export.cli.showFolder"),
            ) { RevealFileAction.openDirectory(folder) }
        }
        else {
            MarpExporter.notify(
                project, NotificationType.INFORMATION, MarpBundle.message("export.done", html(name)),
                MarpBundle.message("export.open"),
            ) { BrowserUtil.open(target.toString()) }
        }
    }

    private fun failed(project: Project, target: Path, result: MarpCliResult) {
        val name = target.fileName.toString()
        val tail = MarpCliArgs.outputTail(result.output)
        val message = when {
            result.exitCode == null -> MarpBundle.message("export.cli.timeout", html(name), TIMEOUT_MINUTES.toString())
            tail.isNotEmpty() -> MarpBundle.message("export.cli.failed", html(name), html(tail).replace("\n", "<br>"))
            else -> MarpBundle.message("export.cli.failed.noOutput", html(name), result.exitCode.toString())
        }
        MarpExporter.notify(project, NotificationType.ERROR, message)
    }
}
