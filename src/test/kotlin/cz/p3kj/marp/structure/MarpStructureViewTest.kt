package cz.p3kj.marp.structure

import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import cz.p3kj.marp.MarpLightTestCase
import cz.p3kj.marp.editor.MarpSplitEditorProvider
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownFile

class MarpStructureViewTest : MarpLightTestCase() {

    private val front = "---\nmarp: true\n---\n"

    private fun <T> withModel(text: String, block: (StructureViewModel) -> T): T {
        myFixture.configureByText("deck.md", text)
        val model = MarpStructureViewBuilder(myFixture.file as MarkdownFile).createStructureViewModel(myFixture.editor)
        try {
            return block(model)
        }
        finally {
            Disposer.dispose(model as Disposable)
        }
    }

    /** The tree as indented presentable texts, one node per line. */
    private fun dump(model: StructureViewModel): List<String> {
        val lines = ArrayList<String>()
        fun visit(element: StructureViewTreeElement, depth: Int) {
            lines += "  ".repeat(depth) + element.presentation.presentableText
            for (child in element.children) visit(child as StructureViewTreeElement, depth + 1)
        }
        for (child in model.root.children) visit(child as StructureViewTreeElement, 0)
        return lines
    }

    private fun find(model: StructureViewModel, text: String): StructureViewTreeElement {
        fun search(element: StructureViewTreeElement): StructureViewTreeElement? {
            if (element.presentation.presentableText == text) return element
            return element.children.firstNotNullOfOrNull { search(it as StructureViewTreeElement) }
        }
        return model.root.children.firstNotNullOfOrNull { search(it as StructureViewTreeElement) } ?: error("No node '$text' in ${dump(model)}")
    }

    fun testSlidesWithNestedHeadings() {
        val text = "$front# Intro\n\nHello\n\n---\n\n# Agenda\n\n## Details\n\n### Deep\n\n## More\n\n---\n\nJust text\n"
        withModel(text) { model ->
            assertEquals(
                listOf("Slide 1: Intro", "Slide 2: Agenda", "  Details", "    Deep", "  More", "Slide 3"),
                dump(model),
            )
        }
    }

    fun testRootIsNotShown() {
        val builder = MarpStructureViewBuilder(run {
            myFixture.configureByText("deck.md", front)
            myFixture.file as MarkdownFile
        })
        assertFalse(builder.isRootNodeShown)
    }

    fun testHeadingsAboveTheTitleLevelNestAtTheTop() {
        val text = "$front## Title\n\n# Bigger\n\n### Small\n\n## Same as title\n"
        withModel(text) { model ->
            assertEquals(listOf("Slide 1: Title", "  Bigger", "    Small", "    Same as title"), dump(model))
        }
    }

    fun testHeadingDividerSlides() {
        val text = "---\nmarp: true\nheadingDivider: 2\n---\n# Deck\n\n## One\n\ntext\n\n### Sub\n\n## Two\n"
        withModel(text) { model ->
            assertEquals(listOf("Slide 1: Deck", "Slide 2: One", "  Sub", "Slide 3: Two"), dump(model))
        }
    }

    fun testSlideNumbersAreNotGrouped() {
        val text = front + "x\n\n---\n\n".repeat(1_000)
        withModel(text) { model ->
            assertEquals("Slide 1000", model.root.children[999].presentation.presentableText)
            assertEquals("Slide 1001", model.root.children[1000].presentation.presentableText)
        }
    }

    fun testCurrentEditorElementFollowsTheCaret() {
        withModel("$front# Intro\n\nHello<caret>\n\n---\n\n# Agenda\n\nBody\n\n## Details\n\nText\n") { model ->
            assertEquals(MarpSlideKey(0), model.currentEditorElement)
            val text = myFixture.editor.document.text

            myFixture.editor.caretModel.moveToOffset(text.indexOf("Body"))
            assertEquals(MarpSlideKey(1), model.currentEditorElement)

            myFixture.editor.caretModel.moveToOffset(text.indexOf("Text"))
            assertEquals(MarpHeadingKey(1, 1), model.currentEditorElement)

            // On the heading line itself, on the separator line, and in the front matter.
            myFixture.editor.caretModel.moveToOffset(text.indexOf("## Details"))
            assertEquals(MarpHeadingKey(1, 1), model.currentEditorElement)
            myFixture.editor.caretModel.moveToOffset(text.indexOf("---", front.length))
            assertEquals(MarpSlideKey(1), model.currentEditorElement)
            myFixture.editor.caretModel.moveToOffset(2)
            assertEquals(MarpSlideKey(0), model.currentEditorElement)
        }
    }

    fun testKeysMatchTheValuesOfTheNodes() {
        withModel("$front# Intro\n\n---\n\n# Agenda\n\n## Details\n") { model ->
            assertEquals(MarpSlideKey(1), find(model, "Slide 2: Agenda").value)
            assertEquals(MarpHeadingKey(1, 1), find(model, "Details").value)
        }
    }

    fun testNodesAreEqualAcrossRebuilds() {
        // Typing moves every offset, the nodes still compare equal (the tree keeps expansion and selection by equality).
        withModel("$front# Intro<caret>\n\n---\n\n# Agenda\n\n## Details\n") { model ->
            val before = find(model, "Details")
            val slide = find(model, "Slide 2: Agenda")
            myFixture.type("xyz")
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            assertEquals(listOf("Slide 1: Introxyz", "Slide 2: Agenda", "  Details"), dump(model))
            assertEquals(before, find(model, "Details"))
            assertEquals(slide, find(model, "Slide 2: Agenda"))
            assertEquals(before.hashCode(), find(model, "Details").hashCode())
            assertFalse(slide == find(model, "Details"))
        }
    }

    fun testNavigateMovesTheCaret() {
        val text = "$front# Intro\n\nHello\n\n---\n\n# Agenda\n\nBody\n\n## Details\n\nText\n"
        withModel(text) { model ->
            val slide = find(model, "Slide 2: Agenda")
            assertTrue(slide.canNavigate())
            assertTrue(slide.canNavigateToSource())
            slide.navigate(false)
            assertEquals(text.indexOf("# Agenda"), myFixture.editor.caretModel.offset)

            find(model, "Details").navigate(false)
            assertEquals(text.indexOf("## Details"), myFixture.editor.caretModel.offset)

            find(model, "Slide 1: Intro").navigate(false)
            assertEquals(text.indexOf("# Intro"), myFixture.editor.caretModel.offset)
        }
    }

    fun testTreeFollowsEdits() {
        withModel("$front# Intro\n") { model ->
            assertEquals(listOf("Slide 1: Intro"), dump(model))
            myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.textLength)
            myFixture.type("\n---\n# New\n")
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            assertEquals(listOf("Slide 1: Intro", "Slide 2: New"), dump(model))
        }
    }

    fun testEmptyDeckHasOneSlide() {
        withModel(front) { model -> assertEquals(listOf("Slide 1"), dump(model)) }
    }

    fun testSplitEditorOffersTheSlideOutline() {
        val file = myFixture.addFileToProject("outline.md", "$front# Intro\n\n---\n\n# Agenda\n").virtualFile
        val editor = MarpSplitEditorProvider().createEditor(project, file)
        try {
            val builder = editor.structureViewBuilder
            assertInstanceOf(builder, MarpStructureViewBuilder::class.java)
        }
        finally {
            // The split editor builds its UI in an invokeLater, let that run before the editor goes away.
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Disposer.dispose(editor)
        }
    }
}
