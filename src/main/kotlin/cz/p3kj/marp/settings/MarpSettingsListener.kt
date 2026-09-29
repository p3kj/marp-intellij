package cz.p3kj.marp.settings

import com.intellij.util.messages.Topic

/** Fired on the project message bus after [MarpSettings.update]. */
fun interface MarpSettingsListener {
    fun settingsChanged()

    companion object {
        @Topic.ProjectLevel
        @JvmField
        val TOPIC: Topic<MarpSettingsListener> = Topic(MarpSettingsListener::class.java, Topic.BroadcastDirection.NONE)
    }
}
