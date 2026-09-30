package cz.p3kj.marp.directives

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import cz.p3kj.marp.MarpBundle
import cz.p3kj.marp.settings.MarpConfigurable
import cz.p3kj.marp.settings.MarpSettings
import cz.p3kj.marp.themes.MarpThemePaths
import cz.p3kj.marp.themes.MarpThemeService
import cz.p3kj.marp.themes.MarpThemeWatch
import java.io.IOException
import java.nio.file.Path

/** Shows the file chooser of the quick fixes. Tests replace [choose], a real chooser is a modal dialog. */
internal object MarpThemeChooser {
    @Volatile
    var choose: (Project, FileChooserDescriptor, VirtualFile?) -> VirtualFile? = { project, descriptor, toSelect ->
        FileChooser.chooseFile(descriptor, project, toSelect)
    }
}

/**
 * The quick fixes of [MarpUnknownThemeInspection]. They ask for a file or open a dialog, so they are plain
 * [LocalQuickFix]es that do not start in a write action and show no preview, and they are not offered for a bulk "fix all"
 * (it would open one chooser per problem). They only keep strings, never PSI.
 */
internal object MarpThemeQuickFixes {

    /**
     * The fixes for the unknown theme [themeName]: add a CSS file or folder to the settings, the `.marprc` fix (when the
     * settings use `themeSet`; a new `.marprc.yml`, or opening the existing one), and open the settings.
     */
    fun forUnknownTheme(project: Project, themeName: String): Array<LocalQuickFix> {
        val fixes = ArrayList<LocalQuickFix>(3)
        fixes += AddThemeToSettingsFix(themeName)
        if (MarpSettings.getInstance(project).useMarprcThemeSet) {
            val dir = projectDirectory(project)
            if (dir != null) {
                val existing = existingMarprc(dir)
                fixes += if (existing != null) OpenMarprcFix(existing.name) else CreateMarprcFix()
            }
        }
        fixes += OpenMarpSettingsFix()
        return fixes.toTypedArray()
    }

    private fun projectDirectory(project: Project): VirtualFile? =
        MarpThemeService.getInstance(project).projectDir?.let { LocalFileSystem.getInstance().findFileByNioFile(it) }

    /** The `.marprc*` file that Marp CLI reads first, looked up in the virtual file system. */
    private fun existingMarprc(dir: VirtualFile): VirtualFile? =
        MarpThemeWatch.MARPRC_NAMES.firstNotNullOfOrNull { name -> dir.findChild(name)?.takeIf { !it.isDirectory } }

    private fun open(project: Project, file: VirtualFile) {
        OpenFileDescriptor(project, file).navigate(true)
    }

    private abstract class DialogFix : LocalQuickFix {
        override fun startInWriteAction(): Boolean = false
        override fun availableInBatchMode(): Boolean = false
        override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY
    }

    private class AddThemeToSettingsFix(private val themeName: String) : DialogFix() {
        override fun getFamilyName(): String = MarpBundle.message("fix.theme.addToSettings")

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val base = MarpThemeService.getInstance(project).projectDir
            val chooser = FileChooserDescriptorFactory.singleFileOrDir()
                .withExtensionFilter("css")
                .withTitle(MarpBundle.message("fix.theme.choose.title"))
                .withDescription(MarpBundle.message("fix.theme.choose.description", themeName))
            val chosen = MarpThemeChooser.choose(project, chooser, projectDirectory(project)) ?: return
            val stored = MarpThemePaths.toStored(Path.of(chosen.path), base)
            val settings = MarpSettings.getInstance(project)
            if (isListed(settings.themes, stored, base)) return
            val next = settings.themes + stored
            // The same change as Apply in Settings | Tools | Marp: it notifies the listeners, which reload the themes.
            settings.update {
                themes.clear()
                themes.addAll(next)
            }
        }

        /** An entry that names the same path as [stored] counts as listed, however it is spelled (`./a.css`, `a.css`). */
        private fun isListed(entries: List<String>, stored: String, base: Path?): Boolean {
            val wanted = MarpThemePaths.resolve(base, stored)
            return entries.any { entry ->
                val text = entry.trim()
                text == stored || (wanted != null && !MarpThemePaths.hasScheme(text) && MarpThemePaths.resolve(base, text) == wanted)
            }
        }
    }

    /** Creates `.marprc.yml` with a `themeSet` that names a chosen file or folder inside the project, and opens it. */
    private class CreateMarprcFix : DialogFix() {
        override fun getFamilyName(): String = MarpBundle.message("fix.theme.createMarprc")

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val base = MarpThemeService.getInstance(project).projectDir ?: return
            val dir = projectDirectory(project) ?: return
            // It may have been created since the problem was reported.
            dir.refresh(false, false)
            existingMarprc(dir)?.let { return open(project, it) }
            val chooser = FileChooserDescriptorFactory.singleFileOrDir()
                .withExtensionFilter("css")
                .withRoots(dir)
                .withTitle(MarpBundle.message("fix.theme.choose.title"))
                .withDescription(MarpBundle.message("fix.theme.marprc.choose.description"))
            val chosen = MarpThemeChooser.choose(project, chooser, dir) ?: return
            val path = Path.of(chosen.path)
            // A .marprc may only name files inside the project, the same rule as when it is loaded.
            if (!MarpThemePaths.isConfinedTo(path, base)) {
                Messages.showErrorDialog(project, MarpBundle.message("fix.theme.marprc.outsideProject"), MarpBundle.message("fix.theme.createMarprc.command"))
                return
            }
            val entry = if (path.toAbsolutePath().normalize() == base.toAbsolutePath().normalize()) "." else MarpThemePaths.toStored(path, base)
            val text = "themeSet:\n  - '${entry.replace("'", "''")}'\n"
            val title = MarpBundle.message("fix.theme.createMarprc.command")
            val file = try {
                WriteCommandAction.writeCommandAction(project).withName(title).compute<VirtualFile, IOException> {
                    dir.createChildData(this@CreateMarprcFix, MARPRC_FILE).also { VfsUtil.saveText(it, text) }
                }
            } catch (e: IOException) {
                Messages.showErrorDialog(project, e.message ?: e.javaClass.simpleName, title)
                return
            }
            open(project, file)
        }
    }

    private class OpenMarprcFix(private val fileName: String) : DialogFix() {
        override fun getFamilyName(): String = MarpBundle.message("fix.theme.openMarprc.family")
        override fun getName(): String = MarpBundle.message("fix.theme.openMarprc", fileName)

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val dir = projectDirectory(project) ?: return
            dir.findChild(fileName)?.takeIf { !it.isDirectory }?.let { open(project, it) }
        }
    }

    private class OpenMarpSettingsFix : DialogFix() {
        override fun getFamilyName(): String = MarpBundle.message("fix.theme.openSettings")

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, MarpConfigurable::class.java)
        }
    }

    private const val MARPRC_FILE = ".marprc.yml"
}
