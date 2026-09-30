package cz.p3kj.marp.navigation

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.testFramework.PlatformTestUtil
import cz.p3kj.marp.MarpBundle
import cz.p3kj.marp.MarpLightTestCase
import cz.p3kj.marp.slides.MarpDeck

class MarpSlideWidgetTest : MarpLightTestCase() {

    private val deck = """
        ---
        marp: true
        ---

        # One

        ---

        # Two

        ---

        # Three
    """.trimIndent()

    private fun deckOf(): MarpDeck = runReadActionBlocking { MarpSlideNavigation.deckFor(project, myFixture.editor.document)!! }

    private fun offsetOf(marker: String): Int = myFixture.editor.document.text.indexOf(marker)

    fun testTextForANonDeckIsEmpty() {
        assertEquals("", MarpSlideWidget.textFor(null, 0))
    }

    fun testTextShowsTheSlideUnderTheOffset() {
        myFixture.configureByText("deck.md", deck)
        val deck = deckOf()
        assertEquals("Slide 1 / 3", MarpSlideWidget.textFor(deck, 0))
        assertEquals("Slide 2 / 3", MarpSlideWidget.textFor(deck, offsetOf("# Two")))
        // A separator line belongs to the slide it starts, like in the preview.
        assertEquals("Slide 2 / 3", MarpSlideWidget.textFor(deck, offsetOf("---\n\n# Two")))
        assertEquals("Slide 3 / 3", MarpSlideWidget.textFor(deck, myFixture.editor.document.textLength))
        assertEquals(MarpBundle.message("statusBar.slide.text", 3, 3), MarpSlideWidget.textFor(deck, offsetOf("# Three")))
    }

    fun testFactoryIsRegistered() {
        val factory = StatusBarWidgetFactory.EP_NAME.extensionList.firstOrNull { it.id == MarpSlideWidget.ID }
        assertNotNull(factory)
        assertEquals(MarpBundle.message("statusBar.slide.name"), factory!!.displayName)
        assertTrue(factory.isAvailable(project))
    }

    // The widget itself ---------------------------------------------------------------------------------------------

    private fun installedWidget(): MarpSlideWidget {
        val statusBar: StatusBar = checkNotNull(WindowManager.getInstance().getStatusBar(project)) { "No status bar for the test project" }
        val widget = MarpSlideWidget(project)
        Disposer.register(testRootDisposable, widget)
        widget.install(statusBar)
        return widget
    }

    private fun waitForText(widget: MarpSlideWidget, expected: String) {
        PlatformTestUtil.waitWithEventsDispatching("Widget text is '${widget.getText()}', expected '$expected'", { widget.getText() == expected }, 10)
    }

    fun testWidgetFollowsTheCaretAndTheText() {
        myFixture.configureByText("deck.md", deck)
        val widget = installedWidget()
        assertEquals("Marp.SlidePosition", widget.ID())
        waitForText(widget, "Slide 1 / 3")
        assertNotNull(widget.getTooltipText())

        myFixture.editor.caretModel.moveToOffset(offsetOf("# Three"))
        waitForText(widget, "Slide 3 / 3")

        // Typing a slide shows up once the document is committed.
        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(offsetOf("# Three"), "# Extra\n\n---\n\n")
        }
        // The caret stays before the inserted slide, so it is still in slide 3 of what are now 4.
        waitForText(widget, "Slide 3 / 4")
    }

    fun testWidgetIsCopiedForDetachedWindows() {
        val widget = installedWidget()
        val copy = (widget as StatusBarWidget.Multiframe).copy()
        Disposer.register(testRootDisposable, copy)
        assertTrue(copy is MarpSlideWidget)
        assertNotSame(widget, copy)
        assertEquals(MarpSlideWidget.ID, copy.ID())
    }

    fun testWidgetIsEmptyOutsideADeck() {
        myFixture.configureByText("plain.md", "# One\n\n---\n\n# Two\n")
        val widget = installedWidget()
        // A Markdown file that is no deck: one background read, then the answer (no deck) is cached with the text.
        widget.refresh()?.let { PlatformTestUtil.waitForPromise(it) }
        assertEquals("", widget.getText())
        assertNull("cached, no second read", widget.refresh())
        assertNull(widget.getTooltipText())
        myFixture.configureByText("deck.md", deck)
        waitForText(widget, "Slide 1 / 3")
    }

    fun testWidgetDropsTheDeckWhenMarpIsRemoved() {
        myFixture.configureByText("deck.md", deck)
        val widget = installedWidget()
        waitForText(widget, "Slide 1 / 3")
        WriteCommandAction.runWriteCommandAction(project) {
            val document = myFixture.editor.document
            document.replaceString(offsetOf("marp: true"), offsetOf("marp: true") + "marp: true".length, "marp: false")
        }
        waitForText(widget, "")
    }
}
