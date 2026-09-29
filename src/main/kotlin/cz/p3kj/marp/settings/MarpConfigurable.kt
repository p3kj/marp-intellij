package cz.p3kj.marp.settings

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.AnActionButton
import com.intellij.ui.CollectionListModel
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
import java.net.URI
import java.nio.file.Path
import javax.swing.ListSelectionModel

/** Settings | Tools | Marp. */
class MarpConfigurable(private val project: Project) : BoundConfigurable(MarpBundle.message("settings.displayName")) {

    private val settings get() = MarpSettings.getInstance(project)

    private val listModel = CollectionListModel<String>()
    private var useMarprcThemeSet = true
    private var html = MarpHtmlMode.DEFAULT
    private var math = MarpMathMode.MATHJAX
    private var scrollSync = true

    private val projectDir: Path? get() = project.basePath?.let { Path.of(it) }

    override fun createPanel(): DialogPanel {
        loadFromSettings()
        val list = JBList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            // Keeps the page within the default Settings dialog height; the row grows with the dialog.
            visibleRowCount = LIST_VISIBLE_ROWS
        }
        val decorator = ToolbarDecorator.createDecorator(list)
            .setAddAction { button -> showAddPopup(button) }
            .setAddActionName(MarpBundle.message("settings.themes.add.file"))
            .createPanel()

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
                    checkBox(MarpBundle.message("settings.scrollSync")).bindSelected(::scrollSync)
                }
            }
        }
    }

    override fun isModified(): Boolean =
        super.isModified() || listModel.items != settings.themes

    override fun reset() {
        loadFromSettings()
        super.reset()
    }

    override fun apply() {
        super.apply()
        val themes = listModel.items.toList()
        settings.update {
            this.themes.clear()
            this.themes.addAll(themes)
            useMarprcThemeSet = this@MarpConfigurable.useMarprcThemeSet
            html = this@MarpConfigurable.html
            math = this@MarpConfigurable.math
            scrollSync = this@MarpConfigurable.scrollSync
        }
    }

    private fun loadFromSettings() {
        val s = settings
        listModel.replaceAll(s.themes)
        useMarprcThemeSet = s.useMarprcThemeSet
        html = s.html
        math = s.math
        scrollSync = s.scrollSync
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

    private fun addEntry(entry: String) {
        if (entry in listModel.items) return
        listModel.add(entry)
    }

    private fun showAddPopup(button: AnActionButton) {
        val group = DefaultActionGroup(
            action("settings.themes.add.file") { addFile() },
            action("settings.themes.add.folder") { addFolder() },
            action("settings.themes.add.url") { addUrl() },
        )
        JBPopupFactory.getInstance()
            .createActionGroupPopup(null, group, button.dataContext, JBPopupFactory.ActionSelectionAid.MNEMONICS, true)
            .show(button.preferredPopupPoint)
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
        if (url != null) addEntry(url.trim())
    }

    companion object {
        private const val LIST_VISIBLE_ROWS = 5

        internal fun isValidHttpUrl(text: String): Boolean = try {
            val uri = URI(text)
            (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.host.isNullOrEmpty()
        } catch (_: Exception) {
            false
        }
    }
}
