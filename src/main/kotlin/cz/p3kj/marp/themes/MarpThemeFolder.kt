package cz.p3kj.marp.themes

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Lists the CSS files of a theme folder entry, like Marp CLI does for a directory `themeSet` (every `*.css` below it;
 * its glob skips `node_modules` and dot files), but bounded, because users do point this at a whole project:
 * `node_modules`, `.git` and every other hidden directory are never entered, the walk stops [MAX_DEPTH] levels down,
 * and at most [MAX_CSS_FILES] files are taken.
 */
internal object MarpThemeFolder {

    /** Directory levels below the folder that are searched; `folder/1/2/3/4/5/6/7/theme.css` is the deepest file found. */
    const val MAX_DEPTH: Int = 8

    /** CSS files loaded from one folder entry. */
    const val MAX_CSS_FILES: Int = 200

    /** [files] sorted by path; [truncated] when the folder had more CSS files than the limit. */
    class CssFiles(val files: List<Path>, val truncated: Boolean)

    /** Directories below a theme folder that are never searched: `node_modules` and hidden ones (`.git`, `.idea`...). */
    fun isSkippedDirectory(name: String): Boolean = name == "node_modules" || name.startsWith(".")

    /** A file name [findCssFiles] takes: `*.css` (any case), but no hidden file. */
    fun isThemeFileName(name: String): Boolean = name.endsWith(".css", ignoreCase = true) && !name.startsWith(".")

    /**
     * Every [isThemeFileName] file below [dir], sorted, with paths under [dir] even when [dir] itself is a
     * symlink. Symlinked directories inside are not followed (so there are no cycles); symlinked files are taken.
     * When more than [maxFiles] files exist the walk stops at the limit, so which files are kept then depends on the
     * file system's listing order. Does file I/O; unreadable directories are skipped.
     */
    fun findCssFiles(dir: Path, maxFiles: Int = MAX_CSS_FILES): CssFiles {
        // walkFileTree does not enter a symlinked start directory unless links are followed everywhere.
        val start = if (Files.isSymbolicLink(dir)) dir.toRealPath() else dir
        val found = ArrayList<Path>()
        var truncated = false
        Files.walkFileTree(start, emptySet(), MAX_DEPTH, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(directory: Path, attrs: BasicFileAttributes): FileVisitResult {
                val name = directory.fileName?.toString()
                return if (directory != start && name != null && isSkippedDirectory(name)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (file.fileName?.toString()?.let(::isThemeFileName) != true) return FileVisitResult.CONTINUE
                if (!attrs.isRegularFile && !(attrs.isSymbolicLink && Files.isRegularFile(file))) return FileVisitResult.CONTINUE
                if (found.size >= maxFiles) {
                    truncated = true
                    return FileVisitResult.TERMINATE
                }
                // add(), not +=: a Path is also an Iterable<Path> of its name elements.
                found.add(if (start == dir) file else dir.resolve(start.relativize(file)))
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        return CssFiles(found.sorted(), truncated)
    }
}
