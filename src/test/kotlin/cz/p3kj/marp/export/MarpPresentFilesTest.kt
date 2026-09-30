package cz.p3kj.marp.export

import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

class MarpPresentFilesTest : TestCase() {

    private val created = mutableListOf<Path>()

    override fun tearDown() {
        try {
            created.forEach { Files.deleteIfExists(it) }
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
}
