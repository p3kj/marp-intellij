package cz.p3kj.marp.preview

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText

class MarpResourcePathsTest {

    private lateinit var tmp: Path
    private lateinit var project: Path
    private lateinit var evil: Path
    private lateinit var outside: Path

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("marp-paths").toRealPath()
        project = tmp.resolve("project").createDirectories()
        evil = tmp.resolve("project-evil").createDirectories()
        outside = tmp.resolve("outside").createDirectories()
        project.resolve("img").createDirectories()
        project.resolve("img/logo.png").writeText("png")
        project.resolve("img/space dir").createDirectories()
        project.resolve("img/space dir/ä b.jpg").writeText("jpg")
        project.resolve("notes.unknown").writeText("?")
        evil.resolve("secret.png").writeText("secret")
        outside.resolve("secret.png").writeText("secret")
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private val roots get() = listOf(project)

    /** Resolves a `/doc/` URL the way the request handler does. */
    private fun resolve(url: String): Path? {
        val target = MarpResourcePaths.parse(url) as? MarpResourcePaths.Target.Doc ?: return null
        return MarpResourcePaths.resolveAllowedFile(target.path, roots)
    }

    private fun docUrl(path: Path) = MarpResourcePaths.docUrl(path.toString().replace('\\', '/'), directory = false)

    @Test
    fun servesFilesInsideTheRoots() {
        assertEquals(project.resolve("img/logo.png"), resolve(docUrl(project.resolve("img/logo.png"))))
        val encoded = docUrl(project.resolve("img/space dir/ä b.jpg"))
        assertTrue(encoded, encoded.endsWith("/img/space%20dir/%C3%A4%20b.jpg"))
        assertEquals(project.resolve("img/space dir/ä b.jpg"), resolve(encoded))
    }

    @Test
    fun docUrlOfDirectoryEndsWithSlash() {
        assertEquals("https://marp.localhost/doc/home/me/My%20Deck/", MarpResourcePaths.docUrl("/home/me/My Deck", directory = true))
        assertEquals("https://marp.localhost/doc/C:/Users/me/deck/", MarpResourcePaths.docUrl("C:/Users/me/deck", directory = true))
        assertEquals("https://marp.localhost/doc/C:/Users/me/deck/", MarpResourcePaths.docUrl("C:\\Users\\me\\deck", directory = true))
        assertEquals("https://marp.localhost/doc/a%23b/c%3Fd/100%25", MarpResourcePaths.docUrl("/a#b/c?d/100%", directory = false))
    }

    @Test
    fun rejectsDotDotSegments() {
        val base = MarpResourcePaths.docUrl(project.toString(), directory = true)
        assertNull(MarpResourcePaths.parse(base + "../outside/secret.png"))
        assertNull(MarpResourcePaths.parse(base + "img/../../outside/secret.png"))
        assertNull(MarpResourcePaths.parse(base + "./img/logo.png"))
    }

    @Test
    fun rejectsEncodedTraversal() {
        val base = MarpResourcePaths.docUrl(project.toString(), directory = true)
        assertNull(MarpResourcePaths.parse(base + "%2e%2e/outside/secret.png"))
        assertNull(MarpResourcePaths.parse(base + "%2E%2E/outside/secret.png"))
        assertNull(MarpResourcePaths.parse(base + ".%2e/outside/secret.png"))
        assertNull(MarpResourcePaths.parse(base + "..%2Foutside%2Fsecret.png"))
        assertNull(MarpResourcePaths.parse(base + "img%5C..%5C..%5Coutside%5Csecret.png"))
        assertNull(MarpResourcePaths.parse(base + "img/logo.png%00.png"))
        assertNull(MarpResourcePaths.parse(base + "img/%zz.png"))
        assertNull(MarpResourcePaths.parse(base + "img/%C3.png"))
    }

    @Test
    fun rejectsAbsolutePathsOutsideTheRoots() {
        assertNull(resolve(docUrl(outside.resolve("secret.png"))))
        assertNull(resolve("https://marp.localhost/doc/etc/passwd"))
    }

    @Test
    fun rejectsSiblingWithSamePrefix() {
        assertNull(resolve(docUrl(evil.resolve("secret.png"))))
    }

    @Test
    fun rejectsSymlinkEscapingTheRoot() {
        val link = project.resolve("link.png")
        try {
            Files.createSymbolicLink(link, outside.resolve("secret.png"))
        }
        catch (e: Exception) {
            assumeTrue("symlinks not supported: $e", false)
        }
        assertNull(resolve(docUrl(link)))
    }

    @Test
    fun rejectsDirectoriesAndMissingFiles() {
        assertNull(resolve(docUrl(project.resolve("img"))))
        assertNull(resolve(docUrl(project.resolve("img/missing.png"))))
    }

    @Test
    fun rejectsOtherOriginsAndPaths() {
        assertNull(MarpResourcePaths.parse("http://marp.localhost/doc/tmp/a.png"))
        assertNull(MarpResourcePaths.parse("https://marp.localhost.evil.com/doc/tmp/a.png"))
        assertNull(MarpResourcePaths.parse("https://example.com/doc/tmp/a.png"))
        assertNull(MarpResourcePaths.parse("https://marp.localhost:8443/doc/tmp/a.png"))
        assertNull(MarpResourcePaths.parse("https://marp.localhost/other/a.png"))
        assertNull(MarpResourcePaths.parse("https://marp.localhost/doc/"))
        assertNull(MarpResourcePaths.parse("not a url"))
    }

    @Test
    fun windowsDrivePathsAreParsedAsDrivePaths() {
        val target = MarpResourcePaths.parse("https://marp.localhost/doc/C:/Users/me/a.png")
        if (System.getProperty("os.name").lowercase().startsWith("windows")) {
            assertEquals(MarpResourcePaths.Target.Doc(Path.of("C:/Users/me/a.png")), target)
        }
        else {
            // "C:/Users/..." is not an absolute path here, so it is never served.
            assertNull(target)
        }
    }

    private val isWindows = System.getProperty("os.name").lowercase().startsWith("windows")

    /** The raw `/doc/` part of a URL, as [MarpResourcePaths.parse] hands it to `docPath`. */
    private fun rawDocPart(url: String): String = java.net.URI(url).rawPath.removePrefix("/doc/")

    @Test
    fun uncPathsKeepTheirDoubleSlash() {
        val url = MarpResourcePaths.docUrl("//server/share/My Deck", directory = true)
        assertEquals("https://marp.localhost/doc//server/share/My%20Deck/", url)
        assertEquals(url, MarpResourcePaths.docUrl("\\\\server\\share\\My Deck", directory = true))
        // A relative reference below that base (browsers keep the empty segment; java.net.URI.resolve would drop it).
        val image = url + "img/a%20b.png"
        assertEquals("//server/share/My Deck/img/a b.png", MarpResourcePaths.docPathString(rawDocPart(image)))
    }

    @Test
    fun docPathStringsRoundTrip() {
        for (path in listOf("/home/me/My Deck/a.png", "C:/Users/me/a.png", "//server/share/a.png", "/UNC/x/y.png", "/a#b/c?d/100%")) {
            val url = MarpResourcePaths.docUrl(path, directory = false)
            assertEquals(url, path, MarpResourcePaths.docPathString(rawDocPart(url)))
        }
    }

    @Test
    fun uncPathsNeedServerAndShareAndStaySafe() {
        assertNull(MarpResourcePaths.docPathString("/server"))
        assertNull(MarpResourcePaths.docPathString("/server/share/../x"))
        assertNull(MarpResourcePaths.docPathString("/server/%2e%2e/x"))
        assertEquals("//server/share", MarpResourcePaths.docPathString("/server/share"))
    }

    @Test
    fun uncPathsAreParsedOnWindowsOnly() {
        val target = MarpResourcePaths.parse("https://marp.localhost/doc//server/share/a.png")
        if (isWindows) {
            assertEquals(MarpResourcePaths.Target.Doc(Path.of("\\\\server\\share\\a.png")), target)
        }
        else {
            // "//server/share/a.png" would silently become "/server/share/a.png" here.
            assertNull(target)
        }
    }

    @Test
    fun appResources() {
        assertEquals(MarpResourcePaths.Target.App("/webview/index.html"), MarpResourcePaths.parse(MarpResourcePaths.APP_INDEX_URL))
        assertEquals(MarpResourcePaths.Target.App("/webview/fonts/a.woff2"), MarpResourcePaths.parse("https://marp.localhost/app/fonts/a.woff2?v=1#x"))
        assertNull(MarpResourcePaths.parse("https://marp.localhost/app/../doc/etc/passwd"))
        assertNull(MarpResourcePaths.parse("https://marp.localhost/app/%2e%2e/secret"))
        assertNull(MarpResourcePaths.parse("https://marp.localhost/app/"))
    }

    @Test
    fun percentDecodingKeepsPlus() {
        assertEquals("a+b c", MarpResourcePaths.percentDecode("a+b%20c"))
        assertEquals("ä", MarpResourcePaths.percentDecode("%C3%A4"))
        assertNull(MarpResourcePaths.percentDecode("%"))
        assertNull(MarpResourcePaths.percentDecode("%4"))
        assertNull(MarpResourcePaths.percentDecode("%FF"))
    }

    @Test
    fun marpHosts() {
        for (url in listOf(
            "https://marp.localhost/app/index.html",
            "https://MARP.LOCALHOST/doc/x",
            "https://marp.localhost:443/doc/x",
            "https://marp.localhost:8443/doc/x",
            "https://marp.localhost./doc/x",
            "http://marp.localhost/doc/x",
            "https://user:pw@marp.localhost/doc/x",
            "https://marp.localhost",
            "https://marp.localhost?x",
            "https://marp.localhost\\doc\\x",
        )) {
            assertTrue(url, MarpResourcePaths.hasMarpHost(url))
        }
        for (url in listOf(
            "https://marp.localhost.evil.com/app/index.html",
            "https://evil.com/marp.localhost/",
            "https://evil.com/?h=https://marp.localhost/",
            "https://marp.localhost@evil.com/",
            "https://localhost/",
            "about:blank",
            "marp.localhost",
            "",
            null,
        )) {
            assertFalse("$url", MarpResourcePaths.hasMarpHost(url))
        }
    }

    /** Records every path that reaches the file system through the real-path lookup. */
    private class RecordingRealPath {
        val calls = mutableListOf<Path>()
        val lookup: (Path) -> Path = { path -> calls.add(path); path.toRealPath() }
    }

    @Test
    fun uncDocPathWithNonUncRootsIsRejectedWithoutFileSystemAccess() {
        // On Windows this is \\host\share\x.png; elsewhere it is /host/share/x.png. Either way it is outside the roots.
        val recording = RecordingRealPath()
        assertNull(MarpResourcePaths.resolveAllowedFile(Path.of("//host/share/x.png"), listOf(tmp), recording.lookup))
        assertEquals(emptyList<Path>(), recording.calls)
        assertFalse(MarpResourcePaths.isLexicallyInside(Path.of("//host/share/x.png"), listOf(tmp)))
    }

    @Test
    fun uncPathsNeedAUncRoot() {
        assumeTrue("UNC paths exist on Windows only", isWindows)
        val share = Path.of("\\\\host\\share\\")
        assertFalse(MarpResourcePaths.isLexicallyInside(Path.of("\\\\host\\share\\x.png"), listOf(Path.of("C:\\project"))))
        assertTrue(MarpResourcePaths.isLexicallyInside(Path.of("\\\\host\\share\\deck\\x.png"), listOf(share)))
        assertFalse(MarpResourcePaths.isLexicallyInside(Path.of("\\\\other\\share\\x.png"), listOf(share)))
    }

    @Test
    fun pathsOutsideEveryRootNeverReachTheFileSystem() {
        val recording = RecordingRealPath()
        val missingSibling = tmp.resolve("missing-sibling/secret.png")
        for (path in listOf(outside.resolve("secret.png"), evil.resolve("secret.png"), missingSibling, Path.of("/etc/passwd"))) {
            assertNull("$path", MarpResourcePaths.resolveAllowedFile(path, roots, recording.lookup))
        }
        // Lexically inside, but `..` leaves the root: normalized before the check.
        assertNull(MarpResourcePaths.resolveAllowedFile(project.resolve("img/../../outside/secret.png"), roots, recording.lookup))
        assertNull(MarpResourcePaths.resolveAllowedFile(Path.of("relative/logo.png"), listOf(Path.of("relative")), recording.lookup))
        assertEquals(emptyList<Path>(), recording.calls)
    }

    @Test
    fun rootsThatDoNotExistServeNothing() {
        val recording = RecordingRealPath()
        val missingRoot = tmp.resolve("no-such-root")
        assertNull(MarpResourcePaths.resolveAllowedFile(missingRoot.resolve("x.png"), listOf(missingRoot), recording.lookup))
        assertEquals("only the requested path was looked up", listOf(missingRoot.resolve("x.png")), recording.calls)
    }

    @Test
    fun inRootFilesAreStillServedAfterTheLexicalGate() {
        val recording = RecordingRealPath()
        val logo = project.resolve("img/logo.png")
        assertEquals(logo, MarpResourcePaths.resolveAllowedFile(logo, roots, recording.lookup))
        assertEquals(listOf(logo, project), recording.calls)
        assertEquals(logo, MarpResourcePaths.resolveAllowedFile(project.resolve("img/./logo.png"), roots))
    }

    @Test
    fun symlinkInsideTheRootToOutsideIsStillRejected() {
        val link = project.resolve("img/escape.png")
        try {
            Files.createSymbolicLink(link, outside.resolve("secret.png"))
        }
        catch (e: Exception) {
            assumeTrue("symlinks not supported: $e", false)
        }
        // Passes the lexical gate (the link is inside), fails the real-path check.
        assertTrue(MarpResourcePaths.isLexicallyInside(link, roots))
        assertNull(MarpResourcePaths.resolveAllowedFile(link, roots))
    }

    @Test
    fun mimeTypes() {
        val expected = mapOf(
            "index.html" to "text/html",
            "marp-preview.js" to "text/javascript",
            "marp-preview.css" to "text/css",
            "a.png" to "image/png",
            "a.JPG" to "image/jpeg",
            "a.jpeg" to "image/jpeg",
            "a.gif" to "image/gif",
            "a.webp" to "image/webp",
            "a.avif" to "image/avif",
            "a.svg" to "image/svg+xml",
            "favicon.ico" to "image/x-icon",
            "a.bmp" to "image/bmp",
            "a.woff" to "font/woff",
            "a.woff2" to "font/woff2",
            "a.ttf" to "font/ttf",
            "a.otf" to "font/otf",
            "a.mp4" to "video/mp4",
            "a.webm" to "video/webm",
            "a.ogg" to "audio/ogg",
            "a.mp3" to "audio/mpeg",
            "a.wav" to "audio/wav",
            "data.json" to "application/json",
            "notes.txt" to "text/plain",
        )
        for ((name, mime) in expected) assertEquals(name, mime, MarpResourcePaths.mimeType(name))
        assertNull(MarpResourcePaths.mimeType("notes.unknown"))
        assertNull(MarpResourcePaths.mimeType("Makefile"))
        assertNotNull(MarpResourcePaths.mimeType("deck.md.png"))
    }
}
