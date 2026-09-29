package cz.p3kj.marp.settings

import com.intellij.openapi.application.ApplicationManager
import cz.p3kj.marp.MarpLightTestCase

class MarpConfigurableTest : MarpLightTestCase() {

    private lateinit var configurable: MarpConfigurable

    override fun setUp() {
        super.setUp()
        MarpSettings.getInstance(project).update {
            themes.clear()
            themes.addAll(listOf("a.css", "https://example.com/t.css"))
        }
        configurable = MarpConfigurable(project)
        configurable.createComponent()
    }

    override fun tearDown() {
        try {
            configurable.disposeUIResources()
            MarpSettings.getInstance(project).update { themes.clear() }
        } finally {
            super.tearDown()
        }
    }

    fun testLoadsTheThemeListAndTracksChanges() {
        assertEquals(listOf("a.css", "https://example.com/t.css"), configurable.themeEntries.items)
        assertFalse(configurable.isModified())

        configurable.addEntry("  themes/  ")
        assertTrue(configurable.isModified())
        assertEquals("themes/", configurable.themeEntries.items.last())

        configurable.apply()
        assertFalse(configurable.isModified())
        assertEquals(listOf("a.css", "https://example.com/t.css", "themes/"), MarpSettings.getInstance(project).themes)
    }

    fun testResetDropsUnsavedEdits() {
        configurable.addEntry("b.css")
        configurable.replaceEntry(0, "c.css")
        assertTrue(configurable.isModified())
        configurable.reset()
        assertFalse(configurable.isModified())
        assertEquals(listOf("a.css", "https://example.com/t.css"), configurable.themeEntries.items)
    }

    fun testBlankAndDuplicateEntriesAreIgnored() {
        configurable.addEntry("   ")
        configurable.addEntry("a.css")
        configurable.replaceEntry(1, "a.css")
        configurable.replaceEntry(1, " ")
        configurable.replaceEntry(5, "x.css")
        assertEquals(listOf("a.css", "https://example.com/t.css"), configurable.themeEntries.items)
        assertFalse(configurable.isModified())

        configurable.replaceEntry(1, " b.css ")
        assertEquals(listOf("a.css", "b.css"), configurable.themeEntries.items)
    }

    fun testEntryValidation() {
        val entries = listOf("a.css", "https://example.com/t.css")
        assertNull(MarpConfigurable.entryError("themes/b.css", entries, editedIndex = -1))
        assertNull(MarpConfigurable.entryError("/abs/folder", entries, editedIndex = -1))
        assertNull(MarpConfigurable.entryError("https://cdn.example.com/x.css", entries, editedIndex = -1))
        assertNull("the edited entry itself is not a duplicate", MarpConfigurable.entryError(" a.css ", entries, editedIndex = 0))

        assertNotNull(MarpConfigurable.entryError("  ", entries, editedIndex = -1))
        assertNotNull(MarpConfigurable.entryError("a.css", entries, editedIndex = -1))
        assertNotNull(MarpConfigurable.entryError("a.css", entries, editedIndex = 1))
        assertNotNull(MarpConfigurable.entryError("https://", entries, editedIndex = -1))
        assertNotNull(MarpConfigurable.entryError("ftp://example.com/x.css", entries, editedIndex = -1))
        assertNotNull(MarpConfigurable.entryError("a\u0000.css", entries, editedIndex = -1))
    }

    fun testPresenterNotesAreOffByDefault() {
        assertFalse(MarpAppSettings.AppState().presenterNotes)
        assertTrue(MarpAppSettings.AppState().scrollSync)
    }

    fun testScrollSyncIsAnIdeSettingWithItsOwnTopic() {
        val appSettings = MarpAppSettings.getInstance()
        val before = appSettings.scrollSync
        var published = 0
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(MarpAppSettingsListener.TOPIC, MarpAppSettingsListener { published++ })
        try {
            appSettings.update { scrollSync = before }
            assertEquals(0, published)
            appSettings.update { scrollSync = !before }
            assertEquals(1, published)
            assertEquals(!before, appSettings.scrollSync)
        } finally {
            appSettings.update { scrollSync = before }
        }
    }
}
