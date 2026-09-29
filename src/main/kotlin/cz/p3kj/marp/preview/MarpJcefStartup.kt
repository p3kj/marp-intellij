package cz.p3kj.marp.preview

import com.intellij.openapi.diagnostic.logger
import com.intellij.util.net.ProxySettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val LOG = logger<MarpJcefStartup>()

/**
 * Work that has to happen before the first JCEF browser of the IDE session is created.
 *
 * Creating the first browser starts JCEF: `JBCefApp`'s class initializer reads the IDE proxy settings to pass them to
 * Chromium. When the platform has not read them yet, that creates the proxy settings service, whose initialization
 * requests another service from inside the class initializer. The platform reports that as an IDE error and blames the
 * plugin that created the browser. The platform itself reads the proxy settings on its first HTTP request, which is
 * usually but not always done before a restored editor is created at startup. [prepare] reads them outside any class
 * initializer, so the services exist before JCEF starts.
 */
internal object MarpJcefStartup {

    @Volatile
    private var prepared = false

    /** Cheap after the first call. Never throws, except for cancellation. */
    suspend fun prepare() {
        if (prepared) return
        withContext(Dispatchers.IO) {
            try {
                ProxySettings.getInstance().getProxyConfiguration()
            }
            catch (e: CancellationException) {
                throw e
            }
            catch (e: Exception) {
                LOG.warn("Cannot read the IDE proxy settings before starting JCEF", e)
            }
        }
        prepared = true
    }
}
