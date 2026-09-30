package cz.p3kj.marp.themes

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
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
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.psi.PsiManager
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
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownFile
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
 * root. `.marprc` entries must stay inside the project directory (the same traversal check as marp-vscode 3.5.2), while
 * settings entries are the user's own choice and may point anywhere. Like marp-vscode's restricted mode, an untrusted
 * project loads no custom theme at all. Folders are searched by [MarpThemeFolder]. URLs are downloaded in parallel
 * (CSS or plain text only, at most [MAX_DOWNLOAD_BYTES]); failures are cached for [FAILED_DOWNLOAD_TTL_MS]. The
 * resolved set is cached until a watched file, a theme document, the `.marprc`, the settings or the project trust
 * change; then [MarpThemeListener.TOPIC] is published (coalesced) and subscribers call [loadThemes] again. The open
 * Markdown files are highlighted again after every publish and after a load that an inspection started
 * ([themeNamesForInspection]), because the unknown-theme warnings depend on the set.
 */
@Service(Service.Level.PROJECT)
class MarpThemeService(private val project: Project, private val cs: CoroutineScope) : Disposable {

    /** Downloads a URL as text (blocking, called on [Dispatchers.IO]). Replaced in tests. */
    internal var urlFetcher: (String) -> String = ::fetchHttp

    /** The project root directory (where `.marprc*` lives and relative entries resolve). Replaced in tests. */
    internal var projectDirProvider: () -> Path? = { project.basePath?.let { Path.of(it) } }

    /** Untrusted projects get no custom themes. Replaced in tests. */
    internal var trustedProvider: () -> Boolean = { TrustedProjects.isProjectTrusted(project) }

    /** Monotonic milliseconds, for the failed-download cache. Replaced in tests. */
    internal var clock: () -> Long = { System.nanoTime() / 1_000_000 }

    /** Downloads by URL: successes until the settings change, failures for [FAILED_DOWNLOAD_TTL_MS]. */
    private val urlCache = ConcurrentHashMap<String, Download>()
    private val loadMutex = Mutex()
    private val generation = AtomicInteger()

    @Volatile
    private var cached: MarpThemeSet? = null

    /** What the last resolved set depends on; replaced as a whole by every [resolve]. */
    @Volatile
    private var watch: MarpThemeWatch = MarpThemeWatch.NONE

    private var publishJob: Job? = null
    private val publishLock = Any()

    init {
        val connection = project.messageBus.connect(cs)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val watch = watch
                if (watch.isEmpty) return
                if (events.any { touchesThemes(watch, it) }) {
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
                val watch = watch
                if (watch.isEmpty) return
                val path = FileDocumentManager.getInstance().getFile(event.document)?.path ?: return
                if (watch.isMarprc(path) || path in watch.themeFiles) {
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

    /**
     * The names of the custom themes of the cached set, for completion: never suspends and never blocks. While nothing is
     * cached it starts loading in the background and returns an empty list, so the names show up on a later call.
     */
    fun cachedThemeNames(): List<String> {
        val set = cached
        if (set == null) {
            cs.launch { loadThemes() }
            return emptyList()
        }
        return namesOf(set)
    }

    /** The names of one set are read once, completion asks for them on every keystroke. */
    private fun namesOf(set: MarpThemeSet): List<String> {
        names?.let { if (it.first === set) return it.second }
        val result = set.themes.mapNotNull { MarpThemeNames.nameOf(it.css) }.distinct()
        names = set to result
        return result
    }

    @Volatile
    private var names: Pair<MarpThemeSet, List<String>>? = null

    private val inspectionLoad = AtomicBoolean()

    /**
     * The custom theme names for the unknown-theme inspection, never suspending and never blocking, or `null` when an
     * unknown name cannot be told from a theme that did not load: nothing is cached yet (loading starts, and the open
     * Markdown files are highlighted again when it is done), the project is untrusted, or the set has any error.
     */
    fun themeNamesForInspection(): Set<String>? {
        if (!trustedProvider()) return null
        val set = cached
        if (set == null) {
            if (inspectionLoad.compareAndSet(false, true)) {
                cs.launch {
                    try {
                        loadThemes()
                    } finally {
                        inspectionLoad.set(false)
                    }
                    restartMarkdownHighlighting()
                }
            }
            return null
        }
        return if (set.errors.isEmpty()) namesOf(set).toSet() else null
    }

    /**
     * Runs the inspections of the open Markdown files again: what they report depends on the theme set, which changes
     * without any edit of the file. Only a re-run, it loads nothing that is not loading already, so it cannot loop.
     */
    internal suspend fun restartMarkdownHighlighting() {
        val files = readAction {
            if (project.isDisposed) emptyList()
            else {
                val psiManager = PsiManager.getInstance(project)
                FileEditorManager.getInstance(project).openFiles.mapNotNull { psiManager.findFile(it) as? MarkdownFile }
            }
        }
        if (files.isEmpty()) return
        val daemon = DaemonCodeAnalyzer.getInstance(project)
        files.forEach { daemon.restart(it, "Marp themes changed") }
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
                restartMarkdownHighlighting()
            }
        }
    }

    /** Called on the EDT for every VFS event: string comparisons only (see [MarpThemeWatch]). */
    private fun touchesThemes(watch: MarpThemeWatch, event: VFileEvent): Boolean {
        val directory = if (event is VFileCreateEvent) event.isDirectory else event.file?.isDirectory == true
        if (watch.touches(event.path, directory)) return true
        return when (event) {
            is VFileMoveEvent -> watch.touches(event.oldPath, directory)
            is VFilePropertyChangeEvent -> event.isRename && (watch.touches(event.oldPath, directory) || watch.touches(event.newPath, directory))
            else -> false
        }
    }

    // ---- resolution ----

    /** [marprcName] is the `.marprc*` file the entry comes from, `null` for a settings entry. */
    private class Entry(val raw: String, val baseDir: Path?, val marprcName: String? = null)

    private sealed interface Download {
        class Loaded(val css: String) : Download

        /** [error] is the complete line shown in the preview. */
        class Failed(val error: String, val failedAt: Long) : Download
    }

    /** Theme files found so far, shared by all entries of one [resolve]. */
    private class Collected(val base: Path?) {
        val themes = mutableListOf<MarpThemeCss>()
        val errors = mutableListOf<String>()
        val seen = HashSet<String>()
        val fileTargets = HashSet<String>()
        val folderTargets = HashSet<String>()
        val themeFiles = HashSet<String>()
    }

    private suspend fun resolve(): MarpThemeSet {
        val settings = MarpSettings.getInstance(project)
        val base = projectDir
        val trusted = trustedProvider()
        val collected = Collected(base)
        val entries = mutableListOf<Entry>()
        settings.themes.filter { it.isNotBlank() }.forEach { entries += Entry(it.trim(), base) }
        val marprcDir = base.takeIf { settings.useMarprcThemeSet }
        // An untrusted project's .marprc is only read to tell whether it names themes; its problems are not shown.
        if (marprcDir != null) entries += readMarprcEntries(marprcDir, if (trusted) collected.errors else mutableListOf())
        val marprcKey = marprcDir?.let(MarpThemePaths::normalizedKey)

        // Like marp-vscode in an untrusted workspace: no custom themes at all, the settings may come from the repository.
        if (!trusted) {
            if (entries.isNotEmpty()) collected.errors += MarpBundle.message("themes.error.untrusted")
            watch = MarpThemeWatch(emptySet(), emptySet(), emptySet(), marprcKey)
            return MarpThemeSet(emptyList(), collected.errors)
        }

        val downloads = download(entries.map { it.raw }.filter(MarpThemePaths::isHttpUrl).distinct())
        for (entry in entries) {
            val raw = entry.raw
            if (MarpThemePaths.isHttpUrl(raw)) {
                if (collected.seen.add(raw)) {
                    when (val download = downloads.getValue(raw)) {
                        is Download.Loaded -> collected.themes += MarpThemeCss(raw, download.css)
                        is Download.Failed -> collected.errors += download.error
                    }
                }
                continue
            }
            if (MarpThemePaths.hasScheme(raw)) {
                collected.errors += MarpBundle.message("themes.error.unsupportedScheme", raw)
                continue
            }
            addPathEntry(entry, collected)
        }
        watch = MarpThemeWatch(collected.fileTargets, collected.folderTargets, collected.themeFiles, marprcKey)
        return MarpThemeSet(collected.themes, collected.errors)
    }

    private suspend fun addPathEntry(entry: Entry, collected: Collected) {
        val raw = entry.raw
        val path = MarpThemePaths.resolve(entry.baseDir, raw)
        if (path == null) {
            collected.errors += MarpBundle.message("themes.error.invalidPath", raw)
            return
        }
        // A .marprc comes with the repository: it must not name files elsewhere on the machine.
        val confinedTo = if (entry.marprcName != null) entry.baseDir else null
        if (confinedTo != null && !MarpThemePaths.isConfinedTo(path, confinedTo)) {
            collected.errors += MarpBundle.message("themes.error.outsideProject", raw, entry.marprcName.orEmpty())
            return
        }
        val key = MarpThemePaths.normalizedKey(path)
        when {
            path.isDirectory() -> {
                collected.folderTargets += key
                val found = findCssFiles(path)
                if (found.truncated) collected.errors += MarpBundle.message("themes.error.tooManyFiles", raw, MarpThemeFolder.MAX_CSS_FILES)
                for (css in found.files) addFile(css, collected, confinedTo, entry.marprcName)
            }
            path.isRegularFile() -> {
                collected.fileTargets += key
                addFile(path, collected, confinedTo, entry.marprcName)
            }
            !path.exists() -> {
                // It may become a file or a folder.
                collected.folderTargets += key
                collected.errors += MarpBundle.message("themes.error.missing", raw)
            }
            else -> {
                collected.fileTargets += key
                collected.errors += MarpBundle.message("themes.error.unreadable", raw)
            }
        }
    }

    private suspend fun addFile(path: Path, collected: Collected, confinedTo: Path?, marprcName: String?) {
        if (!collected.seen.add(MarpThemePaths.key(path))) return
        val display = MarpThemePaths.toStored(path, collected.base)
        // A symlink inside a .marprc theme folder must not lead out of the project either.
        if (confinedTo != null && !MarpThemePaths.isConfinedTo(path, confinedTo)) {
            collected.errors += MarpBundle.message("themes.error.outsideProject", display, marprcName.orEmpty())
            return
        }
        collected.themeFiles += MarpThemePaths.normalizedKey(path)
        val text = readText(path)
        if (text == null) {
            collected.errors += MarpBundle.message("themes.error.unreadable", display)
        } else {
            collected.themes += MarpThemeCss(display, text)
        }
    }

    internal val projectDir: Path? get() = projectDirProvider()

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
        val file = MarpThemeWatch.MARPRC_NAMES.map { base.resolve(it) }.firstOrNull { it.isRegularFile() } ?: return emptyList()
        val text = readText(file)
        if (text == null) {
            errors += MarpBundle.message("themes.error.marprcUnreadable", file.name)
            return emptyList()
        }
        return try {
            MarprcParser.parseThemeSet(file.name, text).map { Entry(it, base, marprcName = file.name) }
        } catch (e: IllegalArgumentException) {
            errors += MarpBundle.message("themes.error.marprc", file.name, e.message ?: "")
            emptyList()
        }
    }

    private fun findCssFiles(dir: Path): MarpThemeFolder.CssFiles = try {
        MarpThemeFolder.findCssFiles(dir)
    } catch (e: Exception) {
        LOG.info("Cannot list theme folder $dir", e)
        MarpThemeFolder.CssFiles(emptyList(), truncated = false)
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
        } catch (e: MarpThemeRejectedException) {
            LOG.info("Theme $url not loaded: ${e.message}")
            Download.Failed(e.userMessage, clock())
        } catch (e: Exception) {
            LOG.info("Cannot download theme $url: $e")
            Download.Failed(MarpBundle.message("themes.error.download", url, e.message ?: e.javaClass.simpleName), clock())
        }
        urlCache[url] = result
        return result
    }

    companion object {
        private val LOG = logger<MarpThemeService>()
        private const val VFS_DEBOUNCE_MS = 100L
        private const val DOCUMENT_DEBOUNCE_MS = 300L
        private const val HTTP_TIMEOUT_MS = 5000

        /** A failed download is retried after this long (or when the settings change), not on every reload. */
        internal const val FAILED_DOWNLOAD_TTL_MS = 60_000L

        /** Remote theme size limit: the whole CSS text goes to the preview through `executeJavaScript` on every reload. */
        internal const val MAX_DOWNLOAD_BYTES: Int = 5 * 1024 * 1024

        /** Media types accepted for a remote theme; a response without Content-Type is accepted too. */
        private val THEME_MEDIA_TYPES = setOf("text/css", "text/plain")

        private val CHARSET_PARAMETER = Regex("charset\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE)

        fun getInstance(project: Project): MarpThemeService = project.service()

        private fun fetchHttp(url: String): String =
            HttpRequests.request(url)
                .connectTimeout(HTTP_TIMEOUT_MS)
                .readTimeout(HTTP_TIMEOUT_MS)
                .connect { request ->
                    val connection = request.connection
                    readThemeResponse(url, connection.contentType, connection.contentLengthLong) { request.inputStream }
                }

        /**
         * The text of a downloaded theme. Throws [MarpThemeRejectedException] when the response is neither CSS nor plain
         * text (by Content-Type; a missing one is accepted) or larger than [MAX_DOWNLOAD_BYTES], checked by the declared
         * length first and by reading at most one byte more than the limit, so an oversized body is never fully read.
         */
        internal fun readThemeResponse(url: String, contentType: String?, contentLength: Long, body: () -> InputStream): String {
            val mediaType = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            if (mediaType.isNotEmpty() && mediaType !in THEME_MEDIA_TYPES) {
                throw MarpThemeRejectedException(MarpBundle.message("themes.error.notCss", url, mediaType))
            }
            val tooLarge = { MarpThemeRejectedException(MarpBundle.message("themes.error.tooLarge", url, MAX_DOWNLOAD_BYTES / (1024 * 1024))) }
            if (contentLength > MAX_DOWNLOAD_BYTES) throw tooLarge()
            val bytes = body().use { it.readNBytes(MAX_DOWNLOAD_BYTES + 1) }
            if (bytes.size > MAX_DOWNLOAD_BYTES) throw tooLarge()
            return String(bytes, charsetOf(contentType))
        }

        /** The `charset` parameter of [contentType], UTF-8 when there is none or it is unknown. */
        private fun charsetOf(contentType: String?): Charset {
            val name = contentType?.let { CHARSET_PARAMETER.find(it)?.groupValues?.get(1) }?.trim()?.trim('"', '\'')
                ?: return Charsets.UTF_8
            return try {
                Charset.forName(name)
            } catch (_: IllegalArgumentException) {
                Charsets.UTF_8
            }
        }
    }
}

/** A remote theme that was downloaded but is not used; [userMessage] is the complete line shown in the preview. */
internal class MarpThemeRejectedException(val userMessage: String) : IOException(userMessage)
