package cz.p3kj.marp.themes

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope

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
 * STUB: the real implementation (settings + `.marprc` themeSet, VFS watching, URL cache) replaces this body.
 */
@Service(Service.Level.PROJECT)
class MarpThemeService(private val project: Project, private val cs: CoroutineScope) {

    /**
     * Returns the current theme set. Suspends while reading files / fetching URLs (runs off the EDT, never blocks it).
     * Results are cached; cheap to call after every [MarpThemeListener.themesChanged].
     */
    suspend fun loadThemes(): MarpThemeSet = MarpThemeSet.EMPTY

    companion object {
        fun getInstance(project: Project): MarpThemeService = project.service()
    }
}
