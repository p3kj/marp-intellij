package cz.p3kj.marp.export

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.util.SystemInfo
import cz.p3kj.marp.settings.MarpHtmlMode
import cz.p3kj.marp.settings.MarpMathMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
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
    fun presentArgumentsWriteHtmlWithoutAFormatOption() {
        assertEquals(
            listOf("--config-file", config.toString(), "-o", "/tmp/marp-present-1.html", "--", deck.toString()),
            MarpCliArgs.presentArguments(deck, Path.of("/tmp/marp-present-1.html"), config),
        )
        val args = MarpCliArgs.presentArguments(Path.of("--pptx"), Path.of("out.html"), config)
        assertEquals("--", args[args.size - 2])
        assertEquals("--pptx", args.last())
        assertFalse("--html means allow HTML tags for the CLI", "--html" in args)
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
        present: Boolean = false,
    ): JsonObject = JsonParser.parseString(MarpCliArgs.config(themes, html, math, allowLocalFiles, present)).asJsonObject

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
    fun onlyAPresentationAsksForTheBespokeTemplate() {
        val export = config()
        assertFalse(export.has("template"))
        assertFalse(export.has("bespoke"))

        val present = config(present = true)
        assertEquals("bespoke", present.get("template").asString)
        val bespoke = present.getAsJsonObject("bespoke")
        assertEquals("only the progress bar is turned on", setOf("progress"), bespoke.keySet())
        assertTrue(bespoke.get("progress").asBoolean)
    }

    @Test
    fun aPresentationKeepsTheSettingsOfTheProject() {
        val theme = Path.of("/themes/a.css").toAbsolutePath()
        val present = config(themes = listOf(theme), html = MarpHtmlMode.ALL, math = MarpMathMode.KATEX, present = true)
        assertEquals(listOf(theme.toString()), present.getAsJsonArray("themeSet").map { it.asString })
        assertTrue(present.get("html").asBoolean)
        assertEquals("katex", present.getAsJsonObject("options").get("math").asString)
        assertTrue(present.get("allowLocalFiles").asBoolean)
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
        assertEquals(MarpCliLocation.Found(marp), MarpCliLocator.locate("", path, windowsExtensions = null))
        assertEquals(MarpCliLocation.Found(marp), MarpCliLocator.locate("   ", path, windowsExtensions = null))
        assertEquals(listOf("marp", "marp"), path.asked)
    }

    @Test
    fun nothingOnThePathIsMissingWithoutAConfiguredPath() {
        assertEquals(MarpCliLocation.Missing(null), MarpCliLocator.locate("", FakePath(), windowsExtensions = null))
    }

    @Test
    fun anAbsolutePathThatExistsIsUsedWithoutTheLookup() {
        val marp = temp.newFile("marp").toPath()
        val path = FakePath()
        assertEquals(MarpCliLocation.Found(marp), MarpCliLocator.locate("  $marp  ", path, windowsExtensions = null))
        assertTrue(path.asked.isEmpty())
    }

    @Test
    fun anAbsolutePathThatDoesNotExistIsMissing() {
        val gone = temp.root.toPath().resolve("gone")
        assertEquals(MarpCliLocation.Missing(gone.toString()), MarpCliLocator.locate(gone.toString(), FakePath(), windowsExtensions = null))
    }

    @Test
    fun aFolderIsNotAnExecutable() {
        val folder = temp.newFolder("bin").toPath()
        assertTrue(Files.isDirectory(folder))
        assertEquals(MarpCliLocation.Missing(folder.toString()), MarpCliLocator.locate(folder.toString(), FakePath(), windowsExtensions = null))
    }

    @Test
    fun aRelativeNameIsLookedUpOnThePath() {
        val cmd = temp.newFile("marp.cmd").toPath()
        val path = FakePath(mapOf("marp.cmd" to cmd))
        assertEquals(MarpCliLocation.Found(cmd), MarpCliLocator.locate("marp.cmd", path, windowsExtensions = null))
        assertEquals(listOf("marp.cmd"), path.asked)
        assertEquals(MarpCliLocation.Missing("other"), MarpCliLocator.locate("other", path, windowsExtensions = null))
    }

    @Test
    fun anInvalidPathIsMissing() {
        assertEquals(MarpCliLocation.Missing("a\u0000b"), MarpCliLocator.locate("a\u0000b", FakePath(), windowsExtensions = null))
    }

    // The PATH search itself, on real files.

    private fun file(vararg parts: String, executable: Boolean = true): Path {
        val file = parts.fold(temp.root.toPath()) { dir, part -> dir.resolve(part) }
        Files.createDirectories(file.parent)
        Files.writeString(file, "x")
        file.toFile().setExecutable(executable)
        return file
    }

    private fun pathOf(vararg directories: String): String =
        directories.joinToString(File.pathSeparator) { temp.root.toPath().resolve(it).toString() }

    @Test
    fun theFirstDirectoryWithTheProgramWins() {
        val second = file("b", "marp")
        file("c", "marp")
        assertEquals(second, MarpCliLocator.findExecutable("marp", pathOf("a", "b", "c"), null))
    }

    @Test
    fun aFileThatIsNotExecutableIsSkippedOutsideWindows() {
        assumeFalse(SystemInfo.isWindows)
        file("a", "marp", executable = false)
        val executable = file("b", "marp")
        assertEquals(executable, MarpCliLocator.findExecutable("marp", pathOf("a", "b"), null))
    }

    @Test
    fun aFolderNamedLikeTheProgramIsNotTheProgram() {
        temp.newFolder("a", "marp")
        assertNull(MarpCliLocator.findExecutable("marp", pathOf("a"), null))
    }

    @Test
    fun nothingToSearchFindsNothing() {
        assertNull(MarpCliLocator.findExecutable("marp", null, null))
        assertNull(MarpCliLocator.findExecutable("marp", "", null))
        assertNull(MarpCliLocator.findExecutable("marp", File.pathSeparator + "  ", null))
    }

    @Test
    fun onWindowsTheExtensionsOfPathextAreTried() {
        file("npm", "marp")
        val cmd = file("npm", "marp.cmd")
        file("npm", "marp.ps1")
        assertEquals(cmd, MarpCliLocator.findExecutable("marp", pathOf("npm"), listOf(".com", ".exe", ".bat", ".cmd")))
    }

    @Test
    fun onWindowsAGivenExtensionIsKept() {
        val cmd = file("npm", "marp.cmd")
        assertEquals(cmd, MarpCliLocator.findExecutable("marp.cmd", pathOf("npm"), listOf(".exe", ".cmd")))
    }

    @Test
    fun onWindowsTheScriptWithoutAnExtensionIsNotAProgram() {
        file("npm", "marp")
        assertNull(MarpCliLocator.findExecutable("marp", pathOf("npm"), listOf(".exe", ".cmd")))
    }

    @Test
    fun relativePathEntriesAreSkipped() {
        val marp = file("bin", "marp")
        val relative = try {
            Path.of("").toAbsolutePath().relativize(marp.parent)
        } catch (_: IllegalArgumentException) {
            null // another drive or root
        }
        assumeTrue(relative != null && !relative.isAbsolute)
        // The same directory: found by its absolute path, skipped by its path from the working directory.
        assertEquals(marp, MarpCliLocator.findExecutable("marp", marp.parent.toString(), null))
        assertNull(MarpCliLocator.findExecutable("marp", relative.toString(), null))
        assertEquals(marp, MarpCliLocator.findExecutable("marp", relative.toString() + File.pathSeparator + marp.parent, null))
    }

    @Test
    fun dotAndEmptyEntriesAreSkipped() {
        assertNull(MarpCliLocator.findExecutable("marp", listOf(".", "\"\"", "", "  ", "..").joinToString(File.pathSeparator), null))
    }

    @Test
    fun aQuotedDirectoryIsUsed() {
        val marp = file("quoted dir", "marp")
        assertEquals(marp, MarpCliLocator.findExecutable("marp", "\"" + marp.parent + "\"", null))
    }

    @Test
    fun onWindowsAnExtensionlessConfiguredPathStandsForItsCmdSibling() {
        val script = file("npm", "marp")
        val cmd = file("npm", "marp.cmd")
        val extensions = listOf(".exe", ".cmd")
        assertEquals(MarpCliLocation.Found(cmd), MarpCliLocator.locate(script.toString(), FakePath(), extensions))
        assertEquals(MarpCliLocation.Found(cmd), MarpCliLocator.locate(cmd.toString(), FakePath(), extensions))
    }

    @Test
    fun onWindowsAConfiguredScriptWithoutASiblingIsNotAProgram() {
        val script = file("npm", "marp")
        assertEquals(MarpCliLocation.Missing(script.toString()), MarpCliLocator.locate(script.toString(), FakePath(), listOf(".exe", ".cmd")))
    }

    // The project-local install: node_modules/.bin/marp from the folder of the deck up to the project root.

    private val projectRoot: Path get() = temp.root.toPath().resolve("proj")

    private fun project(start: String, trusted: Boolean = true) =
        MarpCliProject(start.split('/').fold(projectRoot) { dir, part -> dir.resolve(part) }, projectRoot, trusted)

    private fun local(vararg folder: String, name: String = "marp", executable: Boolean = true): Path =
        file("proj", *folder, "node_modules", ".bin", name, executable = executable)

    @Test
    fun aProjectInstallInTheFolderOfTheDeckIsFound() {
        val marp = local("decks")
        val path = FakePath()
        assertEquals(MarpCliLocation.FoundInProject(marp), MarpCliLocator.locate("", path, null, project("decks")))
        assertTrue("the PATH is not needed", path.asked.isEmpty())
    }

    @Test
    fun aProjectInstallInAParentFolderIsFound() {
        val marp = local()
        assertEquals(MarpCliLocation.FoundInProject(marp), MarpCliLocator.locate("", FakePath(), null, project("decks/talks/2026")))
    }

    @Test
    fun theNearestProjectInstallWins() {
        local()
        local("decks")
        val nearest = local("decks", "talks")
        assertEquals(MarpCliLocation.FoundInProject(nearest), MarpCliLocator.locate("", FakePath(), null, project("decks/talks/2026")))
    }

    @Test
    fun theProjectRootItselfIsSearched() {
        val marp = local()
        val root = MarpCliProject(projectRoot, projectRoot, trusted = true)
        assertEquals(MarpCliLocation.FoundInProject(marp), MarpCliLocator.locate("", FakePath(), null, root))
    }

    @Test
    fun aFolderAboveTheProjectRootIsNotSearched() {
        // Next to the project, not in it.
        file("node_modules", ".bin", "marp")
        assertEquals(MarpCliLocation.Missing(null), MarpCliLocator.locate("", FakePath(), null, project("decks/talks")))
        val onPath = file("bin", "marp")
        assertEquals(MarpCliLocation.Found(onPath), MarpCliLocator.locate("", FakePath(mapOf("marp" to onPath)), null, project("decks/talks")))
    }

    @Test
    fun aDeckOutsideTheProjectHasNoProjectInstall() {
        file("elsewhere", "node_modules", ".bin", "marp")
        local()
        val outside = MarpCliProject(temp.root.toPath().resolve("elsewhere"), projectRoot, trusted = true)
        assertEquals(MarpCliLocation.Missing(null), MarpCliLocator.locate("", FakePath(), null, outside))
        // Not by a path that only starts like the root either.
        val sibling = MarpCliProject(temp.root.toPath().resolve("proj-other"), projectRoot, trusted = true)
        assertEquals(MarpCliLocation.Missing(null), MarpCliLocator.locate("", FakePath(), null, sibling))
        // Nor by leaving the project with "..".
        val dotDot = MarpCliProject(projectRoot.resolve("decks").resolve("..").resolve(".."), projectRoot, trusted = true)
        assertEquals(MarpCliLocation.Missing(null), MarpCliLocator.locate("", FakePath(), null, dotDot))
    }

    @Test
    fun anUntrustedProjectIsNeverSearched() {
        local("decks")
        val onPath = file("bin", "marp")
        val path = FakePath(mapOf("marp" to onPath))
        assertEquals(MarpCliLocation.Found(onPath), MarpCliLocator.locate("", path, null, project("decks", trusted = false)))
        assertEquals(MarpCliLocation.Missing(null), MarpCliLocator.locate("", FakePath(), null, project("decks", trusted = false)))
    }

    @Test
    fun withoutAProjectNothingIsSearched() {
        local("decks")
        assertEquals(MarpCliLocation.Missing(null), MarpCliLocator.locate("", FakePath(), null))
    }

    @Test
    fun theConfiguredPathWinsOverTheProjectInstall() {
        local("decks")
        val configured = file("tools", "marp")
        val path = FakePath()
        assertEquals(MarpCliLocation.Found(configured), MarpCliLocator.locate(configured.toString(), path, null, project("decks")))
        assertTrue(path.asked.isEmpty())
    }

    @Test
    fun aMissingConfiguredPathIsNotReplacedByTheProjectInstall() {
        local("decks")
        val gone = temp.root.toPath().resolve("gone").resolve("marp")
        assertEquals(MarpCliLocation.Missing(gone.toString()), MarpCliLocator.locate(gone.toString(), FakePath(), null, project("decks")))
        // A name to look up on the PATH is not a project lookup either.
        assertEquals(MarpCliLocation.Missing("other"), MarpCliLocator.locate("other", FakePath(), null, project("decks")))
    }

    @Test
    fun theProjectInstallWinsOverThePath() {
        val marp = local("decks")
        val path = FakePath(mapOf("marp" to file("bin", "marp")))
        assertEquals(MarpCliLocation.FoundInProject(marp), MarpCliLocator.locate("  ", path, null, project("decks")))
    }

    @Test
    fun thePathIsUsedWhenTheProjectHasNoInstall() {
        val onPath = file("bin", "marp")
        val path = FakePath(mapOf("marp" to onPath))
        assertEquals(MarpCliLocation.Found(onPath), MarpCliLocator.locate("", path, null, project("decks")))
        assertEquals(listOf("marp"), path.asked)
    }

    @Test
    fun aFolderNamedLikeTheProjectInstallIsSkipped() {
        temp.newFolder("proj", "decks", "node_modules", ".bin", "marp")
        val above = local()
        assertEquals(MarpCliLocation.FoundInProject(above), MarpCliLocator.locate("", FakePath(), null, project("decks")))
    }

    @Test
    fun aProjectInstallThatIsNotExecutableIsSkippedOutsideWindows() {
        assumeFalse(SystemInfo.isWindows)
        local("decks", executable = false)
        val above = local()
        assertEquals(MarpCliLocation.FoundInProject(above), MarpCliLocator.locate("", FakePath(), null, project("decks")))
    }

    @Test
    fun onWindowsTheProjectInstallIsItsCmdSibling() {
        val extensions = listOf(".exe", ".cmd")
        local("decks")
        val cmd = local("decks", name = "marp.cmd")
        local("decks", name = "marp.ps1")
        assertEquals(MarpCliLocation.FoundInProject(cmd), MarpCliLocator.locate("", FakePath(), extensions, project("decks")))
    }

    @Test
    fun onWindowsTheProjectScriptWithoutASiblingIsNotAProgram() {
        local("decks")
        assertEquals(MarpCliLocation.Missing(null), MarpCliLocator.locate("", FakePath(), listOf(".exe", ".cmd"), project("decks")))
    }

    @Test
    fun projectFoldersRunFromTheStartUpToAndIncludingTheRoot() {
        val root = temp.root.toPath().resolve("proj")
        val start = root.resolve("a").resolve("b")
        assertEquals(listOf(start, root.resolve("a"), root), MarpCliLocator.projectFolders(start, root))
        assertEquals(listOf(root), MarpCliLocator.projectFolders(root, root))
        assertEquals(listOf(root.resolve("a"), root), MarpCliLocator.projectFolders(root.resolve("a").resolve("x").resolve(".."), root))
    }

    @Test
    fun projectFoldersStayInsideTheRoot() {
        val root = temp.root.toPath().resolve("proj")
        assertTrue(MarpCliLocator.projectFolders(root.parent, root).isEmpty())
        assertTrue(MarpCliLocator.projectFolders(temp.root.toPath().resolve("proj2"), root).isEmpty())
        assertTrue(MarpCliLocator.projectFolders(root.resolve("..").resolve("other"), root).isEmpty())
    }

    @Test
    fun aProjectNeedsABaseDirectoryAndAStart() {
        val start = projectRoot.resolve("decks")
        assertEquals(MarpCliProject(start, projectRoot, true), MarpCliProject.of(projectRoot.toString(), start, true))
        assertNull(MarpCliProject.of(null, start, true))
        assertNull(MarpCliProject.of(projectRoot.toString(), null, true))
        assertNull(MarpCliProject.of("a\u0000b", start, true))
    }
}
