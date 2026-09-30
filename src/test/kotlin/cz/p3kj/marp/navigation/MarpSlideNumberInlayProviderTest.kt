package cz.p3kj.marp.navigation

import com.intellij.codeInsight.hints.InlayGroup
import com.intellij.codeInsight.hints.declarative.CollapseState
import com.intellij.codeInsight.hints.declarative.CollapsiblePresentationTreeBuilder
import com.intellij.codeInsight.hints.declarative.EndOfLinePosition
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayActionData
import com.intellij.codeInsight.hints.declarative.InlayHintsProviderExtensionBean
import com.intellij.codeInsight.hints.declarative.InlayPayload
import com.intellij.codeInsight.hints.declarative.InlayPosition
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.OwnBypassCollector
import com.intellij.codeInsight.hints.declarative.PresentationTreeBuilder
import com.intellij.openapi.application.runReadActionBlocking
import cz.p3kj.marp.MarpBundle
import cz.p3kj.marp.MarpLightTestCase

class MarpSlideNumberInlayProviderTest : MarpLightTestCase() {

    private val provider = MarpSlideNumberInlayProvider()

    /** A line and the text of the hint at its end. */
    private data class Hint(val line: Int, val text: String)

    private class RecordingBuilder : PresentationTreeBuilder {
        val texts = ArrayList<String>()

        override fun list(builder: PresentationTreeBuilder.() -> Unit) = builder()

        override fun collapsibleList(
            state: CollapseState,
            expandedState: CollapsiblePresentationTreeBuilder.() -> Unit,
            collapsedState: CollapsiblePresentationTreeBuilder.() -> Unit,
        ) = throw UnsupportedOperationException()

        override fun text(text: String, actionData: InlayActionData?) {
            texts += text
        }

        override fun clickHandlerScope(actionData: InlayActionData, builder: PresentationTreeBuilder.() -> Unit) = builder()
    }

    private class RecordingSink : InlayTreeSink {
        val hints = ArrayList<Hint>()

        override fun addPresentation(
            position: InlayPosition,
            payloads: List<InlayPayload>?,
            tooltip: String?,
            hintFormat: HintFormat,
            builder: PresentationTreeBuilder.() -> Unit,
        ) {
            val recorder = RecordingBuilder()
            recorder.builder()
            hints += Hint((position as EndOfLinePosition).line, recorder.texts.joinToString(""))
        }

        override fun whenOptionEnabled(optionId: String, block: () -> Unit) = throw UnsupportedOperationException()
    }

    /** The hints the provider adds to [text] (a file called [name]), `null` when it makes no collector. */
    private fun hintsOf(text: String, name: String = "deck.md"): List<Hint>? {
        myFixture.configureByText(name, text)
        return runReadActionBlocking {
            val collector = provider.createCollector(myFixture.file, myFixture.editor) ?: return@runReadActionBlocking null
            val sink = RecordingSink()
            (collector as OwnBypassCollector).collectHintsForFile(myFixture.file, sink)
            sink.hints
        }
    }

    private fun label(number: Int) = MarpBundle.message("inlay.slideNumbers.label", number)

    fun testEachSlideStartIsLabelled() {
        val hints = hintsOf(
            """
            ---
            marp: true
            ---

            # One

            ---

            # Two

            ---

            # Three
            """.trimIndent(),
        )
        assertEquals(listOf(Hint(0, "Slide 1"), Hint(6, "Slide 2"), Hint(10, "Slide 3")), hints)
        assertEquals(label(2), hints!![1].text)
    }

    fun testSlidesStartedByAHeadingAreLabelledOnTheHeadingLine() {
        val hints = hintsOf(
            """
            ---
            marp: true
            headingDivider: 2
            ---

            # A

            ## B

            text

            ## C
            """.trimIndent(),
        )
        assertEquals(listOf(Hint(0, "Slide 1"), Hint(7, "Slide 2"), Hint(11, "Slide 3")), hints)
    }

    fun testSeparatorsInsideCodeAndSetextHeadingsAreNotSlides() {
        val hints = hintsOf(
            """
            ---
            marp: true
            ---

            Title
            ---

            ```
            ---
            ```

            ---

            End
            """.trimIndent(),
        )
        assertEquals(listOf(Hint(0, "Slide 1"), Hint(11, "Slide 2")), hints)
    }

    fun testADeckWithoutSeparatorsHasOneLabel() {
        assertEquals(listOf(Hint(0, "Slide 1")), hintsOf("---\nmarp: true\n---\n\nHello\n"))
    }

    fun testLabelsUseTheLineOfTheSlideStart() {
        myFixture.configureByText("deck.md", "---\nmarp: true\n---\n\n# A\n\n---\n\n# B\n")
        val (deck, document) = runReadActionBlocking {
            MarpSlideNavigation.deckFor(project, myFixture.editor.document)!! to myFixture.editor.document
        }
        assertEquals(listOf(0 to 1, 6 to 2), MarpSlideNumberInlayProvider.labels(deck, document))
    }

    fun testNoHintsInPlainMarkdown() {
        assertNull(hintsOf("# One\n\n---\n\n# Two\n", "plain.md"))
    }

    fun testNoHintsWhenMarpIsOff() {
        assertNull(hintsOf("---\nmarp: false\n---\n\n# One\n\n---\n\n# Two\n"))
    }

    fun testProviderIsRegisteredForMarkdown() {
        val bean = InlayHintsProviderExtensionBean.EP.extensionList.single { it.providerId == "marp.slideNumbers" }
        assertEquals("Markdown", bean.language)
        assertTrue(bean.isEnabledByDefault)
        assertEquals(InlayGroup.OTHER_GROUP, bean.requiredGroup())
        assertEquals(MarpBundle.message("inlay.slideNumbers.name"), bean.getProviderName())
        assertEquals(MarpBundle.message("inlay.slideNumbers.description"), bean.getDescription())
        assertTrue(bean.instance is MarpSlideNumberInlayProvider)
    }
}
