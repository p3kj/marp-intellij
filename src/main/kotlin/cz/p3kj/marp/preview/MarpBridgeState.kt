package cz.p3kj.marp.preview

/**
 * State of the Kotlin -> JS bridge of one preview page, see `docs/ARCHITECTURE.md`.
 *
 * Bridge methods are state setters, so only the latest JSON argument of each method is kept. While the page is not
 * ready calls are only recorded; when it reports `ready` (again after every reload) the recorded calls are replayed in
 * [REPLAY_ORDER]. After [dispose] nothing is executed any more. Thread-safe; [execute] runs with the lock held, so the
 * page receives calls in the order they were made.
 *
 * [command]s (export, flush) are different: they are never recorded or replayed, only executed while the page is ready.
 */
internal class MarpBridgeState(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val execute: (method: String, jsonArg: String) -> Unit,
) {
    private val lock = Any()
    private val latest = HashMap<String, String>()
    private var ready = false
    private var disposed = false
    private var reloads = 0
    private var readyAt = NEVER

    val isReady: Boolean get() = synchronized(lock) { ready }

    /**
     * Records [jsonArg] as the latest argument of [method] and executes it right away when the page is ready. With
     * [skipUnchanged], an argument equal to the latest one is dropped.
     */
    fun call(method: String, jsonArg: String, skipUnchanged: Boolean = false) {
        require(method in REPLAY_ORDER) { "Unknown bridge method: $method" }
        synchronized(lock) {
            if (disposed) return
            if (skipUnchanged && latest[method] == jsonArg) return
            latest[method] = jsonArg
            if (ready) execute(method, jsonArg)
        }
    }

    /**
     * Executes a command right away and returns `true`, or returns `false` when the page is not ready or the state is
     * disposed. A command is not state: it is never stored and never replayed on [onReady]. Its argument carries an id
     * and the page answers with a `reply` message, see `docs/ARCHITECTURE.md`.
     */
    fun command(method: String, jsonArg: String): Boolean {
        require(method in COMMANDS) { "Unknown bridge command: $method" }
        synchronized(lock) {
            if (disposed || !ready) return false
            execute(method, jsonArg)
            return true
        }
    }

    /** A main-frame load started: the page (and its bridge) is gone until the next [onReady]. */
    fun onLoadStart() {
        synchronized(lock) { ready = false }
    }

    /** The page reported `ready`: replay the latest argument of every method, in [REPLAY_ORDER]. */
    fun onReady() {
        synchronized(lock) {
            if (disposed) return
            ready = true
            readyAt = now()
            for (method in REPLAY_ORDER) latest[method]?.let { execute(method, it) }
        }
    }

    /**
     * The preview page is gone: the renderer process died, or the main frame ended up on another page. Returns `true`
     * when the page should be loaded again: at most [MAX_RELOADS] times in a row, where a page that stayed up for
     * [STABLE_MS] starts a new row (a deck that crashes the renderer right away must not reload forever).
     */
    fun onPageGone(): Boolean = synchronized(lock) {
        ready = false
        if (disposed) return false
        val wasStable = readyAt != NEVER && now() - readyAt >= STABLE_MS
        readyAt = NEVER
        if (wasStable) reloads = 0
        reloads++ < MAX_RELOADS
    }

    fun dispose() {
        synchronized(lock) {
            disposed = true
            ready = false
            latest.clear()
        }
    }

    companion object {
        const val SET_STRINGS: String = "setStrings"
        const val SET_IDE_THEME: String = "setIdeTheme"
        const val SET_THEMES: String = "setThemes"
        const val UPDATE: String = "update"
        const val SCROLL_TO_LINE: String = "scrollToLine"
        const val SET_ACTIVE_LINE: String = "setActiveLine"
        const val SET_OVERVIEW: String = "setOverview"

        const val EXPORT_HTML: String = "exportHtml"
        const val FLUSH_RENDER: String = "flushRender"

        /** Methods for [command]. */
        val COMMANDS: Set<String> = setOf(EXPORT_HTML, FLUSH_RENDER)

        /** Replay order on `ready`, as documented in `docs/ARCHITECTURE.md`. */
        val REPLAY_ORDER: List<String> = listOf(SET_STRINGS, SET_IDE_THEME, SET_THEMES, UPDATE, SCROLL_TO_LINE, SET_ACTIVE_LINE, SET_OVERVIEW)

        const val MAX_RELOADS: Int = 3
        const val STABLE_MS: Long = 10_000

        private const val NEVER = Long.MIN_VALUE
    }
}
