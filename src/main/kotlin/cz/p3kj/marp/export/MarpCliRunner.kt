package cz.p3kj.marp.export

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.util.Key
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path

/** How long a Marp CLI export may take before it is killed. Rendering with a browser is slow for big decks. */
internal const val MARP_CLI_TIMEOUT_MS: Long = 5 * 60 * 1000

private const val VERSION_TIMEOUT_MS: Long = 15_000

/** What a Marp CLI run printed (stdout and stderr together) and how it ended: [exitCode] is `null` when it was killed for taking too long. */
data class MarpCliResult(val exitCode: Int?, val output: String)

/**
 * Runs [commandLine] to the end, or kills it (with the processes it started, Marp CLI starts a browser) after
 * [timeoutMs] or when the caller is cancelled. Nothing is written to its stdin and it is closed at once, so the CLI never
 * waits for Markdown on it. Throws `ExecutionException` when the process cannot be started.
 */
suspend fun runMarpCli(commandLine: GeneralCommandLine, timeoutMs: Long): MarpCliResult = withContext(Dispatchers.IO) {
    val handler = KillableProcessHandler(commandLine)
    val output = StringBuffer()
    val exit = CompletableDeferred<Int>()
    handler.addProcessListener(object : ProcessListener {
        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            if (ProcessOutputType.isStdout(outputType) || ProcessOutputType.isStderr(outputType)) output.append(event.text)
        }

        override fun processTerminated(event: ProcessEvent) {
            exit.complete(event.exitCode)
        }
    })
    handler.startNotify()
    handler.processInput.close()
    try {
        // withTimeoutOrNull, not withTimeout: its exception is a CancellationException that callers would rethrow silently.
        MarpCliResult(withTimeoutOrNull(timeoutMs) { exit.await() }, output.toString())
    }
    finally {
        if (!handler.isProcessTerminated) handler.killProcess()
    }
}

/** The version line of Marp CLI (`marp --version`), `null` when [executable] does not answer with one in time. Off the EDT. */
suspend fun marpCliVersion(executable: Path): String? {
    val commandLine = GeneralCommandLine(executable.toString(), "--version").withCharset(Charsets.UTF_8)
    val result = try {
        runMarpCli(commandLine, VERSION_TIMEOUT_MS)
    }
    catch (_: ExecutionException) {
        return null
    }
    return if (result.exitCode == 0) MarpCliArgs.firstLine(result.output) else null
}
