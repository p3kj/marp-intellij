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
import com.intellij.openapi.application.UI
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.util.coroutines.childScope
import com.intellij.ui.ColorUtil
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import cz.p3kj.marp.themes.MarpThemeSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.network.CefRequest
import java.awt.Color
import java.nio.file.Path
import javax.swing.JComponent

private val LOG = logger<MarpPreviewPanel>()

/**
 * The JCEF browser showing the Marp preview page (`https://marp.localhost/app/index.html`) plus the JS bridge
 * described in `docs/ARCHITECTURE.md`.
 *
 * Bridge calls are state setters, so the panel keeps the latest argument of every call and sends them all (in contract
 * order) whenever the page reports `ready`, including after a reload. All public methods are thread-safe.
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

    private val browser: JBCefBrowser = JBCefBrowser.createBuilder()
        .setUrl(MarpResourcePaths.APP_INDEX_URL)
        .setEnableOpenDevToolsMenuItem(false)
        .build()

    // Must be created before the native browser is, i.e. right after build().
    private val query: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

    @Volatile
    private var allowedRoots: Collection<Path> = emptyList()

    private val requestHandler = MarpResourceRequestHandler(
        allowedRoots = { allowedRoots },
        onNavigation = { url -> scope.launch { openLink(url) } },
        onRenderProcessGone = ::onRenderProcessGone,
    )

    private val lock = Any()
    private var ready = false
    private var disposed = false
    private var crashReloads = 0

    /** Latest JSON argument per bridge method, in the order they are replayed on `ready`. */
    private val state = linkedMapOf<String, String?>(
        SET_IDE_THEME to null,
        SET_THEMES to null,
        UPDATE to null,
        SCROLL_TO_LINE to null,
        SET_ACTIVE_LINE to null,
    )

    val component: JComponent get() = browser.component

    init {
        Disposer.register(this, browser)
        Disposer.register(browser, query)
        Disposer.register(this, requestHandler)

        browser.setProperty(JBCefBrowserBase.Properties.NO_CONTEXT_MENU, true)
        query.addHandler { message ->
            onMessage(message)
            null
        }

        val client = browser.jbCefClient
        val cefBrowser = browser.cefBrowser
        client.addRequestHandler(requestHandler, cefBrowser)
        client.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadStart(browser: CefBrowser?, frame: CefFrame?, transitionType: CefRequest.TransitionType?) {
                if (frame?.isMain == true) synchronized(lock) { ready = false }
            }

            override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true && frame.url.orEmpty().startsWith(MarpResourcePaths.APP_URL_PREFIX)) injectHost()
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

        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(LafManagerListener.TOPIC, LafManagerListener { sendIdeTheme() })
        connection.subscribe(EditorColorsManager.TOPIC, EditorColorsListener { sendIdeTheme() })
        sendIdeTheme()

        // Registered last, so it is disposed first: stop talking to the browser before it goes away.
        Disposer.register(this) {
            synchronized(lock) {
                disposed = true
                ready = false
            }
            listener = null
            scope.cancel()
        }
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
        call(SET_THEMES, JsonObject().apply {
            add("themes", themes)
            add("errors", errors)
        })
    }

    /** Unchanged arguments are not sent again (typing bursts end with a render request for text that is already shown). */
    fun update(markdown: String, baseHref: String, html: String, math: String) {
        call(UPDATE, skipUnchanged = true, argument = JsonObject().apply {
            addProperty("markdown", markdown)
            addProperty("baseHref", baseHref)
            add("options", JsonObject().apply {
                addProperty("html", html)
                addProperty("math", math)
            })
        })
    }

    fun scrollToLine(line: Double) {
        if (line.isFinite()) call(SCROLL_TO_LINE, line)
    }

    fun setActiveLine(line: Int) {
        call(SET_ACTIVE_LINE, line)
    }

    private fun sendIdeTheme() {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val background = scheme.defaultBackground
        val foreground = scheme.defaultForeground
        call(SET_IDE_THEME, JsonObject().apply {
            addProperty("dark", ColorUtil.isDark(background))
            addProperty("background", cssHex(background))
            addProperty("foreground", cssHex(foreground))
        })
    }

    private fun call(method: String, argument: Number) {
        callJson(method, argument.toString())
    }

    private fun call(method: String, argument: JsonElement, skipUnchanged: Boolean = false) {
        callJson(method, argument.toString(), skipUnchanged)
    }

    private fun callJson(method: String, json: String, skipUnchanged: Boolean = false) {
        synchronized(lock) {
            if (disposed) return
            if (skipUnchanged && state[method] == json) return
            state[method] = json
            if (ready) execute(method, json)
        }
    }

    /** Call with [lock] held. */
    private fun execute(method: String, json: String) {
        browser.cefBrowser.executeJavaScript("window.marpBridge && window.marpBridge.$method($json);", MarpResourcePaths.APP_INDEX_URL, 0)
    }

    private fun injectHost() {
        val js = """
            window.__marpHost = { post: function (msg) { ${query.inject("msg")} } };
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
            "ready" -> onReady()
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

    private fun onReady() {
        synchronized(lock) {
            if (disposed) return
            ready = true
            crashReloads = 0
            for ((method, json) in state) {
                if (json != null) execute(method, json)
            }
        }
    }

    private fun onRenderProcessGone() {
        val reload = synchronized(lock) {
            ready = false
            !disposed && crashReloads++ < MAX_CRASH_RELOADS
        }
        if (reload) {
            scope.launch(Dispatchers.UI) {
                delay(CRASH_RELOAD_DELAY_MS)
                browser.cefBrowser.reload()
            }
        }
    }

    /** Link clicked in the preview: local files open in the IDE, web and mail links in the system browser. */
    private suspend fun openLink(href: String) {
        if (href.startsWith(MarpResourcePaths.DOC_URL_PREFIX, ignoreCase = true)) {
            val target = MarpResourcePaths.parse(href) as? MarpResourcePaths.Target.Doc ?: return
            val file = withContext(Dispatchers.IO) {
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target.path)
            } ?: return
            if (file.isDirectory) return
            withContext(Dispatchers.EDT) {
                if (!project.isDisposed && file.isValid) OpenFileDescriptor(project, file).navigate(true)
            }
        }
        else if (!MarpResourcePaths.isMarpUrl(href) && MarpResourceRequestHandler.isExternalUrl(href)) {
            BrowserUtil.browse(href, project)
        }
    }

    override fun dispose() {
        // Children (browser, JS query, request handler, bus connection, shutdown hook) are disposed by Disposer.
    }

    private companion object {
        const val SET_IDE_THEME = "setIdeTheme"
        const val SET_THEMES = "setThemes"
        const val UPDATE = "update"
        const val SCROLL_TO_LINE = "scrollToLine"
        const val SET_ACTIVE_LINE = "setActiveLine"

        const val MAX_CRASH_RELOADS = 3
        const val CRASH_RELOAD_DELAY_MS = 500L

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
