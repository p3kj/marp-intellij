package cz.p3kj.marp.themes

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.ide.trustedProjects.TrustedProjectsListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.util.io.HttpRequests
import cz.p3kj.marp.MarpBundle
import cz.p3kj.marp.settings.MarpSettings
import cz.p3kj.marp.settings.MarpSettingsListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** One custom theme stylesheet. [source] is a display name (project-relative path or URL) used in error messages. */
data class MarpThemeCss(val source: String, val css: String)

/** Resolved custom themes plus human-readable problems (missing file, failed download...) to show in the preview. */
data class MarpThemeSet(val themes: List<MarpThemeCss>, val errors: List<String>) {
    companion object {
        val EMPTY = MarpThemeSet(emptyList(), emptyList())
    }
}

/**
 * Resolves, reads and watches custom Marp themes for a project.
 *
 * Sources are the `themes` of [MarpSettings] plus, optionally, the `themeSet` of a `.marprc*` file in the project
 * root. In an untrusted project only local files from the settings are used. URLs are downloaded in parallel; failures
 * are cached for [FAILED_DOWNLOAD_TTL_MS]. The resolved set is cached until a watched file, a theme document, the
 * `.marprc`, the settings or the project trust change; then [MarpThemeListener.TOPIC] is published (coalesced) and
 * subscribers call [loadThemes] again.
 */
@Service(Service.Level.PROJECT)
class MarpThemeService(private val project: Project, private val cs: CoroutineScope) : Disposable {

    /** Downloads a URL as text (blocking, called on [Dispatchers.IO]). Replaced in tests. */
    internal var urlFetcher: (String) -> String = ::fetchHttp

    /** The project root directory (where `.marprc*` lives and relative entries resolve). Replaced in tests. */
    internal var projectDirProvider: () -> Path? = { project.basePath?.let { Path.of(it) } }

    /** Untrusted projects get local theme files from the settings only, no URLs and no `.marprc`. Replaced in tests. */
    internal var trustedProvider: () -> Boolean = { TrustedProjects.isProjectTrusted(project) }

    /** Monotonic milliseconds, for the failed-download cache. Replaced in tests. */
    internal var clock: () -> Long = { System.nanoTime() / 1_000_000 }

    /** Downloads by URL: successes until the settings change, failures for [FAILED_DOWNLOAD_TTL_MS]. */
    private val urlCache = ConcurrentHashMap<String, Download>()
    private val loadMutex = Mutex()
    private val generation = AtomicInteger()

    @Volatile
    private var cached: MarpThemeSet? = null

    /** Entry paths (files or dirs, normalized, `/` separated) that trigger a reload when touched. */
    @Volatile
    private var watchTargets: Set<String> = emptySet()

    /** Resolved theme file paths (normalized, `/` separated), for cheap document-change lookups. */
    @Volatile
    private var watchedFiles: Set<String> = emptySet()

    private var publishJob: Job? = null
    private val publishLock = Any()

    init {
        val connection = project.messageBus.connect(cs)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any(::touchesThemes)) {
                    invalidate()
                    schedulePublish(VFS_DEBOUNCE_MS)
                }
            }
        })
        connection.subscribe(MarpSettingsListener.TOPIC, MarpSettingsListener {
            urlCache.clear()
            invalidate()
            schedulePublish(0)
        })
        ApplicationManager.getApplication().messageBus.connect(cs).subscribe(TrustedProjectsListener.TOPIC, object : TrustedProjectsListener {
            override fun onProjectTrusted(project: Project) = trustChanged(project)
            override fun onProjectUntrusted(project: Project) = trustChanged(project)
        })
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                val isMarprc = file.name in MARPRC_NAMES && file.parent?.path == projectDir?.let(MarpThemePaths::normalizedKey)
                if (isMarprc || file.path in watchedFiles) {
                    invalidate()
                    schedulePublish(DOCUMENT_DEBOUNCE_MS)
                }
            }
        }, this)
    }

    override fun dispose() {
        synchronized(publishLock) { publishJob?.cancel() }
    }

    /**
     * Returns the current theme set. Suspends while reading files / fetching URLs (runs off the EDT, never blocks it).
     * Results are cached; cheap to call after every [MarpThemeListener.themesChanged].
     */
    suspend fun loadThemes(): MarpThemeSet {
        cached?.let { return it }
        return loadMutex.withLock {
            cached?.let { return@withLock it }
            val startedAt = generation.get()
            val result = withContext(Dispatchers.IO) { resolve() }
            if (startedAt == generation.get()) cached = result
            result
        }
    }

    private fun trustChanged(changed: Project) {
        if (changed != project) return
        invalidate()
        schedulePublish(0)
    }

    private fun invalidate() {
        generation.incrementAndGet()
        cached = null
    }

    private fun schedulePublish(delayMs: Long) {
        synchronized(publishLock) {
            publishJob?.cancel()
            publishJob = cs.launch {
                if (delayMs > 0) delay(delayMs)
                project.messageBus.syncPublisher(MarpThemeListener.TOPIC).themesChanged()
            }
        }
    }

    private val projectDir: Path? get() = projectDirProvider()

    private fun isMarprcPath(path: String): Boolean {
        val base = projectDir ?: return false
        val p = Path.of(path)
        return p.parent?.let { MarpThemePaths.normalizedKey(it) } == MarpThemePaths.normalizedKey(base) &&
            p.name in MARPRC_NAMES
    }

    private fun touchesThemes(event: VFileEvent): Boolean {
        val paths = buildList {
            add(event.path)
            when (event) {
                is VFileMoveEvent -> add(event.oldPath)
                is VFilePropertyChangeEvent -> if (event.isRename) add(event.oldPath)
                else -> {}
            }
        }
        val targets = watchTargets
        return paths.any { p ->
            isMarprcPath(p) || targets.any { t -> p == t || p.startsWith("$t/") || t.startsWith("$p/") }
        }
    }

    // ---- resolution ----

    private class Entry(val raw: String, val baseDir: Path?, val fromMarprc: Boolean = false)

    private sealed interface Download {
        class Loaded(val css: String) : Download
        class Failed(val message: String, val failedAt: Long) : Download
    }

    private suspend fun resolve(): MarpThemeSet {
        val settings = MarpSettings.getInstance(project)
        val base = projectDir
        val errors = mutableListOf<String>()
        val entries = mutableListOf<Entry>()
        settings.themes.filter { it.isNotBlank() }.forEach { entries += Entry(it.trim(), base) }
        if (settings.useMarprcThemeSet && base != null) entries += readMarprcEntries(base, errors)

        // Like marp-vscode in an untrusted workspace: only local theme files from the settings.
        if (!trustedProvider()) {
            val restricted = entries.removeAll { it.fromMarprc || MarpThemePaths.isHttpUrl(it.raw) }
            if (restricted) errors += MarpBundle.message("themes.error.untrusted")
        }

        val downloads = download(entries.map { it.raw }.filter(MarpThemePaths::isHttpUrl).distinct())

        val themes = mutableListOf<MarpThemeCss>()
        val seen = HashSet<String>()
        val targets = HashSet<String>()
        val files = HashSet<String>()

        for (entry in entries) {
            val raw = entry.raw
            if (MarpThemePaths.isHttpUrl(raw)) {
                if (seen.add(raw)) {
                    when (val download = downloads.getValue(raw)) {
                        is Download.Loaded -> themes += MarpThemeCss(raw, download.css)
                        is Download.Failed -> errors += MarpBundle.message("themes.error.download", raw, download.message)
                    }
                }
                continue
            }
            if (MarpThemePaths.hasScheme(raw)) {
                errors += MarpBundle.message("themes.error.unsupportedScheme", raw)
                continue
            }
            val path = entry.baseDir?.let { MarpThemePaths.resolve(it, raw) } ?: MarpThemePaths.resolve(Path.of("").toAbsolutePath(), raw)
            if (path == null) {
                errors += MarpBundle.message("themes.error.invalidPath", raw)
                continue
            }
            targets += MarpThemePaths.normalizedKey(path)
            when {
                path.isDirectory() -> for (css in findCssFiles(path)) {
                    addFile(css, base, themes, errors, seen, files)
                }
                path.isRegularFile() -> addFile(path, base, themes, errors, seen, files)
                !path.exists() -> errors += MarpBundle.message("themes.error.missing", raw)
                else -> errors += MarpBundle.message("themes.error.unreadable", raw)
            }
        }
        watchTargets = targets
        watchedFiles = files
        return MarpThemeSet(themes, errors)
    }

    private suspend fun addFile(
        path: Path,
        base: Path?,
        themes: MutableList<MarpThemeCss>,
        errors: MutableList<String>,
        seen: MutableSet<String>,
        files: MutableSet<String>,
    ) {
        if (!seen.add(MarpThemePaths.key(path))) return
        val display = MarpThemePaths.display(path, base)
        files += MarpThemePaths.normalizedKey(path)
        val text = readText(path)
        if (text == null) {
            errors += MarpBundle.message("themes.error.unreadable", display)
        } else {
            themes += MarpThemeCss(display, text)
        }
    }

    /** Unsaved editor content wins over the disk content. Null when the file cannot be read. */
    private suspend fun readText(path: Path): String? {
        val unsaved = readAction {
            val vf = LocalFileSystem.getInstance().findFileByNioFile(path) ?: return@readAction null
            val fdm = FileDocumentManager.getInstance()
            val doc = fdm.getCachedDocument(vf) ?: return@readAction null
            if (fdm.isDocumentUnsaved(doc)) doc.text else null
        }
        if (unsaved != null) return unsaved
        return try {
            Files.readString(path)
        } catch (e: Exception) {
            LOG.info("Cannot read theme $path", e)
            null
        }
    }

    private suspend fun readMarprcEntries(base: Path, errors: MutableList<String>): List<Entry> {
        val file = MARPRC_NAMES.map { base.resolve(it) }.firstOrNull { it.isRegularFile() } ?: return emptyList()
        val text = readText(file)
        if (text == null) {
            errors += MarpBundle.message("themes.error.marprcUnreadable", file.name)
            return emptyList()
        }
        return try {
            MarprcParser.parseThemeSet(file.name, text).map { Entry(it, file.parent, fromMarprc = true) }
        } catch (e: IllegalArgumentException) {
            errors += MarpBundle.message("themes.error.marprc", file.name, e.message ?: "")
            emptyList()
        }
    }

    /** Same as Marp CLI for a directory `themeSet`: every `*.css` below it (recursively), skipping `node_modules`. */
    private fun findCssFiles(dir: Path): List<Path> = try {
        Files.walk(dir).use { stream ->
            stream
                .filter { p ->
                    p.isRegularFile() && p.name.endsWith(".css", ignoreCase = true) &&
                        dir.relativize(p).none { it.toString() == "node_modules" }
                }
                .sorted()
                .toList()
        }
    } catch (e: Exception) {
        LOG.info("Cannot list theme folder $dir", e)
        emptyList()
    }

    /** All [urls] at once, each from the cache or downloaded in parallel. */
    private suspend fun download(urls: List<String>): Map<String, Download> {
        if (urls.isEmpty()) return emptyMap()
        return coroutineScope {
            urls.map { url -> async(Dispatchers.IO) { url to cachedOrFetch(url) } }.awaitAll().toMap()
        }
    }

    private fun cachedOrFetch(url: String): Download {
        when (val cached = urlCache[url]) {
            is Download.Loaded -> return cached
            is Download.Failed -> if (clock() - cached.failedAt < FAILED_DOWNLOAD_TTL_MS) return cached
            null -> {}
        }
        val result = try {
            Download.Loaded(urlFetcher(url))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.info("Cannot download theme $url: $e")
            Download.Failed(e.message ?: e.javaClass.simpleName, clock())
        }
        urlCache[url] = result
        return result
    }

    companion object {
        private val LOG = logger<MarpThemeService>()
        private val MARPRC_NAMES = listOf(".marprc.yml", ".marprc.yaml", ".marprc.json", ".marprc")
        private const val VFS_DEBOUNCE_MS = 100L
        private const val DOCUMENT_DEBOUNCE_MS = 300L
        private const val HTTP_TIMEOUT_MS = 5000

        /** A failed download is retried after this long (or when the settings change), not on every reload. */
        internal const val FAILED_DOWNLOAD_TTL_MS = 60_000L

        fun getInstance(project: Project): MarpThemeService = project.service()

        private fun fetchHttp(url: String): String =
            HttpRequests.request(url)
                .connectTimeout(HTTP_TIMEOUT_MS)
                .readTimeout(HTTP_TIMEOUT_MS)
                .readString()
    }
}
