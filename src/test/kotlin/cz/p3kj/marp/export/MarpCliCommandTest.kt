package cz.p3kj.marp.export

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import cz.p3kj.marp.settings.MarpHtmlMode
import cz.p3kj.marp.settings.MarpMathMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class MarpCliCommandTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val deck = Path.of("/work/deck.md")
    private val config = Path.of("/tmp/marp-cli1/marp-config.json")

    @Test
    fun pptxArguments() {
        assertEquals(
            listOf("--config-file", config.toString(), "--pptx", "-o", "/out/deck.pptx", "--", deck.toString()),
            MarpCliArgs.arguments(MarpCliFormat.PPTX, deck, Path.of("/out/deck.pptx"), config),
        )
    }

    @Test
    fun imageArguments() {
        assertEquals(
            listOf("--config-file", config.toString(), "--images", "png", "-o", "/out/deck.png", "--", deck.toString()),
            MarpCliArgs.arguments(MarpCliFormat.PNG, deck, Path.of("/out/deck.png"), config),
        )
        assertEquals(
            listOf("--config-file", config.toString(), "--images", "jpeg", "-o", "/out/deck.jpg", "--", deck.toString()),
            MarpCliArgs.arguments(MarpCliFormat.JPEG, deck, Path.of("/out/deck.jpg"), config),
        )
    }

    @Test
    fun aDeckNamedLikeAnOptionComesAfterTheSeparator() {
        val args = MarpCliArgs.arguments(MarpCliFormat.PPTX, Path.of("--pptx"), Path.of("out.pptx"), config)
        assertEquals("--", args[args.size - 2])
        assertEquals("--pptx", args.last())
    }

    private fun config(
        themes: List<Path> = emptyList(),
        html: MarpHtmlMode = MarpHtmlMode.DEFAULT,
        math: MarpMathMode = MarpMathMode.MATHJAX,
        allowLocalFiles: Boolean = true,
    ): JsonObject = JsonParser.parseString(MarpCliArgs.config(themes, html, math, allowLocalFiles)).asJsonObject

    @Test
    fun themesAreListedAsPaths() {
        val a = Path.of("/themes/a.css").toAbsolutePath()
        val b = Path.of("/themes/b b.css").toAbsolutePath()
        val themeSet = config(themes = listOf(a, b)).getAsJsonArray("themeSet")
        assertEquals(listOf(a.toString(), b.toString()), themeSet.map { it.asString })
    }

    @Test
    fun noThemesLeaveTheKeyOut() {
        assertFalse(config().has("themeSet"))
    }

    @Test
    fun htmlModes() {
        assertTrue(config(html = MarpHtmlMode.ALL).get("html").asBoolean)
        assertFalse(config(html = MarpHtmlMode.OFF).get("html").asBoolean)
        assertFalse("the default keeps the marp-core allowlist", config(html = MarpHtmlMode.DEFAULT).has("html"))
    }

    @Test
    fun mathModes() {
        assertEquals("katex", config(math = MarpMathMode.KATEX).getAsJsonObject("options").get("math").asString)
        assertEquals("mathjax", config(math = MarpMathMode.MATHJAX).getAsJsonObject("options").get("math").asString)
        val off = config(math = MarpMathMode.OFF).getAsJsonObject("options").get("math")
        assertTrue(off.isJsonPrimitive && off.asJsonPrimitive.isBoolean)
        assertFalse(off.asBoolean)
    }

    @Test
    fun localFilesAreAllowedOnlyWhenAsked() {
        assertTrue(config(allowLocalFiles = true).get("allowLocalFiles").asBoolean)
        assertFalse(config(allowLocalFiles = false).get("allowLocalFiles").asBoolean)
    }

    @Test
    fun backslashesAndAmpersandsSurviveTheJson() {
        val path = Path.of("/themes/a\\b & c.css")
        val themeSet = config(themes = listOf(path)).getAsJsonArray("themeSet")
        assertEquals(path.toString(), themeSet[0].asString)
    }

    @Test
    fun outputTailStripsColorsAndBlankLines() {
        val text = "\u001B[31m[ERROR]\u001B[0m No suitable browser found.\r\n\r\n   \nSecond line  \n"
        assertEquals("[ERROR] No suitable browser found.\nSecond line", MarpCliArgs.outputTail(text))
    }

    @Test
    fun outputTailKeepsTheLastLines() {
        val text = (1..40).joinToString("\n") { "line $it" }
        val tail = MarpCliArgs.outputTail(text).lines()
        assertEquals(15, tail.size)
        assertEquals("line 26", tail.first())
        assertEquals("line 40", tail.last())
    }

    @Test
    fun outputTailIsShortened() {
        val tail = MarpCliArgs.outputTail("a".repeat(5000) + "END")
        assertEquals(1500, tail.length)
        assertTrue(tail.endsWith("END"))
    }

    @Test
    fun outputTailOfNothingIsEmpty() {
        assertEquals("", MarpCliArgs.outputTail(""))
        assertEquals("", MarpCliArgs.outputTail("\n \n\u001B[0m\n"))
    }

    @Test
    fun firstLineSkipsBlankLinesAndColors() {
        assertEquals("@marp-team/marp-cli v4.2.3", MarpCliArgs.firstLine("\n \u001B[1m@marp-team/marp-cli v4.2.3\u001B[0m  \nsecond"))
        assertNull(MarpCliArgs.firstLine(" \n"))
    }

    // Locator: a fake PATH lookup and real files.

    private class FakePath(private val known: Map<String, Path> = emptyMap()) : (String) -> Path? {
        val asked = mutableListOf<String>()
        override fun invoke(name: String): Path? {
            asked += name
            return known[name]
        }
    }

    @Test
    fun anEmptySettingLooksUpMarpOnThePath() {
        val marp = temp.newFile("marp").toPath()
        val path = FakePath(mapOf("marp" to marp))
        assertEquals(MarpCliLocation.Found(marp), MarpCliLocator.locate("", path))
        assertEquals(MarpCliLocation.Found(marp), MarpCliLocator.locate("   ", path))
        assertEquals(listOf("marp", "marp"), path.asked)
    }

    @Test
    fun nothingOnThePathIsMissingWithoutAConfiguredPath() {
        assertEquals(MarpCliLocation.Missing(null), MarpCliLocator.locate("", FakePath()))
    }

    @Test
    fun anAbsolutePathThatExistsIsUsedWithoutTheLookup() {
        val marp = temp.newFile("marp").toPath()
        val path = FakePath()
        assertEquals(MarpCliLocation.Found(marp), MarpCliLocator.locate("  $marp  ", path))
        assertTrue(path.asked.isEmpty())
    }

    @Test
    fun anAbsolutePathThatDoesNotExistIsMissing() {
        val gone = temp.root.toPath().resolve("gone")
        assertEquals(MarpCliLocation.Missing(gone.toString()), MarpCliLocator.locate(gone.toString(), FakePath()))
    }

    @Test
    fun aFolderIsNotAnExecutable() {
        val folder = temp.newFolder("bin").toPath()
        assertTrue(Files.isDirectory(folder))
        assertEquals(MarpCliLocation.Missing(folder.toString()), MarpCliLocator.locate(folder.toString(), FakePath()))
    }

    @Test
    fun aRelativeNameIsLookedUpOnThePath() {
        val cmd = temp.newFile("marp.cmd").toPath()
        val path = FakePath(mapOf("marp.cmd" to cmd))
        assertEquals(MarpCliLocation.Found(cmd), MarpCliLocator.locate("marp.cmd", path))
        assertEquals(listOf("marp.cmd"), path.asked)
        assertEquals(MarpCliLocation.Missing("other"), MarpCliLocator.locate("other", path))
    }

    @Test
    fun anInvalidPathIsMissing() {
        assertEquals(MarpCliLocation.Missing("a\u0000b"), MarpCliLocator.locate("a\u0000b", FakePath()))
    }
}
