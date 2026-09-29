package cz.p3kj.marp.themes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText

class MarpThemeFolderTest {

    private lateinit var tmp: Path

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("marp-folder").toRealPath()
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun write(relative: String): Path = tmp.resolve(relative).also {
        it.parent.createDirectories()
        it.writeText("/* $relative */")
    }

    private fun found(dir: Path, maxFiles: Int = MarpThemeFolder.MAX_CSS_FILES): List<String> =
        MarpThemeFolder.findCssFiles(dir, maxFiles).files.map { dir.relativize(it).toString().replace('\\', '/') }

    @Test
    fun findsNestedCssFilesSorted() {
        for (name in listOf("t/b.css", "t/a.CSS", "t/sub/c.css", "t/sub/deeper/d.css", "t/readme.md", "t/sub/style.scss")) write(name)
        assertEquals(listOf("a.CSS", "b.css", "sub/c.css", "sub/deeper/d.css"), found(tmp.resolve("t")))
        assertFalse(MarpThemeFolder.findCssFiles(tmp.resolve("t")).truncated)
    }

    @Test
    fun skipsNodeModulesAndHiddenDirectoriesAndFiles() {
        for (name in listOf(
            "t/a.css", "t/node_modules/pkg/x.css", "t/sub/node_modules/y.css", "t/.git/z.css", "t/.cache/deep/h.css",
            "t/sub/.hidden/i.css", "t/.dotfile.css", "t/sub/ok.css",
        )) {
            write(name)
        }
        assertEquals(listOf("a.css", "sub/ok.css"), found(tmp.resolve("t")))
    }

    @Test
    fun aHiddenFolderEntryIsStillSearched() {
        write(".themes/a.css")
        assertEquals(listOf("a.css"), found(tmp.resolve(".themes")))
    }

    @Test
    fun depthIsCapped() {
        write("t/1/2/3/4/5/6/7/deepest.css")
        write("t/1/2/3/4/5/6/7/8/too-deep.css")
        assertEquals(listOf("1/2/3/4/5/6/7/deepest.css"), found(tmp.resolve("t")))
    }

    @Test
    fun numberOfFilesIsCapped() {
        repeat(5) { write("t/f$it.css") }
        val limited = MarpThemeFolder.findCssFiles(tmp.resolve("t"), maxFiles = 3)
        assertEquals(3, limited.files.size)
        assertTrue(limited.truncated)
        val exact = MarpThemeFolder.findCssFiles(tmp.resolve("t"), maxFiles = 5)
        assertEquals(5, exact.files.size)
        assertFalse(exact.truncated)
    }

    @Test
    fun symlinkedFolderKeepsItsOwnPathsAndInnerDirectoryLinksAreNotFollowed() {
        write("real/a.css")
        write("real/sub/b.css")
        write("elsewhere/c.css")
        val link = tmp.resolve("link")
        try {
            Files.createSymbolicLink(link, tmp.resolve("real"))
            Files.createSymbolicLink(tmp.resolve("real/linked-dir"), tmp.resolve("elsewhere"))
            Files.createSymbolicLink(tmp.resolve("real/linked.css"), tmp.resolve("elsewhere/c.css"))
        }
        catch (e: Exception) {
            assumeTrue("symlinks not supported: $e", false)
        }
        val files = MarpThemeFolder.findCssFiles(link).files
        assertEquals(listOf(link.resolve("a.css"), link.resolve("linked.css"), link.resolve("sub/b.css")), files)
    }

    @Test
    fun skippedNames() {
        for (name in listOf("node_modules", ".git", ".idea", ".")) assertTrue(name, MarpThemeFolder.isSkippedDirectory(name))
        for (name in listOf("themes", "node_modules2", "build")) assertFalse(name, MarpThemeFolder.isSkippedDirectory(name))
        assertTrue(MarpThemeFolder.isThemeFileName("Theme.CSS"))
        assertFalse(MarpThemeFolder.isThemeFileName(".theme.css"))
        assertFalse(MarpThemeFolder.isThemeFileName("theme.css.map"))
    }
}
