package cz.p3kj.marp.preview

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.jcef.utils.JBCefStreamResourceHandler
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefRequestHandler
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

private val LOG = logger<MarpResourceRequestHandler>()

/**
 * Serves `https://marp.localhost/app/...` from the plugin jar and `https://marp.localhost/doc/...` from disk (only inside
 * [allowedRoots], and only for requests made by the preview page itself) for one preview browser, answers any other
 * `marp.localhost` URL with 404 ([route]); requests to other hosts go to the network as usual.
 *
 * The main frame never leaves the preview page: every main-frame navigation to anything but an `/app/` URL is cancelled
 * (`about:blank`, `data:`, `file:`, `<meta http-equiv=refresh>` targets would replace the page and its bridge), and
 * user-initiated ones to `http(s)` / `mailto` URLs are handed to [onNavigation], which applies [MarpLinkPolicy]. The page
 * prevents link navigation itself, this is the safety net. Runs on CEF threads, never on the EDT.
 *
 * Responses use [JBCefStreamResourceHandler], which streams the file and works in both in-process and out-of-process
 * JCEF modes.
 */
internal class MarpResourceRequestHandler(
    private val allowedRoots: () -> Collection<Path>,
    private val onNavigation: (url: String) -> Unit,
    private val onRenderProcessGone: () -> Unit,
) : CefRequestHandlerAdapter(), Disposable {

    @Volatile
    private var disposed = false

    private val resourceRequestHandler = object : CefResourceRequestHandlerAdapter() {
        override fun getResourceHandler(browser: CefBrowser?, frame: CefFrame?, request: CefRequest): CefResourceHandler? =
            createResourceHandler(request.url)
    }

    /** Answers 404 without looking at the file system. */
    private val notFoundRequestHandler = object : CefResourceRequestHandlerAdapter() {
        override fun getResourceHandler(browser: CefBrowser?, frame: CefFrame?, request: CefRequest): CefResourceHandler? = notFound()
    }

    override fun getResourceRequestHandler(
        browser: CefBrowser?,
        frame: CefFrame?,
        request: CefRequest,
        isNavigation: Boolean,
        isDownload: Boolean,
        requestInitiator: String?,
        disableDefaultHandling: BoolRef?,
    ): CefResourceRequestHandler? {
        val url = request.url
        return when (route(url, requestInitiator)) {
            Route.NETWORK -> null
            Route.SERVE -> resourceRequestHandler
            Route.NOT_FOUND -> {
                LOG.debug("Answering 404 for $url requested by $requestInitiator")
                notFoundRequestHandler
            }
        }
    }

    override fun onBeforeBrowse(browser: CefBrowser?, frame: CefFrame?, request: CefRequest, userGesture: Boolean, isRedirect: Boolean): Boolean {
        val url = request.url
        return when (navigation(url, mainFrame = frame?.isMain ?: true, userGesture = userGesture)) {
            Navigation.ALLOW -> false
            Navigation.CANCEL -> {
                LOG.debug("Cancelled preview navigation to $url")
                true
            }
            Navigation.CANCEL_AND_OPEN -> {
                onNavigation(url)
                true
            }
        }
    }

    override fun onRenderProcessTerminated(
        browser: CefBrowser?,
        status: CefRequestHandler.TerminationStatus?,
        errorCode: Int,
        errorString: String?,
    ) {
        LOG.warn("Marp preview renderer terminated: $status ($errorCode) $errorString")
        onRenderProcessGone()
    }

    override fun dispose() {
        disposed = true
    }

    private fun createResourceHandler(url: String): CefResourceHandler? {
        if (disposed) return null
        return try {
            when (val target = MarpResourcePaths.parse(url)) {
                is MarpResourcePaths.Target.App -> serveApp(target.resourceName)
                is MarpResourcePaths.Target.Doc -> serveDoc(target.path)
                null -> notFound()
            }
        }
        catch (e: IOException) {
            LOG.debug("Cannot serve $url", e)
            notFound()
        }
        catch (e: RuntimeException) {
            // e.g. the preview was disposed while the request was in flight. A 404 rather than null: null would send
            // this marp.localhost request to the network.
            LOG.debug("Cannot serve $url", e)
            try {
                notFound()
            }
            catch (_: RuntimeException) {
                null // only while this handler is being disposed, together with its browser
            }
        }
    }

    private fun serveApp(resourceName: String): CefResourceHandler? {
        val mimeType = MarpResourcePaths.mimeType(resourceName) ?: return notFound()
        val stream = MarpResourceRequestHandler::class.java.getResourceAsStream(resourceName) ?: return notFound()
        return respond(stream, mimeType)
    }

    private fun serveDoc(path: Path): CefResourceHandler? {
        val mimeType = MarpResourcePaths.mimeType(path.fileName?.toString() ?: return notFound()) ?: return notFound()
        val file = MarpResourcePaths.resolveAllowedFile(path, allowedRoots()) ?: return notFound()
        return respond(Files.newInputStream(file), mimeType)
    }

    private fun respond(stream: InputStream, mimeType: String): CefResourceHandler? {
        if (disposed) {
            stream.close()
            return null
        }
        return try {
            JBCefStreamResourceHandler(stream, mimeType, this, NO_CACHE)
        }
        catch (e: RuntimeException) {
            stream.close()
            throw e
        }
    }

    private fun notFound(): CefResourceHandler? {
        if (disposed) return null
        return NotFoundResourceHandler(this)
    }

    private class NotFoundResourceHandler(parent: Disposable) :
        JBCefStreamResourceHandler(ByteArrayInputStream(NOT_FOUND_BODY), "text/plain", parent, NO_CACHE) {
        override fun getResponseHeaders(response: CefResponse, responseLength: IntRef, redirectUrl: StringRef) {
            super.getResponseHeaders(response, responseLength, redirectUrl)
            response.status = 404
            response.statusText = "Not Found"
        }
    }

    enum class Navigation { ALLOW, CANCEL, CANCEL_AND_OPEN }

    /** How [getResourceRequestHandler] answers a request, see [route]. */
    enum class Route { NETWORK, SERVE, NOT_FOUND }

    companion object {
        private val NO_CACHE = mapOf("Cache-Control" to "no-cache")
        private val NOT_FOUND_BODY = "Not Found".toByteArray(Charsets.UTF_8)

        /**
         * Requests [MarpResourcePaths.parse] accepts are served, except `/doc/` files requested by anything but the
         * preview page itself. Every other URL on host `marp.localhost` (another port, a trailing dot, `http:`, an
         * unknown path) gets 404: it must never fall through to the network, where Chromium resolves `*.localhost` to
         * the loopback interface. Only foreign hosts go to the network.
         */
        fun route(url: String?, requestInitiator: String?): Route {
            val target = url?.let(MarpResourcePaths::parse)
            return when {
                target == null -> if (MarpResourcePaths.hasMarpHost(url)) Route.NOT_FOUND else Route.NETWORK
                target is MarpResourcePaths.Target.Doc && !isPreviewInitiator(requestInitiator) -> Route.NOT_FOUND
                else -> Route.SERVE
            }
        }

        /**
         * The main frame only ever shows `/app/` pages (the preview page and its reloads). Everything else is cancelled;
         * user-initiated navigations to [isExternalUrl] URLs are opened through [MarpLinkPolicy] instead. Subframes
         * cannot load at all (CSP `frame-src 'none'`), so they are left to the CSP.
         */
        fun navigation(url: String?, mainFrame: Boolean, userGesture: Boolean): Navigation = when {
            !mainFrame || url == null -> Navigation.ALLOW
            url.startsWith(MarpResourcePaths.APP_URL_PREFIX, ignoreCase = true) -> Navigation.ALLOW
            userGesture && isExternalUrl(url) -> Navigation.CANCEL_AND_OPEN
            else -> Navigation.CANCEL
        }

        /** Links the preview hands to the IDE: `http(s)` (including `https://marp.localhost/doc/...`) and `mailto`. */
        fun isExternalUrl(url: String): Boolean =
            url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true) ||
            url.startsWith("mailto:", ignoreCase = true)

        /**
         * CEF's request initiator (the origin of the page that made the request): `https://marp.localhost` for the
         * preview's own subresources, empty or `null` for browser-initiated requests.
         */
        fun isPreviewInitiator(initiator: String?): Boolean =
            initiator.isNullOrEmpty() || initiator.removeSuffix("/").equals(MarpResourcePaths.ORIGIN, ignoreCase = true)
    }
}
