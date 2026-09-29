package cz.p3kj.marp.themes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarpThemeWatchTest {

    private val watch = MarpThemeWatch(
        fileTargets = setOf("/p/theme.css"),
        folderTargets = setOf("/p/themes", "/p/later"),
        themeFiles = setOf("/p/theme.css", "/p/themes/a.css"),
        marprcDir = "/p",
    )

    private fun assertTouches(path: String, directory: Boolean = false) = assertTrue(path, watch.touches(path, directory))

    private fun assertIgnores(path: String, directory: Boolean = false) = assertFalse(path, watch.touches(path, directory))

    @Test
    fun fileTargetsReactToTheFileAndItsParents() {
        assertTouches("/p/theme.css")
        assertTouches("/p", directory = true)
        assertTouches("/", directory = true)
        assertIgnores("/p/theme.css.bak")
        assertIgnores("/p/other.css")
        assertIgnores("/p/theme.css/x")
    }

    @Test
    fun folderTargetsReactToCssFilesAndDirectoriesInside() {
        assertTouches("/p/themes", directory = true)
        assertTouches("/p/themes/a.css")
        assertTouches("/p/themes/sub/B.CSS")
        assertTouches("/p/themes/sub", directory = true)
        assertTouches("/p/themes/1/2/3/4/5/6/7/deepest.css")
        // A missing entry that appears, as a file or as a folder.
        assertTouches("/p/later")
        assertTouches("/p/later", directory = true)
        assertTouches("/p/later/a.css")
    }

    @Test
    fun folderTargetsIgnoreEverythingTheFolderSearchSkips() {
        assertIgnores("/p/themes/readme.md")
        assertIgnores("/p/themes/sub/build.log")
        assertIgnores("/p/themes/node_modules/pkg/x.css")
        assertIgnores("/p/themes/node_modules", directory = true)
        assertIgnores("/p/themes/sub/node_modules/pkg", directory = true)
        assertIgnores("/p/themes/.git/x.css")
        assertIgnores("/p/themes/.git", directory = true)
        assertIgnores("/p/themes/a/.cache/b.css")
        assertIgnores("/p/themes/.hidden.css")
        assertIgnores("/p/themes/1/2/3/4/5/6/7/8/too-deep.css")
        assertIgnores("/p/themes-old/a.css")
        assertIgnores("/p/themes-old", directory = true)
    }

    @Test
    fun marprcFilesInTheProjectDirectory() {
        for (name in MarpThemeWatch.MARPRC_NAMES) {
            assertTrue(name, watch.isMarprc("/p/$name"))
            assertTouches("/p/$name")
        }
        assertFalse(watch.isMarprc("/p/sub/.marprc.yml"))
        assertFalse(watch.isMarprc("/p/.marprc.toml"))
        assertFalse(watch.isMarprc("/.marprc.yml"))
        assertFalse(MarpThemeWatch(emptySet(), emptySet(), emptySet(), null).isMarprc("/p/.marprc.yml"))
    }

    @Test
    fun emptyWatchesNothing() {
        assertTrue(MarpThemeWatch.NONE.isEmpty)
        assertFalse(MarpThemeWatch.NONE.touches("/p/a.css", directory = false))
        assertFalse(MarpThemeWatch(emptySet(), emptySet(), emptySet(), "/p").isEmpty)
        assertFalse(watch.isEmpty)
    }

    @Test
    fun aFolderAtTheFileSystemRoot() {
        val root = MarpThemeWatch(emptySet(), setOf("C:/"), emptySet(), null)
        assertTrue(root.touches("C:/a.css", directory = false))
        assertFalse(root.touches("C:/a.txt", directory = false))
    }
}
