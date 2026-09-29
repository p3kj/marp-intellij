package cz.p3kj.marp.preview

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.intellij.ide.BrowserUtil
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.UI
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.util.coroutines.childScope
import com.intellij.ui.ColorUtil
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import cz.p3kj.marp.MarpBundle
import cz.p3kj.marp.themes.MarpThemeSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.network.CefRequest
import java.awt.BorderLayout
import java.awt.Color
import java.nio.file.Path
import javax.swing.JComponent

private val LOG = logger<MarpPreviewPanel>()

/**
 * The JCEF browser showing the Marp preview page (`https://marp.localhost/app/index.html`) plus the JS bridge
 * described in `docs/ARCHITECTURE.md`.
 *
 * [component] is an empty placeholder at first. The browser is created right after on the EDT, once
 * [MarpJcefStartup.prepare] has run (the first browser of the session starts JCEF), and then shown in the placeholder.
 * Until it is ready the bridge only records calls.
 *
 * Bridge calls are state setters, so [MarpBridgeState] keeps the latest argument of every call and sends them all (in
 * contract order) whenever the page reports `ready`, including after a reload. All public methods are thread-safe.
 * Create only when `JBCefApp.isSupported()`.
 */
class MarpPreviewPanel(private val project: Project, parentScope: CoroutineScope) : Disposable {

    /** Preview -> editor events, delivered on the EDT. */
    interface Listener {
        /** The user scrolled the preview: fractional editor line that should be at the top. */
        fun revealLine(line: Double)

        /** Double-click in a slide: editor line to put the caret on. */
        fun didClick(line: Int)
    }

    @Volatile
    var listener: Listener? = null

    private val scope = parentScope.childScope("Marp preview panel")

    /** Holds the browser component once it exists; shows a message when the browser cannot be created. */
    private val placeholder = JBPanelWithEmptyText(BorderLayout())

    /** Parent of the browser. Registered before the shutdown hook in `init`, so it is disposed after it. */
    private val browserDisposable = Disposer.newDisposable(this, "Marp preview browser")

    private class Browser(val jbBrowser: JBCefBrowser, val query: JBCefJSQuery) {
        val cefBrowser: CefBrowser get() = jbBrowser.cefBrowser
    }

    /** `null` until [createBrowser] ran. */
    @Volatile
    private var browser: Browser? = null

    @Volatile
    private var allowedRoots: Collection<Path> = emptyList()

    private val requestHandler = MarpResourceRequestHandler(
        allowedRoots = { allowedRoots },
        onNavigation = { url -> scope.launch { openLink(url) } },
        onRenderProcessGone = { onPageGone("renderer process terminated") },
    )

    // Only executes after the page reported ready, so the browser exists by then.
    private val bridge = MarpBridgeState { method, json ->
        browser?.cefBrowser?.executeJavaScript("window.marpBridge && window.marpBridge.$method($json);", MarpResourcePaths.APP_INDEX_URL, 0)
    }

    val component: JComponent get() = placeholder

    /** The browser once it exists, the placeholder before. */
    val preferredFocusedComponent: JComponent get() = browser?.jbBrowser?.component ?: placeholder

    init {
        Disposer.register(this, requestHandler)

        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(LafManagerListener.TOPIC, LafManagerListener { sendIdeTheme() })
        connection.subscribe(EditorColorsManager.TOPIC, EditorColorsListener { sendIdeTheme() })
        sendStrings()
        sendIdeTheme()

        scope.launch {
            MarpJcefStartup.prepare()
            // Any modality: only creates UI, and a modal dialog open at startup must not keep the preview empty.
            withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                ensureActive()
                try {
                    createBrowser()
                }
                catch (e: CancellationException) {
                    throw e
                }
                catch (e: Exception) {
                    LOG.warn("Cannot create the Marp preview browser", e)
                    placeholder.withEmptyText(MarpBundle.message("preview.jcef.unsupported"))
                }
            }
        }

        // Registered last, so it is disposed first: stop talking to the browser before it goes away.
        Disposer.register(this) {
            bridge.dispose()
            listener = null
            scope.cancel()
        }
    }

    /** EDT. Called at most once, before the panel is disposed (both happen on the EDT, disposal cancels [scope]). */
    private fun createBrowser() {
        val jbBrowser = JBCefBrowser.createBuilder()
            .setUrl(MarpResourcePaths.APP_INDEX_URL)
            .setEnableOpenDevToolsMenuItem(false)
            .build()
        Disposer.register(browserDisposable, jbBrowser)
        // Must be created before the native browser is, i.e. right after build().
        val query = JBCefJSQuery.create(jbBrowser as JBCefBrowserBase)
        Disposer.register(jbBrowser, query)

        jbBrowser.setProperty(JBCefBrowserBase.Properties.NO_CONTEXT_MENU, true)
        // No JCEF error page: it is loaded as a navigation away from the preview page, which is always cancelled.
        jbBrowser.setErrorPage(null)
        query.addHandler { message ->
            onMessage(message)
            null
        }

        val client = jbBrowser.jbCefClient
        val cefBrowser = jbBrowser.cefBrowser
        client.addRequestHandler(requestHandler, cefBrowser)
        client.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadStart(browser: CefBrowser?, frame: CefFrame?, transitionType: CefRequest.TransitionType?) {
                if (frame?.isMain == true) bridge.onLoadStart()
            }

            override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain != true) return
                val url = frame.url.orEmpty()
                if (url.startsWith(MarpResourcePaths.APP_URL_PREFIX, ignoreCase = true)) injectHost()
                // Safety net for navigations CEF never reports to onBeforeBrowse (about:blank): back to the preview.
                else onPageGone("main frame left the preview for $url")
            }

            override fun onLoadError(
                browser: CefBrowser?,
                frame: CefFrame?,
                errorCode: CefLoadHandler.ErrorCode?,
                errorText: String?,
                failedUrl: String?,
            ) {
                if (frame?.isMain == true && errorCode != CefLoadHandler.ErrorCode.ERR_ABORTED) {
                    LOG.warn("Marp preview failed to load $failedUrl: $errorCode $errorText")
                }
            }
        }, cefBrowser)
        client.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
            override fun onBeforePopup(browser: CefBrowser?, frame: CefFrame?, targetUrl: String?, targetFrameName: String?): Boolean {
                if (targetUrl != null) scope.launch { openLink(targetUrl) }
                return true
            }
        }, cefBrowser)

        browser = Browser(jbBrowser, query)
        placeholder.add(jbBrowser.component, BorderLayout.CENTER)
        placeholder.revalidate()
        placeholder.repaint()
    }

    /** Directories whose files may be served under `https://marp.localhost/doc/`. */
    fun setAllowedRoots(roots: Collection<Path>) {
        allowedRoots = roots.toList()
    }

    fun setThemes(themeSet: MarpThemeSet) {
        val themes = JsonArray()
        for (theme in themeSet.themes) {
            themes.add(JsonObject().apply {
                addProperty("source", theme.source)
                addProperty("css", theme.css)
            })
        }
        val errors = JsonArray()
        themeSet.errors.forEach(errors::add)
        call(MarpBridgeState.SET_THEMES, JsonObject().apply {
            add("themes", themes)
            add("errors", errors)
        })
    }

    /** Unchanged arguments are not sent again (typing bursts end with a render request for text that is already shown). */
    fun update(markdown: String, baseHref: String, html: String, math: String) {
        call(MarpBridgeState.UPDATE, skipUnchanged = true, argument = JsonObject().apply {
            addProperty("markdown", markdown)
            addProperty("baseHref", baseHref)
            add("options", JsonObject().apply {
                addProperty("html", html)
                addProperty("math", math)
            })
        })
    }

    fun scrollToLine(line: Double) {
        if (line.isFinite()) call(MarpBridgeState.SCROLL_TO_LINE, line)
    }

    fun setActiveLine(line: Int) {
        call(MarpBridgeState.SET_ACTIVE_LINE, line)
    }

    /** The page's own texts; `{0}`, `{1}` stay placeholders that the page fills in. */
    private fun sendStrings() {
        call(MarpBridgeState.SET_STRINGS, JsonObject().apply {
            addProperty("dismiss", MarpBundle.message("preview.banner.dismiss"))
            addProperty("themeError", MarpBundle.message("preview.error.theme", "{0}", "{1}"))
            addProperty("renderError", MarpBundle.message("preview.error.render", "{0}"))
            addProperty("unknownTheme", MarpBundle.message("preview.error.unknownTheme", "{0}"))
        })
    }

    private fun sendIdeTheme() {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val background = scheme.defaultBackground
        val foreground = scheme.defaultForeground
        call(MarpBridgeState.SET_IDE_THEME, JsonObject().apply {
            addProperty("dark", ColorUtil.isDark(background))
            addProperty("background", cssHex(background))
            addProperty("foreground", cssHex(foreground))
        })
    }

    private fun call(method: String, argument: Number) {
        bridge.call(method, argument.toString())
    }

    private fun call(method: String, argument: JsonElement, skipUnchanged: Boolean = false) {
        bridge.call(method, argument.toString(), skipUnchanged)
    }

    private fun injectHost() {
        val browser = browser ?: return
        val js = """
            window.__marpHost = { post: function (msg) { ${browser.query.inject("msg")} } };
            if (window.__marpHostReady) window.__marpHostReady();
        """.trimIndent()
        browser.cefBrowser.executeJavaScript(js, MarpResourcePaths.APP_INDEX_URL, 0)
    }

    /** Called by JCEF on its own thread. */
    private fun onMessage(message: String) {
        val json = try {
            JsonParser.parseString(message).takeIf { it.isJsonObject }?.asJsonObject
        }
        catch (_: JsonParseException) {
            null
        }
        if (json == null) {
            LOG.debug("Ignoring malformed preview message: $message")
            return
        }
        when (json.string("type")) {
            "ready" -> bridge.onReady()
            "revealLine" -> json.number("line")?.let { line ->
                scope.launch(Dispatchers.EDT) { listener?.revealLine(line) }
            }
            "didClick" -> json.number("line")?.let { line ->
                scope.launch(Dispatchers.EDT) { listener?.didClick(line.toInt()) }
            }
            "openLink" -> json.string("href")?.let { href -> scope.launch { openLink(href) } }
            "error" -> LOG.info("Marp preview reported: ${json.string("message")}")
            else -> LOG.debug("Ignoring preview message: $message")
        }
    }

    /** The renderer died or the main frame left the preview page: load the preview page again (capped). */
    private fun onPageGone(reason: String) {
        if (bridge.onPageGone()) {
            LOG.info("Marp preview reloads: $reason")
            scope.launch(Dispatchers.UI) {
                delay(RELOAD_DELAY_MS)
                browser?.cefBrowser?.loadURL(MarpResourcePaths.APP_INDEX_URL)
            }
        }
        else {
            LOG.warn("Marp preview gave up reloading: $reason")
        }
    }

    /**
     * Link clicked in the preview (or a popup / cancelled navigation), see [MarpLinkPolicy]: local files inside the
     * allowed roots open in the IDE, web and mail links in the system browser, anything else is ignored.
     */
    private suspend fun openLink(href: String) {
        when (val action = withContext(Dispatchers.IO) { MarpLinkPolicy.decide(href, allowedRoots) }) {
            is MarpLinkPolicy.Action.OpenFile -> {
                val file = withContext(Dispatchers.IO) {
                    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(action.path)
                } ?: return
                withContext(Dispatchers.EDT) {
                    if (!project.isDisposed && file.isValid && !file.isDirectory) OpenFileDescriptor(project, file).navigate(true)
                }
            }
            is MarpLinkPolicy.Action.Browse -> BrowserUtil.browse(action.url, project)
            MarpLinkPolicy.Action.Ignore -> LOG.debug("Ignoring preview link $href")
        }
    }

    override fun dispose() {
        // Children (browser with its JS query, request handler, bus connection, shutdown hook) are disposed by Disposer.
    }

    private companion object {
        const val RELOAD_DELAY_MS = 500L

        fun cssHex(color: Color): String = String.format("#%02x%02x%02x", color.red, color.green, color.blue)

        fun JsonObject.string(name: String): String? {
            val element = get(name) ?: return null
            return if (element.isJsonPrimitive && element.asJsonPrimitive.isString) element.asString else null
        }

        fun JsonObject.number(name: String): Double? {
            val element = get(name) ?: return null
            if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) return null
            return element.asDouble.takeIf { it.isFinite() }
        }
    }
}
