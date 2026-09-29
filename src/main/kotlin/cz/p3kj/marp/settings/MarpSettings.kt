package cz.p3kj.marp.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/** Raw HTML handling in slides, same values as marp-vscode's `markdown.marp.html`. */
enum class MarpHtmlMode(val jsValue: String) {
    OFF("off"),
    DEFAULT("default"),
    ALL("all"),
}

/** Math typesetting library, same values as marp-vscode's `markdown.marp.mathTypesetting`. */
enum class MarpMathMode(val jsValue: String) {
    MATHJAX("mathjax"),
    KATEX("katex"),
    OFF("off"),
}

/**
 * Project-level Marp settings, stored in `.idea/marp.xml` so they can be shared with the team.
 */
@Service(Service.Level.PROJECT)
@State(name = "MarpSettings", storages = [Storage("marp.xml")])
class MarpSettings(private val project: Project) : SimplePersistentStateComponent<MarpSettings.MarpState>(MarpState()) {

    class MarpState : BaseState() {
        /** CSS files, folders (all `*.css` inside) or http(s) URLs. Relative paths resolve against the project directory. */
        var themes by list<String>()

        /** Also load themes from the `themeSet` of a `.marprc.yml` / `.marprc.yaml` / `.marprc.json` in the project root. */
        var useMarprcThemeSet by property(true)

        var html by enum(MarpHtmlMode.DEFAULT)
        var math by enum(MarpMathMode.MATHJAX)
        var scrollSync by property(true)
    }

    val themes: List<String> get() = state.themes.toList()
    val useMarprcThemeSet: Boolean get() = state.useMarprcThemeSet
    val html: MarpHtmlMode get() = state.html
    val math: MarpMathMode get() = state.math
    val scrollSync: Boolean get() = state.scrollSync

    /** Changes the settings and notifies [MarpSettingsListener.TOPIC] subscribers. */
    fun update(block: MarpState.() -> Unit) {
        state.block()
        project.messageBus.syncPublisher(MarpSettingsListener.TOPIC).settingsChanged()
    }

    companion object {
        fun getInstance(project: Project): MarpSettings = project.service()
    }
}
