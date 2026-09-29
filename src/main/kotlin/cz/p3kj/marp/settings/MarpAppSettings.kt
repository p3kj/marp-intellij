package cz.p3kj.marp.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * IDE-wide Marp settings: personal preferences that must not travel with a project's `.idea/marp.xml`. Stored in the
 * IDE configuration (`options/marp.xml`) and part of the "Tools" category of settings sync and export.
 */
@Service(Service.Level.APP)
@State(name = "MarpAppSettings", storages = [Storage("marp.xml")], category = SettingsCategory.TOOLS)
class MarpAppSettings : SimplePersistentStateComponent<MarpAppSettings.AppState>(AppState()) {

    class AppState : BaseState() {
        /** Editor and preview scroll together. */
        var scrollSync by property(true)

        /** The preview shows each slide's presenter notes (HTML comments that are not directives) under it. */
        var presenterNotes by property(false)
    }

    val scrollSync: Boolean get() = state.scrollSync
    val presenterNotes: Boolean get() = state.presenterNotes

    /** Changes the settings and, when anything actually changed, notifies [MarpAppSettingsListener.TOPIC] subscribers. */
    fun update(block: AppState.() -> Unit) {
        val before = state.modificationCount
        state.block()
        if (state.modificationCount != before) {
            ApplicationManager.getApplication().messageBus.syncPublisher(MarpAppSettingsListener.TOPIC).settingsChanged()
        }
    }

    companion object {
        fun getInstance(): MarpAppSettings = service()
    }
}
