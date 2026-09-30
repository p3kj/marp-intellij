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
 * [export] runs on the EDT (save dialog), the rest in the project scope under a background progress. The process is
 * killed with the progress, see [runMarpCli].
 */
internal object MarpCliExporter {

    private const val TIMEOUT_MINUTES = MARP_CLI_TIMEOUT_MS / 60_000

    /** EDT. Asks where to save, saves the open files (the CLI reads from disk), then exports in the background. Nothing happens when the dialog is cancelled. */
    fun export(project: Project, markdown: VirtualFile, format: MarpCliFormat) {
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
            MarpExporter.notify(project, NotificationType.ERROR, MarpBundle.message("export.failed", name, e.message ?: e.javaClass.simpleName))
        }
    }

    private suspend fun convert(project: Project, deck: Path, format: MarpCliFormat, target: Path) {
        val executable = when (val location = withContext(Dispatchers.IO) { MarpCliLocator.locate(MarpAppSettings.getInstance().marpCliPath) }) {
            is MarpCliLocation.Found -> location.executable
            is MarpCliLocation.Missing -> {
                val message = location.configured?.let { MarpBundle.message("export.cli.missingPath", it) } ?: MarpBundle.message("export.cli.notFound")
                MarpExporter.notifyWithSettings(project, NotificationType.WARNING, message)
                return
            }
        }
        val themes = MarpThemeService.getInstance(project).loadThemes().themes
        val settings = MarpSettings.getInstance(project)
        val trusted = TrustedProjects.isProjectTrusted(project)

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
                    // Untrusted projects: no raw HTML and no local files, like the preview (no custom themes are loaded either).
                    html = MarpPreviewFileEditor.effectiveHtmlMode(settings.html, trusted),
                    math = settings.math,
                    allowLocalFiles = trusted,
                )
                Files.writeString(config, json)
                GeneralCommandLine(listOf(executable.toString()) + MarpCliArgs.arguments(format, deck, target, config))
                    .withWorkingDirectory(deck.parent)
                    .withCharset(Charsets.UTF_8)
            }
            val result = try {
                runMarpCli(commandLine, MARP_CLI_TIMEOUT_MS)
            }
            catch (e: ExecutionException) {
                LOG.warn("Cannot start Marp CLI for $deck: ${commandLine.commandLineString}", e)
                val reason = e.message ?: e.javaClass.simpleName
                MarpExporter.notifyWithSettings(project, NotificationType.ERROR, MarpBundle.message("export.cli.cannotStart", executable.toString(), reason))
                return
            }
            val produced = result.exitCode == 0 && (format.isImages || Files.isRegularFile(target))
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

    private suspend fun finished(project: Project, format: MarpCliFormat, target: Path) {
        // Makes the files show up in the project view when they are written inside the project.
        val folder = target.parent
        withContext(Dispatchers.IO) { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(folder)?.refresh(true, false) }
        val name = target.fileName.toString()
        if (format.isImages) {
            // Marp CLI numbers the images itself: deck.png becomes deck.001.png, deck.002.png, ...
            val example = "${name.substringBeforeLast('.')}.001.${format.extension}"
            MarpExporter.notify(
                project, NotificationType.INFORMATION, MarpBundle.message("export.cli.done.images", example),
                MarpBundle.message("export.cli.showFolder"),
            ) { RevealFileAction.openDirectory(folder) }
        }
        else {
            MarpExporter.notify(
                project, NotificationType.INFORMATION, MarpBundle.message("export.done", name),
                MarpBundle.message("export.open"),
            ) { BrowserUtil.browse(target) }
        }
    }

    private fun failed(project: Project, target: Path, result: MarpCliResult) {
        val name = target.fileName.toString()
        val tail = MarpCliArgs.outputTail(result.output)
        val message = when {
            result.exitCode == null -> MarpBundle.message("export.cli.timeout", name, TIMEOUT_MINUTES)
            // The notification is HTML, the output is not.
            tail.isNotEmpty() -> MarpBundle.message("export.cli.failed", name, StringUtil.escapeXmlEntities(tail).replace("\n", "<br>"))
            else -> MarpBundle.message("export.cli.failed.noOutput", name, result.exitCode)
        }
        MarpExporter.notify(project, NotificationType.ERROR, message)
    }
}
