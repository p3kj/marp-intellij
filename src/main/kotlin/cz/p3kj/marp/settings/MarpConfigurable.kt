package cz.p3kj.marp.settings

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import cz.p3kj.marp.MarpBundle
import cz.p3kj.marp.themes.MarpThemePaths
import java.awt.event.MouseEvent
import java.net.URI
import java.nio.file.InvalidPathException
import java.nio.file.Path
import javax.swing.ListSelectionModel

/**
 * Settings | Tools | Marp. Themes, `.marprc`, HTML and math are project settings ([MarpSettings]); presenter notes and
 * scroll sync are IDE-wide preferences ([MarpAppSettings]).
 */
class MarpConfigurable(private val project: Project) : BoundConfigurable(MarpBundle.message("settings.displayName")) {

    private val settings get() = MarpSettings.getInstance(project)
    private val appSettings get() = MarpAppSettings.getInstance()

    /** The theme entries being edited; internal for tests. */
    internal val themeEntries = CollectionListModel<String>()
    private var useMarprcThemeSet = true
    private var html = MarpHtmlMode.DEFAULT
    private var math = MarpMathMode.MATHJAX
    private var scrollSync = true
    private var presenterNotes = false

    private val projectDir: Path? get() = project.basePath?.let { Path.of(it) }

    override fun createPanel(): DialogPanel {
        loadFromSettings()
        val list = JBList(themeEntries).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            // Keeps the page within the default Settings dialog height; the row grows with the dialog.
            visibleRowCount = LIST_VISIBLE_ROWS
        }
        val decorator = ToolbarDecorator.createDecorator(list)
            .setAddAction { button -> showAddPopup(button.dataContext) }
            .setAddActionName(MarpBundle.message("settings.themes.add.file"))
            .setEditAction { editEntry(list.selectedIndex) }
            .setEditActionName(MarpBundle.message("settings.themes.edit"))
            .createPanel()
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                if (list.selectedIndex < 0) return false
                editEntry(list.selectedIndex)
                return true
            }
        }.installOn(list)

        // Comments wrap at the width of the page instead of widening it (the default is a fixed line length).
        return panel {
            group(MarpBundle.message("settings.themes.group")) {
                row {
                    cell(decorator).align(Align.FILL).resizableColumn()
                }.resizableRow()
                row {
                    comment(MarpBundle.message("settings.themes.comment"), MAX_LINE_LENGTH_WORD_WRAP)
                }
                row {
                    checkBox(MarpBundle.message("settings.useMarprcThemeSet"))
                        .bindSelected(::useMarprcThemeSet)
                        .comment(MarpBundle.message("settings.useMarprcThemeSet.comment"), MAX_LINE_LENGTH_WORD_WRAP)
                }
            }.resizableRow()
            group(MarpBundle.message("settings.rendering.group")) {
                // Row comments start under the label: under the combo box they would need the label width on top.
                row(MarpBundle.message("settings.html")) {
                    comboBox(MarpHtmlMode.entries, textListCellRenderer { it?.let(::htmlLabel) })
                        .bindItem({ html }, { html = it ?: MarpHtmlMode.DEFAULT })
                }.rowComment(MarpBundle.message("settings.html.comment"), MAX_LINE_LENGTH_WORD_WRAP)
                row(MarpBundle.message("settings.math")) {
                    comboBox(MarpMathMode.entries, textListCellRenderer { it?.let(::mathLabel) })
                        .bindItem({ math }, { math = it ?: MarpMathMode.MATHJAX })
                }.rowComment(MarpBundle.message("settings.math.comment"), MAX_LINE_LENGTH_WORD_WRAP)
                row {
                    checkBox(MarpBundle.message("settings.presenterNotes"))
                        .bindSelected(::presenterNotes)
                        .comment(MarpBundle.message("settings.presenterNotes.comment"), MAX_LINE_LENGTH_WORD_WRAP)
                }
                row {
                    checkBox(MarpBundle.message("settings.scrollSync"))
                        .bindSelected(::scrollSync)
                        .comment(MarpBundle.message("settings.scrollSync.comment"))
                }
            }
        }
    }

    override fun isModified(): Boolean =
        super.isModified() || themeEntries.items != settings.themes

    override fun reset() {
        loadFromSettings()
        super.reset()
    }

    override fun apply() {
        super.apply()
        val themes = themeEntries.items.toList()
        settings.update {
            this.themes.clear()
            this.themes.addAll(themes)
            useMarprcThemeSet = this@MarpConfigurable.useMarprcThemeSet
            html = this@MarpConfigurable.html
            math = this@MarpConfigurable.math
        }
        appSettings.update {
            scrollSync = this@MarpConfigurable.scrollSync
            presenterNotes = this@MarpConfigurable.presenterNotes
        }
    }

    private fun loadFromSettings() {
        val s = settings
        themeEntries.replaceAll(s.themes)
        useMarprcThemeSet = s.useMarprcThemeSet
        html = s.html
        math = s.math
        scrollSync = appSettings.scrollSync
        presenterNotes = appSettings.presenterNotes
    }

    private fun htmlLabel(mode: MarpHtmlMode): String = when (mode) {
        MarpHtmlMode.OFF -> MarpBundle.message("settings.html.off")
        MarpHtmlMode.DEFAULT -> MarpBundle.message("settings.html.default")
        MarpHtmlMode.ALL -> MarpBundle.message("settings.html.all")
    }

    private fun mathLabel(mode: MarpMathMode): String = when (mode) {
        MarpMathMode.MATHJAX -> MarpBundle.message("settings.math.mathjax")
        MarpMathMode.KATEX -> MarpBundle.message("settings.math.katex")
        MarpMathMode.OFF -> MarpBundle.message("settings.math.off")
    }

    /** Adds [entry] (trimmed) unless it is blank or already in the list. */
    internal fun addEntry(entry: String) {
        val text = entry.trim()
        if (text.isEmpty() || text in themeEntries.items) return
        themeEntries.add(text)
    }

    /** Replaces the entry at [index] with [entry] (trimmed); a blank text or one that is already in the list is ignored. */
    internal fun replaceEntry(index: Int, entry: String) {
        val text = entry.trim()
        if (index !in 0 until themeEntries.size || text.isEmpty()) return
        if (themeEntries.items.withIndex().any { (i, existing) -> i != index && existing == text }) return
        themeEntries.setElementAt(text, index)
    }

    private fun showAddPopup(dataContext: DataContext) {
        val group = DefaultActionGroup(
            action("settings.themes.add.file") { addFile() },
            action("settings.themes.add.folder") { addFolder() },
            action("settings.themes.add.url") { addUrl() },
            action("settings.themes.add.path") { addPath() },
        )
        val popupFactory = JBPopupFactory.getInstance()
        popupFactory
            .createActionGroupPopup(null, group, dataContext, JBPopupFactory.ActionSelectionAid.MNEMONICS, true)
            .show(popupFactory.guessBestPopupLocation(dataContext))
    }

    private fun action(key: String, block: () -> Unit) = object : DumbAwareAction(MarpBundle.message(key)) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) = block()
    }

    private fun addFile() {
        val descriptor = FileChooserDescriptorFactory.singleFile()
            .withExtensionFilter("css")
            .withTitle(MarpBundle.message("settings.themes.chooseFile.title"))
            .withDescription(MarpBundle.message("settings.themes.chooseFile.description"))
        FileChooser.chooseFile(descriptor, project, null)?.let { addEntry(MarpThemePaths.toStored(Path.of(it.path), projectDir)) }
    }

    private fun addFolder() {
        val descriptor = FileChooserDescriptorFactory.singleDir()
            .withTitle(MarpBundle.message("settings.themes.chooseFolder.title"))
            .withDescription(MarpBundle.message("settings.themes.chooseFolder.description"))
        FileChooser.chooseFile(descriptor, project, null)?.let { addEntry(MarpThemePaths.toStored(Path.of(it.path), projectDir)) }
    }

    private fun addUrl() {
        val validator = object : InputValidatorEx {
            override fun getErrorText(inputString: String): String? =
                if (isValidHttpUrl(inputString.trim())) null else MarpBundle.message("settings.themes.url.invalid")
            override fun checkInput(inputString: String) = isValidHttpUrl(inputString.trim())
            override fun canClose(inputString: String) = checkInput(inputString)
        }
        val url = Messages.showInputDialog(
            project,
            MarpBundle.message("settings.themes.url.prompt"),
            MarpBundle.message("settings.themes.url.title"),
            null,
            "https://",
            validator,
        )
        if (url != null) addEntry(url)
    }

    /** A typed path (or URL), for entries the file chooser cannot produce, such as a folder that does not exist yet. */
    private fun addPath() {
        val text = askForEntry(MarpBundle.message("settings.themes.path.title"), "", editedIndex = -1) ?: return
        addEntry(text)
    }

    private fun editEntry(index: Int) {
        val current = themeEntries.items.getOrNull(index) ?: return
        val text = askForEntry(MarpBundle.message("settings.themes.edit.title"), current, editedIndex = index) ?: return
        replaceEntry(index, text)
    }

    private fun askForEntry(title: String, initial: String, editedIndex: Int): String? {
        val validator = object : InputValidatorEx {
            override fun getErrorText(inputString: String): String? = entryError(inputString, themeEntries.items, editedIndex)
            override fun checkInput(inputString: String) = getErrorText(inputString) == null
            override fun canClose(inputString: String) = checkInput(inputString)
        }
        return Messages.showInputDialog(project, MarpBundle.message("settings.themes.entry.prompt"), title, null, initial, validator)
    }

    companion object {
        private const val LIST_VISIBLE_ROWS = 5

        internal fun isValidHttpUrl(text: String): Boolean = try {
            val uri = URI(text)
            (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.host.isNullOrEmpty()
        } catch (_: Exception) {
            false
        }

        /**
         * Why [text] cannot be a theme entry, or `null` when it can: a file or folder path (absolute or relative) or an
         * http(s) URL, not already in [entries] except at [editedIndex] (the entry being edited, `-1` when adding).
         */
        internal fun entryError(text: String, entries: List<String>, editedIndex: Int): String? {
            val entry = text.trim()
            return when {
                entry.isEmpty() -> MarpBundle.message("settings.themes.entry.empty")
                MarpThemePaths.isHttpUrl(entry) -> if (isValidHttpUrl(entry)) null else MarpBundle.message("settings.themes.url.invalid")
                MarpThemePaths.hasScheme(entry) -> MarpBundle.message("settings.themes.entry.unsupportedScheme")
                !isValidPath(entry) -> MarpBundle.message("settings.themes.entry.invalidPath")
                else -> null
            } ?: MarpBundle.message("settings.themes.entry.duplicate").takeIf {
                entries.withIndex().any { (i, existing) -> i != editedIndex && existing == entry }
            }
        }

        private fun isValidPath(text: String): Boolean = try {
            Path.of(text)
            true
        } catch (_: InvalidPathException) {
            false
        }
    }
}
