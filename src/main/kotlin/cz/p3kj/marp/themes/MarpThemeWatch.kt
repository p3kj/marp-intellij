package cz.p3kj.marp.themes

/**
 * What a resolved theme set depends on, matched against VFS event paths and edited documents by plain string
 * comparison: VFS delivers events on the EDT inside the write action, and a branch switch or an `npm install` delivers
 * tens of thousands of them. All paths are normalized, absolute and `/` separated ([MarpThemePaths.normalizedKey]).
 *
 * @property fileTargets entries that are files: an event on the file itself or on one of its parent directories counts.
 * @property folderTargets entries that are folders, or do not exist yet: besides the entry and its parents, CSS files
 *   and directories inside count, except where [MarpThemeFolder] never looks (skipped directories, hidden files, too
 *   deep).
 * @property themeFiles the theme files that were read, for document edits.
 * @property marprcDir the directory whose `.marprc*` files are watched, `null` when `themeSet` is not used.
 */
internal class MarpThemeWatch(
    private val fileTargets: Set<String>,
    private val folderTargets: Set<String>,
    val themeFiles: Set<String>,
    private val marprcDir: String?,
) {
    /** Nothing can change the theme set: event checks can stop right away. */
    val isEmpty: Boolean get() = fileTargets.isEmpty() && folderTargets.isEmpty() && marprcDir == null

    /** A `.marprc*` file in [marprcDir]. */
    fun isMarprc(path: String): Boolean =
        marprcDir != null && path.substringAfterLast('/') in MARPRC_NAMES && path.substringBeforeLast('/', "") == marprcDir

    /** Whether a VFS event on [path] ([directory] when the event is about a directory) may change the theme set. */
    fun touches(path: String, directory: Boolean): Boolean {
        if (isMarprc(path)) return true
        for (target in fileTargets) {
            if (path == target || isBelow(target, path)) return true
        }
        for (target in folderTargets) {
            if (path == target || isBelow(target, path)) return true
            val relative = relativeBelow(target, path) ?: continue
            if (isRelevantInFolder(relative, directory)) return true
        }
        return false
    }

    companion object {
        /** Marp CLI configuration files, in the order Marp CLI looks for them. */
        val MARPRC_NAMES: List<String> = listOf(".marprc.yml", ".marprc.yaml", ".marprc.json", ".marprc")

        val NONE: MarpThemeWatch = MarpThemeWatch(emptySet(), emptySet(), emptySet(), null)

        /** `true` when [path] is strictly below [ancestor] (a parent directory being moved, renamed or deleted). */
        private fun isBelow(path: String, ancestor: String): Boolean = relativeBelow(ancestor, path) != null

        /** The part of [path] below [dir] (`a/b.css`), or `null` when [path] is not strictly below [dir]. */
        private fun relativeBelow(dir: String, path: String): String? {
            val prefix = if (dir.endsWith('/')) dir else "$dir/"
            return if (path.length > prefix.length && path.startsWith(prefix)) path.substring(prefix.length) else null
        }

        /** A CSS file or a directory that [MarpThemeFolder.findCssFiles] would look at. */
        private fun isRelevantInFolder(relative: String, directory: Boolean): Boolean {
            val segments = relative.split('/')
            if (segments.size > MarpThemeFolder.MAX_DEPTH) return false
            val directories = if (directory) segments else segments.dropLast(1)
            if (directories.any(MarpThemeFolder::isSkippedDirectory)) return false
            return directory || MarpThemeFolder.isThemeFileName(segments.last())
        }
    }
}
