package cz.p3kj.marp.preview

/**
 * State of the Kotlin -> JS bridge of one preview page, see `docs/ARCHITECTURE.md`.
 *
 * Bridge methods are state setters, so only the latest JSON argument of each method is kept. While the page is not
 * ready calls are only recorded; when it reports `ready` (again after every reload) the recorded calls are replayed in
 * [REPLAY_ORDER]. After [dispose] nothing is executed any more. Thread-safe; [execute] runs with the lock held, so the
 * page receives calls in the order they were made.
 */
internal class MarpBridgeState(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val execute: (method: String, jsonArg: String) -> Unit,
) {
    private val lock = Any()
    private val latest = HashMap<String, String>()
    private var ready = false
    private var disposed = false
    private var crashReloads = 0
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
     * The renderer process died. Returns `true` when the page should be reloaded: at most [MAX_CRASH_RELOADS] times in a
     * row, where a page that stayed up for [STABLE_MS] starts a new row (a deck that crashes the renderer right away
     * must not reload forever).
     */
    fun onRenderProcessGone(): Boolean = synchronized(lock) {
        ready = false
        if (disposed) return false
        val wasStable = readyAt != NEVER && now() - readyAt >= STABLE_MS
        readyAt = NEVER
        if (wasStable) crashReloads = 0
        crashReloads++ < MAX_CRASH_RELOADS
    }

    fun dispose() {
        synchronized(lock) {
            disposed = true
            ready = false
            latest.clear()
        }
    }

    companion object {
        const val SET_IDE_THEME: String = "setIdeTheme"
        const val SET_THEMES: String = "setThemes"
        const val UPDATE: String = "update"
        const val SCROLL_TO_LINE: String = "scrollToLine"
        const val SET_ACTIVE_LINE: String = "setActiveLine"

        /** Replay order on `ready`, as documented in `docs/ARCHITECTURE.md`. */
        val REPLAY_ORDER: List<String> = listOf(SET_IDE_THEME, SET_THEMES, UPDATE, SCROLL_TO_LINE, SET_ACTIVE_LINE)

        const val MAX_CRASH_RELOADS: Int = 3
        const val STABLE_MS: Long = 10_000

        private const val NEVER = Long.MIN_VALUE
    }
}
