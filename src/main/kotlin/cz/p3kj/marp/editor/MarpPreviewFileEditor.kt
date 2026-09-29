package cz.p3kj.marp.editor

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.ide.trustedProjects.TrustedProjectsListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.platform.util.coroutines.childScope
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.jcef.JBCefApp
import cz.p3kj.marp.MarpBundle
import cz.p3kj.marp.preview.MarpPreviewPanel
import cz.p3kj.marp.preview.MarpResourcePaths
import cz.p3kj.marp.settings.MarpHtmlMode
import cz.p3kj.marp.settings.MarpSettings
import cz.p3kj.marp.settings.MarpSettingsListener
import cz.p3kj.marp.themes.MarpThemeListener
import cz.p3kj.marp.themes.MarpThemeService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.beans.PropertyChangeListener
import java.nio.file.InvalidPathException
import java.nio.file.Path
import javax.swing.JComponent

private val LOG = logger<MarpPreviewFileEditor>()

/**
 * The preview half of the Marp split editor: renders the document with marp-core in [MarpPreviewPanel].
 *
 * Document edits are throttled ([RENDER_DELAY_MS]) and rendered in a coroutine scope that is cancelled on dispose;
 * settings, theme, trust, content root and rename/move changes re-render immediately. When JCEF is not available the
 * editor shows a plain message instead of the browser.
 */
class MarpPreviewFileEditor(val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {

    private val scope: CoroutineScope = MarpProjectScope.getInstance(project).scope.childScope("Marp preview ${file.name}")

    private val document: Document? = runReadActionBlocking { FileDocumentManager.getInstance().getDocument(file) }

    /** `null` when JCEF is not supported. */
    val panel: MarpPreviewPanel? = if (JBCefApp.isSupported()) MarpPreviewPanel(project, scope) else null

    private val component: JComponent = panel?.component
        ?: JBPanelWithEmptyText().withEmptyText(MarpBundle.message("preview.jcef.unsupported"))

    /** Conflated, so requests sent before the collectors start are kept. `true` = render now, `false` = after [RENDER_DELAY_MS] (typing). */
    private val renderRequests = Channel<Boolean>(Channel.CONFLATED)
    private val themeRequests = Channel<Unit>(Channel.CONFLATED)

    init {
        Disposer.register(this) { scope.cancel() }
        if (panel != null) {
            Disposer.register(this, panel)
            startRendering()
            subscribe()
        }
    }

    private fun startRendering() {
        scope.launch {
            // Throttled, not debounced: edits made during the delay are conflated into one follow-up request, so the
            // preview keeps refreshing while the user types instead of waiting for a pause.
            for (immediate in renderRequests) {
                if (!immediate) delay(RENDER_DELAY_MS)
                logFailures("render") { render() }
            }
        }
        scope.launch {
            themeRequests.receiveAsFlow().collectLatest {
                logFailures("load themes") {
                    val themes = MarpThemeService.getInstance(project).loadThemes()
                    panel?.setThemes(themes)
                }
                requestRender(immediate = true)
            }
        }
        requestRender(immediate = true)
        requestThemes()
    }

    private fun subscribe() {
        document?.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                requestRender(immediate = false)
            }
        }, this)

        val connection = project.messageBus.connect(this)
        connection.subscribe(MarpSettingsListener.TOPIC, MarpSettingsListener {
            requestThemes()
            requestRender(immediate = true)
        })
        connection.subscribe(MarpThemeListener.TOPIC, MarpThemeListener { requestThemes() })
        // Content roots decide which local files the preview may load.
        connection.subscribe(ModuleRootListener.TOPIC, object : ModuleRootListener {
            override fun rootsChanged(event: ModuleRootEvent) {
                requestRender(immediate = true)
            }
        })
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                // A renamed or moved file (or parent folder) changes the base href.
                if (events.any { it.movesOrRenames(file) }) requestRender(immediate = true)
            }
        })

        ApplicationManager.getApplication().messageBus.connect(this).subscribe(TrustedProjectsListener.TOPIC, object : TrustedProjectsListener {
            override fun onProjectTrusted(project: Project) = trustChanged(project)
            override fun onProjectUntrusted(project: Project) = trustChanged(project)
        })
    }

    /** Trust decides the HTML mode and which themes load (see [effectiveHtmlMode], [MarpThemeService]). */
    private fun trustChanged(changed: Project) {
        if (changed != project) return
        requestThemes()
        requestRender(immediate = true)
    }

    private fun requestRender(immediate: Boolean) {
        renderRequests.trySend(immediate)
    }

    private fun requestThemes() {
        themeRequests.trySend(Unit)
    }

    /** Keeps the request loops alive when one round fails; cancellation still propagates. */
    private inline fun logFailures(what: String, block: () -> Unit) {
        try {
            block()
        }
        catch (e: CancellationException) {
            throw e
        }
        catch (e: Exception) {
            LOG.warn("Marp preview: cannot $what for ${file.path}", e)
        }
    }

    private class RenderInput(val markdown: String, val baseHref: String, val roots: List<Path>)

    private suspend fun render() {
        val panel = panel ?: return
        val document = document ?: return
        val input = readAction {
            if (project.isDisposed || !file.isValid) null
            else RenderInput(document.text, baseHref(), allowedRoots())
        } ?: return
        val settings = MarpSettings.getInstance(project)
        panel.setAllowedRoots(input.roots)
        panel.update(
            markdown = input.markdown,
            baseHref = input.baseHref,
            html = effectiveHtmlMode(settings.html, TrustedProjects.isProjectTrusted(project)).jsValue,
            math = settings.math.jsValue,
        )
    }

    /** `https://marp.localhost/doc/<markdown file dir>/`, so relative images and links resolve to local files. */
    private fun baseHref(): String {
        val dir = file.parent?.let { it.fileSystem.getNioPath(it) } ?: return MarpResourcePaths.DOC_URL_PREFIX
        return MarpResourcePaths.docUrl(dir.toString().replace('\\', '/'), directory = true)
    }

    /** Project base dir, content roots and the Markdown file's directory. Call in a read action. */
    private fun allowedRoots(): List<Path> {
        val roots = LinkedHashSet<Path>()
        project.basePath?.let { basePath ->
            try {
                roots.add(Path.of(basePath))
            }
            catch (_: InvalidPathException) {
            }
        }
        for (root in ProjectRootManager.getInstance(project).contentRoots) {
            root.fileSystem.getNioPath(root)?.let(roots::add)
        }
        file.parent?.let { it.fileSystem.getNioPath(it) }?.let(roots::add)
        return roots.toList()
    }

    override fun getComponent(): JComponent = component

    override fun getPreferredFocusedComponent(): JComponent = component

    override fun getName(): String = MarpBundle.message("preview.editor.name")

    override fun setState(state: FileEditorState) {}

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = file.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun getFile(): VirtualFile = file

    override fun dispose() {
        // The panel, listeners and the coroutine scope are disposed through Disposer.
    }

    companion object {
        /** Delay between a document edit and its render; also the render interval while the user keeps typing. */
        const val RENDER_DELAY_MS: Long = 150

        /** Like marp-vscode: raw HTML in slides only in trusted projects, untrusted ones render no HTML at all. */
        fun effectiveHtmlMode(configured: MarpHtmlMode, trusted: Boolean): MarpHtmlMode =
            if (trusted) configured else MarpHtmlMode.OFF

        private fun VFileEvent.movesOrRenames(target: VirtualFile): Boolean {
            val changed = when (this) {
                is VFileMoveEvent -> file
                is VFilePropertyChangeEvent -> if (propertyName == VirtualFile.PROP_NAME) file else null
                else -> null
            } ?: return false
            return VfsUtilCore.isAncestor(changed, target, false)
        }
    }
}
