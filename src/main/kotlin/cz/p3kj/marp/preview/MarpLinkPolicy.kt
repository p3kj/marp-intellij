package cz.p3kj.marp.preview

import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Path

/**
 * What the IDE does with a link the preview wants to open (a click, a popup or a cancelled navigation), see
 * `docs/ARCHITECTURE.md`. The page itself can never navigate away; everything goes through here.
 */
internal object MarpLinkPolicy {

    sealed interface Action {
        /** Open this local file (real path, inside the allowed roots) in the IDE. */
        data class OpenFile(val path: Path) : Action

        /** Hand this `http(s)` or `mailto` URL to the system browser / mail client. */
        data class Browse(val url: String) : Action

        data object Ignore : Action
    }

    /**
     * `https://marp.localhost/doc/...` opens the file only when it passes the same guard as the resource handler
     * ([MarpResourcePaths.resolveAllowedFile] with [allowedRoots]); any other URL on that host is ignored. Other
     * `http`, `https` (with a host) and `mailto` URLs are browsed; every other scheme is ignored. Does file I/O.
     */
    fun decide(href: String, allowedRoots: Collection<Path>): Action {
        val uri = try {
            URI(href)
        }
        catch (_: URISyntaxException) {
            return Action.Ignore
        }
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> when {
                uri.rawAuthority.isNullOrEmpty() -> Action.Ignore
                isPreviewHost(uri) -> openFile(href, allowedRoots)
                else -> Action.Browse(href)
            }
            "mailto" -> Action.Browse(href)
            else -> Action.Ignore
        }
    }

    /** `marp.localhost` with any user info or port (java.net.URI has no host for some authorities, e.g. with `_`). */
    private fun isPreviewHost(uri: URI): Boolean {
        val host = uri.host ?: uri.rawAuthority.substringAfterLast('@').substringBefore(':')
        return host.equals(MarpResourcePaths.HOST, ignoreCase = true)
    }

    private fun openFile(href: String, allowedRoots: Collection<Path>): Action {
        val target = MarpResourcePaths.parse(href) as? MarpResourcePaths.Target.Doc ?: return Action.Ignore
        val file = MarpResourcePaths.resolveAllowedFile(target.path, allowedRoots) ?: return Action.Ignore
        return Action.OpenFile(file)
    }
}
