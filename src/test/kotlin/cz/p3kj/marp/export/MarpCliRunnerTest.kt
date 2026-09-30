package cz.p3kj.marp.export

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.util.SystemInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/** Runs small shell scripts instead of a real Marp CLI, so it needs a POSIX shell. */
class MarpCliRunnerTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Before
    fun onlyWhereThereIsAShell() {
        assumeFalse(SystemInfo.isWindows)
    }

    private fun sh(script: String) = GeneralCommandLine("sh", "-c", script).withCharset(Charsets.UTF_8)

    @Test
    fun collectsBothStreamsAndTheExitCode() {
        val result = runBlocking { runMarpCli(sh("echo out; echo err >&2; exit 3"), 30_000) }
        assertEquals(3, result.exitCode)
        assertTrue(result.output, "out" in result.output)
        assertTrue(result.output, "err" in result.output)
    }

    @Test
    fun aCleanRunHasExitCodeZero() {
        val result = runBlocking { runMarpCli(sh("echo done"), 30_000) }
        assertEquals(0, result.exitCode)
        assertEquals("done", result.output.trim())
    }

    @Test
    fun stdinIsClosedSoAProgramReadingItDoesNotWait() {
        // Like marp with no input file: it would wait for Markdown on stdin.
        val result = runBlocking { runMarpCli(sh("cat; echo after"), 30_000) }
        assertEquals(0, result.exitCode)
        assertEquals("after", result.output.trim())
    }

    @Test
    fun outputIsReadAsUtf8() {
        val result = runBlocking { runMarpCli(sh("printf '\\303\\251'"), 30_000) }
        assertEquals("\u00e9", result.output)
    }

    @Test
    fun aRunThatTakesTooLongIsKilled() {
        val pidFile = temp.root.toPath().resolve("pid")
        val result = runBlocking { runMarpCli(sh("echo \$\$ > '$pidFile'; exec sleep 30"), 2_000) }
        assertNull(result.exitCode)

        val pid = Files.readString(pidFile).trim().toLong()
        // The kill is a signal, the process may need a moment to be gone.
        val deadline = System.nanoTime() + 10_000_000_000L
        while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false) && System.nanoTime() < deadline) Thread.sleep(50)
        assertFalse("the process $pid is still running", ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
    }

    @Test(expected = ExecutionException::class)
    fun aProgramThatDoesNotExistCannotBeStarted() {
        runBlocking { runMarpCli(GeneralCommandLine(temp.root.toPath().resolve("missing").toString()), 30_000) }
    }

    private fun script(name: String, body: String): Path {
        val file = temp.root.toPath().resolve(name)
        Files.writeString(file, "#!/bin/sh\n$body\n")
        assertTrue(file.toFile().setExecutable(true))
        return file
    }

    @Test
    fun theVersionIsTheFirstLineOfTheAnswer() {
        val marp = script("marp", "echo '@marp-team/marp-cli v4.2.3 (w/ @marp-team/marp-core v4.1.0)'; echo second")
        assertEquals("@marp-team/marp-cli v4.2.3 (w/ @marp-team/marp-core v4.1.0)", runBlocking { marpCliVersion(marp) })
    }

    @Test
    fun noVersionWhenTheProgramFails() {
        val marp = script("failing", "echo 'env: node: No such file or directory' >&2; exit 127")
        assertNull(runBlocking { marpCliVersion(marp) })
    }

    @Test
    fun noVersionWhenThereIsNoProgram() {
        assertNull(runBlocking { marpCliVersion(temp.root.toPath().resolve("missing")) })
    }

    @Test
    fun aProgramThatAnswersNothingHasNoVersion() {
        val marp = script("silent", "exit 0")
        assertNull(runBlocking { marpCliVersion(marp) })
        assertNotNull(runBlocking { marpCliVersion(script("some", "echo x")) })
    }
}
