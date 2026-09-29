package cz.p3kj.marp.themes

import com.intellij.util.messages.Topic

/**
 * Fired on the project message bus when the resolved theme set may have changed:
 * a watched theme CSS file / folder / `.marprc` changed on disk, or the theme settings changed.
 * Subscribers call [MarpThemeService.loadThemes] again.
 */
fun interface MarpThemeListener {
    fun themesChanged()

    companion object {
        @Topic.ProjectLevel
        @JvmField
        val TOPIC: Topic<MarpThemeListener> = Topic(MarpThemeListener::class.java, Topic.BroadcastDirection.NONE)
    }
}
