package cz.p3kj.marp.preview

import cz.p3kj.marp.preview.MarpLinkPolicy.Action
import cz.p3kj.marp.preview.MarpResourceRequestHandler.Companion.isPreviewInitiator
import cz.p3kj.marp.preview.MarpResourceRequestHandler.Companion.navigation
import cz.p3kj.marp.preview.MarpResourceRequestHandler.Companion.route
import cz.p3kj.marp.preview.MarpResourceRequestHandler.Navigation
import cz.p3kj.marp.preview.MarpResourceRequestHandler.Route
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText

class MarpLinkPolicyTest {

    private lateinit var tmp: Path
    private lateinit var project: Path
    private lateinit var outside: Path

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("marp-links").toRealPath()
        project = tmp.resolve("project").createDirectories()
        outside = tmp.resolve("outside").createDirectories()
        project.resolve("other.md").writeText("---\nmarp: true\n---\n")
        project.resolve("notes.unknown").writeText("?")
        outside.resolve("id_rsa").writeText("secret")
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun decide(href: String) = MarpLinkPolicy.decide(href, listOf(project))

    private fun docUrl(path: Path) = MarpResourcePaths.docUrl(path.toString().replace('\\', '/'), directory = false)

    @Test
    fun opensLocalFilesInsideTheRootsOnly() {
        assertEquals(Action.OpenFile(project.resolve("other.md")), decide(docUrl(project.resolve("other.md"))))
        // Any extension: links may point to other decks or notes, unlike images the resource handler serves.
        assertEquals(Action.OpenFile(project.resolve("notes.unknown")), decide(docUrl(project.resolve("notes.unknown"))))
        assertEquals(Action.Ignore, decide(docUrl(outside.resolve("id_rsa"))))
        assertEquals(Action.Ignore, decide("https://marp.localhost/doc/etc/passwd"))
        assertEquals(Action.Ignore, decide(docUrl(project)))
        assertEquals(Action.Ignore, decide(docUrl(project.resolve("missing.md"))))
        assertEquals(Action.Ignore, decide(MarpResourcePaths.docUrl(project.toString(), directory = true) + "../outside/id_rsa"))
    }

    @Test
    fun neverBrowsesThePreviewHost() {
        val outsidePath = MarpResourcePaths.docUrl(outside.resolve("id_rsa").toString(), directory = false).removePrefix(MarpResourcePaths.DOC_URL_PREFIX)
        for (href in listOf(
            MarpResourcePaths.APP_INDEX_URL,
            "https://marp.localhost/",
            "https://MARP.LOCALHOST/doc/$outsidePath",
            "https://marp.localhost:443/doc/$outsidePath",
            "https://marp.localhost:8443/doc/$outsidePath",
            "http://marp.localhost/doc/$outsidePath",
            "https://user@marp.localhost/doc/$outsidePath",
        )) {
            assertEquals(href, Action.Ignore, decide(href))
        }
    }

    @Test
    fun browsesWebAndMailLinks() {
        for (href in listOf("https://example.com/a?b=c#d", "http://example.com", "HTTPS://Example.com/", "mailto:someone@example.com", "https://my_host.example.com/")) {
            assertEquals(href, Action.Browse(href), decide(href))
        }
    }

    @Test
    fun ignoresEverythingElse() {
        for (href in listOf(
            "javascript:alert(1)", "file:///etc/passwd", "data:text/html,<b>x</b>", "about:blank", "ftp://example.com/x",
            "vscode://file/etc/passwd", "https:///no-host", "https://", "not a url", "", "chrome://settings",
        )) {
            assertEquals(href, Action.Ignore, decide(href))
        }
    }

    @Test
    fun mainFrameOnlyShowsTheAppPage() {
        assertEquals(Navigation.ALLOW, navigation(MarpResourcePaths.APP_INDEX_URL, mainFrame = true, userGesture = false))
        assertEquals(Navigation.ALLOW, navigation(MarpResourcePaths.APP_INDEX_URL, mainFrame = true, userGesture = true))
        for (url in listOf("about:blank", "data:text/html,x", "file:///etc/passwd", "javascript:1", "https://marp.localhost/doc/tmp/a.html")) {
            assertEquals(url, Navigation.CANCEL, navigation(url, mainFrame = true, userGesture = false))
        }
        // <meta http-equiv=refresh> and script navigations have no user gesture: cancelled, nothing opens.
        assertEquals(Navigation.CANCEL, navigation("https://evil.example/", mainFrame = true, userGesture = false))
        assertEquals(Navigation.CANCEL, navigation("about:blank", mainFrame = true, userGesture = true))
        assertEquals(Navigation.CANCEL_AND_OPEN, navigation("https://example.com/", mainFrame = true, userGesture = true))
        assertEquals(Navigation.CANCEL_AND_OPEN, navigation("https://marp.localhost/doc/tmp/a.md", mainFrame = true, userGesture = true))
        assertEquals(Navigation.CANCEL_AND_OPEN, navigation("mailto:a@example.com", mainFrame = true, userGesture = true))
        assertEquals(Navigation.ALLOW, navigation("https://example.com/", mainFrame = false, userGesture = false))
    }

    @Test
    fun everyRequestToThePreviewHostIsAnsweredLocally() {
        val doc = docUrl(project.resolve("other.md"))
        val docPath = doc.removePrefix(MarpResourcePaths.ORIGIN)
        assertEquals(Route.SERVE, route(MarpResourcePaths.APP_INDEX_URL, null))
        assertEquals(Route.SERVE, route(doc, "https://marp.localhost"))
        // The default port is the same origin: served (or refused) here, never sent to loopback:443.
        assertEquals(Route.SERVE, route("https://marp.localhost:443$docPath", "https://marp.localhost"))
        assertEquals(Route.SERVE, route("https://marp.localhost:443/app/index.html", null))
        assertEquals(Route.NOT_FOUND, route("https://marp.localhost:443$docPath", "https://evil.example"))
        assertEquals(Route.NOT_FOUND, route(doc, "https://evil.example"))
        for (url in listOf(
            "https://marp.localhost./app/index.html",
            "https://marp.localhost.$docPath",
            "https://MARP.LOCALHOST.:443$docPath",
            "https://marp.localhost:8443/app/index.html",
            "http://marp.localhost/app/index.html",
            "https://marp.localhost/other/a.png",
            "https://marp.localhost/doc/%2e%2e/etc/passwd",
            "https://marp.localhost/",
        )) {
            assertEquals(url, Route.NOT_FOUND, route(url, null))
        }
        for (url in listOf("https://example.com/a.png", "https://marp.localhost.evil.com/app/index.html", "data:image/png;base64,AA==", null)) {
            assertEquals("$url", Route.NETWORK, route(url, null))
        }
    }

    @Test
    fun localFilesOnlyForThePreviewPagesOwnRequests() {
        for (initiator in listOf(null, "", "https://marp.localhost", "https://marp.localhost/", "HTTPS://MARP.LOCALHOST")) {
            assertTrue("$initiator", isPreviewInitiator(initiator))
        }
        for (initiator in listOf("https://marp.localhost.evil.com", "https://example.com", "null", "http://marp.localhost", "https://marp.localhost:8443")) {
            assertFalse(initiator, isPreviewInitiator(initiator))
        }
    }
}
