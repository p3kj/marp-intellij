package cz.p3kj.marp.settings

import cz.p3kj.marp.MarpLightTestCase

class MarpSettingsTest : MarpLightTestCase() {

    private var published = 0

    override fun setUp() {
        super.setUp()
        project.messageBus.connect(testRootDisposable).subscribe(MarpSettingsListener.TOPIC, MarpSettingsListener { published++ })
    }

    fun testPublishesOnlyWhenSomethingChanged() {
        val settings = MarpSettings.getInstance(project)
        val html = settings.html
        val other = if (html == MarpHtmlMode.OFF) MarpHtmlMode.ALL else MarpHtmlMode.OFF

        settings.update { this.html = html }
        assertEquals(0, published)

        settings.update { this.html = other }
        assertEquals(1, published)
        assertEquals(other, settings.html)

        settings.update { this.html = other }
        assertEquals(1, published)

        settings.update { themes.add("a.css") }
        assertEquals(2, published)
        assertEquals(listOf("a.css"), settings.themes)

        settings.update {
            this.html = html
            themes.remove("a.css")
        }
        assertEquals(3, published)
        assertEmpty(settings.themes)
    }
}
