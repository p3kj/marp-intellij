package cz.p3kj.marp.settings

import com.intellij.util.messages.Topic

/** Fired on the application message bus after [MarpAppSettings.update] changed something. */
fun interface MarpAppSettingsListener {
    fun settingsChanged()

    companion object {
        @Topic.AppLevel
        @JvmField
        val TOPIC: Topic<MarpAppSettingsListener> = Topic(MarpAppSettingsListener::class.java, Topic.BroadcastDirection.NONE)
    }
}
