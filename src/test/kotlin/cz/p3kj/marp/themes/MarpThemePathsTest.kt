package cz.p3kj.marp.themes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

class MarpThemePathsTest {

    private lateinit var tmp: Path
    private lateinit var base: Path

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("marp-theme-paths").toRealPath()
        base = tmp.resolve("project").createDirectories()
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    @Test
    fun schemes() {
        for (entry in listOf("https://example.com/a.css", "ftp://host/a.css", "file:///etc/x.css", "vscode-resource://x")) {
            assertTrue(entry, MarpThemePaths.hasScheme(entry))
        }
        for (entry in listOf("C:\\themes\\a.css", "C:/themes/a.css", "themes/a.css", "./a.css", "/abs/a.css", "c://x")) {
            assertFalse(entry, MarpThemePaths.hasScheme(entry) && !entry.startsWith("c://"))
        }
        assertTrue(MarpThemePaths.isHttpUrl("HTTPS://example.com"))
        assertTrue(MarpThemePaths.isHttpUrl("http://example.com"))
        assertFalse(MarpThemePaths.isHttpUrl("ftp://example.com"))
        assertFalse(MarpThemePaths.isHttpUrl("https-themes/a.css"))
    }

    @Test
    fun resolvesRelativeEntriesAgainstTheBaseAndNormalizes() {
        assertEquals(base.resolve("a.css"), MarpThemePaths.resolve(base, "a.css"))
        assertEquals(base.resolve("themes/a.css"), MarpThemePaths.resolve(base, " ./themes/a.css "))
        assertEquals(base.resolve("b.css"), MarpThemePaths.resolve(base, "themes/../b.css"))
        assertEquals(tmp.resolve("outside.css"), MarpThemePaths.resolve(base, "../outside.css"))
        val absolute = tmp.resolve("x/../abs.css")
        assertEquals(tmp.resolve("abs.css"), MarpThemePaths.resolve(base, absolute.toString()))
    }

    @Test
    fun relativeEntriesNeedABaseDirectory() {
        assertNull(MarpThemePaths.resolve(null, "a.css"))
        assertNull(MarpThemePaths.resolve(null, "./themes"))
        assertEquals(tmp.resolve("abs.css"), MarpThemePaths.resolve(null, tmp.resolve("abs.css").toString()))
        assertNull(MarpThemePaths.resolve(base, "bad\u0000name.css"))
    }

    @Test
    fun storedEntriesAreProjectRelativeWithSlashesWhenInside() {
        assertEquals("themes/a.css", MarpThemePaths.toStored(base.resolve("themes/a.css"), base))
        assertEquals("a.css", MarpThemePaths.toStored(base.resolve("sub/../a.css"), base))
        val outside = tmp.resolve("outside/a.css")
        assertEquals(outside.toString().replace('\\', '/'), MarpThemePaths.toStored(outside, base))
        // The base itself and a sibling with the same prefix stay absolute.
        assertEquals(base.toString().replace('\\', '/'), MarpThemePaths.toStored(base, base))
        val sibling = tmp.resolve("project-evil/a.css")
        assertEquals(sibling.toString().replace('\\', '/'), MarpThemePaths.toStored(sibling, base))
        assertEquals(outside.toString().replace('\\', '/'), MarpThemePaths.toStored(outside, null))
    }

    @Test
    fun keysUseTheRealPathWhenTheFileExists() {
        val file = base.resolve("a.css").also { it.writeText("a") }
        assertEquals(file.toString().replace('\\', '/'), MarpThemePaths.key(base.resolve("sub/../a.css")))
        val missing = base.resolve("missing/../missing.css")
        assertEquals(base.resolve("missing.css").toString().replace('\\', '/'), MarpThemePaths.key(missing))
        assertEquals(base.resolve("x.css").toString().replace('\\', '/'), MarpThemePaths.normalizedKey(base.resolve("./x.css")))
    }

    @Test
    fun confinement() {
        base.resolve("themes").createDirectories().resolve("a.css").writeText("a")
        tmp.resolve("outside.css").writeText("o")
        assertTrue(MarpThemePaths.isConfinedTo(base.resolve("themes/a.css"), base))
        assertTrue(MarpThemePaths.isConfinedTo(base, base))
        assertTrue("missing files are checked again when they appear", MarpThemePaths.isConfinedTo(base.resolve("later.css"), base))
        assertFalse(MarpThemePaths.isConfinedTo(tmp.resolve("outside.css"), base))
        assertFalse(MarpThemePaths.isConfinedTo(base.resolve("../outside.css"), base))
        assertFalse(MarpThemePaths.isConfinedTo(tmp.resolve("project-evil/a.css"), base))
    }

    @Test
    fun confinementFollowsSymlinks() {
        val outside = tmp.resolve("outside").createDirectories()
        outside.resolve("evil.css").writeText("evil")
        try {
            Files.createSymbolicLink(base.resolve("linked.css"), outside.resolve("evil.css"))
            Files.createSymbolicLink(base.resolve("linked-dir"), outside)
        }
        catch (e: Exception) {
            assumeTrue("symlinks not supported: $e", false)
        }
        assertFalse(MarpThemePaths.isConfinedTo(base.resolve("linked.css"), base))
        assertFalse(MarpThemePaths.isConfinedTo(base.resolve("linked-dir"), base))
        assertFalse(MarpThemePaths.isConfinedTo(base.resolve("linked-dir/evil.css"), base))
    }
}
