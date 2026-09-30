package cz.p3kj.marp.export

import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

class MarpPresentFilesTest : TestCase() {

    private val created = mutableListOf<Path>()

    override fun tearDown() {
        try {
            // Children were added after their parents.
            created.asReversed().forEach { Files.deleteIfExists(it) }
        } finally {
            super.tearDown()
        }
    }

    fun testBaseHrefIsAFileUrlOfTheFolderWithATrailingSlash() {
        val directory = Files.createTempDirectory("marp base ")
        created.add(directory)
        val href = MarpPresentFiles.baseHref(directory)
        assertTrue(href, href.startsWith("file:"))
        assertTrue(href, href.endsWith("/"))
        assertFalse("no double slash at the end: $href", href.endsWith("//"))
        assertEquals(directory, Path.of(java.net.URI(href)))
    }

    fun testBaseHrefEncodesCharactersThatAreSpecialInAUrl() {
        // `?` is not allowed in Windows file names.
        if (System.getProperty("os.name").startsWith("Windows")) return
        val directory = Files.createTempDirectory("marp base ").resolve("a#b?c%d ü")
        created.add(directory.parent)
        Files.createDirectory(directory)
        created.add(directory)
        val href = MarpPresentFiles.baseHref(directory)
        assertFalse("no raw #: $href", href.contains('#'))
        assertFalse("no raw ?: $href", href.contains('?'))
        assertTrue(href, href.contains("%23"))
        assertTrue(href, href.contains("%3F"))
        assertTrue(href, href.contains("%25"))
        assertTrue(href, href.endsWith("/"))
        assertEquals(directory, Path.of(java.net.URI(href)))
    }

    fun testBaseHrefEncodesSpacesAndKeepsAFolderThatDoesNotExistAFolder() {
        val directory = Files.createTempDirectory("marp base ").resolve("sub folder")
        created.add(directory.parent)
        assertFalse(Files.exists(directory))
        val href = MarpPresentFiles.baseHref(directory)
        assertTrue(href, href.endsWith("/sub%20folder/"))
        assertFalse(href, href.contains(' '))
    }

    fun testWriteCreatesAReadableHtmlFileWithTheText() {
        val html = "<!DOCTYPE html><title>Ünïcode ✓</title>\n"
        val path = MarpPresentFiles.write(html)
        created.add(path)
        assertTrue(path.toString(), path.fileName.toString().endsWith(".html"))
        assertTrue(path.fileName.toString().startsWith("marp-present-"))
        assertEquals(html, Files.readString(path))
        assertEquals(html.toByteArray(Charsets.UTF_8).toList(), Files.readAllBytes(path).toList())
    }

    fun testEveryPresentGetsAFileOfItsOwn() {
        val first = MarpPresentFiles.write("one")
        val second = MarpPresentFiles.write("two")
        created.addAll(listOf(first, second))
        assertFalse(first == second)
        assertEquals("one", Files.readString(first))
        assertEquals("two", Files.readString(second))
    }

    fun testTheFileIsOnlyReadableByItsOwnerWhereTheFileSystemHasPermissions() {
        val path = MarpPresentFiles.write("secret")
        created.add(path)
        val view = Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView::class.java) ?: return
        val permissions = view.readAttributes().permissions()
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), permissions)
    }

    // The page of Marp CLI ---------------------------------------------------------------------------------------------

    private val page = "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\"><title>Deck</title></head><body><header>H</header></body></html>"

    private fun scriptOf(html: String): String = html.substringAfter("<script>").substringBefore("</script>")

    fun testNewFileIsAnEmptyOwnerOnlyHtmlFileOfItsOwn() {
        val first = MarpPresentFiles.newFile()
        val second = MarpPresentFiles.newFile()
        created.addAll(listOf(first, second))
        assertFalse(first == second)
        assertTrue(first.fileName.toString().startsWith("marp-present-"))
        assertTrue(first.fileName.toString().endsWith(".html"))
        assertEquals(0L, Files.size(first))
        val view = Files.getFileAttributeView(first, java.nio.file.attribute.PosixFileAttributeView::class.java) ?: return
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), view.readAttributes().permissions())
    }

    fun testTheBaseAndTheScriptComeRightAfterTheHeadTag() {
        val html = MarpPresentFiles.cliPresentation(page, "file:///work/talk/", 2)
        assertTrue(html, html.startsWith("<!DOCTYPE html><html lang=\"en\"><head><base href=\"file:///work/talk/\"><script>"))
        assertTrue(html, html.contains("</script><meta charset=\"UTF-8\"><title>Deck</title></head><body><header>H</header></body></html>"))
        assertEquals("one base", 1, Regex("<base ").findAll(html).count())
        assertEquals("one script", 1, Regex("<script>").findAll(html).count())
    }

    fun testTheSlideIsOneBasedInTheAbsoluteAddress() {
        assertTrue(scriptOf(MarpPresentFiles.cliPresentation(page, "file:///w/", 2)).contains("+\"#3\");"))
        assertTrue(scriptOf(MarpPresentFiles.cliPresentation(page, "file:///w/", 0)).contains("+\"#1\");"))
        assertTrue(scriptOf(MarpPresentFiles.cliPresentation(page, "file:///w/", -1)).contains("+\"#1\");"))
        val script = scriptOf(MarpPresentFiles.cliPresentation(page, "file:///w/", 4))
        // An address the template or the user already set (a reload keeps the slide) wins, and it is absolute: the base would move a relative one.
        assertTrue(script, script.startsWith("if(!location.hash)history.replaceState(null,\"\",location.href.split(\"#\")[0]+\"#5\");"))
    }

    fun testLinksToASlideAreFollowedByHand() {
        val script = scriptOf(MarpPresentFiles.cliPresentation(page, "file:///w/", 0))
        assertTrue(script, script.contains("closest('a[href^=\"#\"]')"))
        assertTrue(script, script.contains("e.preventDefault();location.hash=a.getAttribute(\"href\")"))
        assertTrue("in the capture phase, before the template", script.endsWith("},true);"))
    }

    fun testTheHeadTagMayHaveAttributesAndAnyCase() {
        val html = MarpPresentFiles.cliPresentation("<html><HEAD lang=\"x\"><meta></HEAD><body></body></html>", "file:///w/", 0)
        assertTrue(html, html.startsWith("<html><HEAD lang=\"x\"><base href=\"file:///w/\"><script>"))
        assertTrue(html, html.endsWith("</script><meta></HEAD><body></body></html>"))
    }

    fun testAHeaderElementIsNotTheHead() {
        val html = "<html><body><header>H</header></body></html>"
        assertEquals(html, MarpPresentFiles.cliPresentation(html, "file:///w/", 0))
        val withHead = MarpPresentFiles.cliPresentation("<html><body><header>H</header></body><head></head></html>", "file:///w/", 0)
        assertTrue(withHead, withHead.contains("<header>H</header>"))
        assertTrue(withHead, withHead.contains("<head><base "))
    }

    fun testOnlyTheFirstHeadGetsTheBase() {
        val html = MarpPresentFiles.cliPresentation("<head><a></a></head><head></head>", "file:///w/", 0)
        assertEquals(html, 1, Regex("<base ").findAll(html).count())
        assertTrue(html, html.contains("</script><a></a></head><head></head>"))
    }

    fun testAPageWithoutAHeadIsLeftAlone() {
        val html = "<!DOCTYPE html><p>no head</p>"
        assertEquals(html, MarpPresentFiles.cliPresentation(html, "file:///w/", 3))
        assertEquals("", MarpPresentFiles.cliPresentation("", "file:///w/", 3))
    }

    fun testTheBaseIsEscapedForAnAttribute() {
        val html = MarpPresentFiles.cliPresentation(page, "file:///w/a&b\"c<d/", 0)
        assertTrue(html, html.contains("<base href=\"file:///w/a&amp;b&quot;c&lt;d/\">"))
        assertFalse(html, html.contains("a&b"))
    }
}
