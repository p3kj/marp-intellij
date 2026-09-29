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
 * [allowedRoots]) for one preview browser; every other request goes to the network as usual.
 *
 * Main-frame navigations away from the preview page are cancelled and handed to [onNavigation] (the page prevents link
 * navigation itself, this is the safety net). Runs on CEF threads, never on the EDT.
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

    override fun getResourceRequestHandler(
        browser: CefBrowser?,
        frame: CefFrame?,
        request: CefRequest,
        isNavigation: Boolean,
        isDownload: Boolean,
        requestInitiator: String?,
        disableDefaultHandling: BoolRef?,
    ): CefResourceRequestHandler? = if (MarpResourcePaths.isMarpUrl(request.url)) resourceRequestHandler else null

    override fun onBeforeBrowse(browser: CefBrowser?, frame: CefFrame?, request: CefRequest, userGesture: Boolean, isRedirect: Boolean): Boolean {
        if (frame != null && !frame.isMain) return false
        val url = request.url ?: return false
        if (url.startsWith(MarpResourcePaths.APP_URL_PREFIX, ignoreCase = true)) return false
        if (!isExternalUrl(url)) return false
        if (userGesture) onNavigation(url)
        return true
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
            // e.g. the preview was disposed while the request was in flight
            LOG.debug("Cannot serve $url", e)
            null
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

    companion object {
        private val NO_CACHE = mapOf("Cache-Control" to "no-cache")
        private val NOT_FOUND_BODY = "Not Found".toByteArray(Charsets.UTF_8)

        /** Links the preview hands to the IDE: `http(s)` (including `https://marp.localhost/doc/...`) and `mailto`. */
        fun isExternalUrl(url: String): Boolean =
            url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true) ||
            url.startsWith("mailto:", ignoreCase = true)
    }
}
