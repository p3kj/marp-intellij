package cz.p3kj.marp.structure

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import cz.p3kj.marp.MarpBundle
import cz.p3kj.marp.slides.MarpDeck
import cz.p3kj.marp.slides.MarpHeading
import cz.p3kj.marp.slides.MarpSlide
import cz.p3kj.marp.slides.MarpSlideParser
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownFile

/**
 * The Structure tool window and File Structure popup content of a Marp deck: one node per slide ("Slide 3: Agenda",
 * titled by its first heading) with the other headings of the slide nested below it. Selecting a node moves the
 * caret into the slide, which highlights it in the preview and scrolls the preview along.
 */
class MarpStructureViewBuilder(private val file: MarkdownFile) : TreeBasedStructureViewBuilder(), DumbAware {

    override fun createStructureViewModel(editor: Editor?): StructureViewModel = MarpStructureViewModel(file, editor)

    /** The file node adds nothing, the slides are the top level. */
    override fun isRootNodeShown(): Boolean = false
}

/** Identity of a slide node. The tree compares nodes by these keys, so expansion and selection survive typing. */
data class MarpSlideKey(val slide: Int)

/** Identity of a heading node: [heading] is the index in [MarpSlide.headings]. */
data class MarpHeadingKey(val slide: Int, val heading: Int)

class MarpStructureViewModel(private val file: MarkdownFile, editor: Editor?) :
    StructureViewModelBase(file, editor, MarpDeckElement(file)), DumbAware {

    /**
     * The node for the caret position: the last heading of the slide at or before the caret, the slide itself before
     * its first (title) heading. The Structure view selects the node whose value equals this.
     */
    override fun getCurrentEditorElement(): Any? {
        val editor = editor ?: return null
        if (!file.isValid) return null
        val deck = MarpSlideParser.deck(file)
        val offset = editor.caretModel.offset
        val slide = deck.slideIndexAt(offset)
        val headings = deck.slides[slide].headings
        val heading = headings.indexOfLast { offset >= it.offset }
        return if (heading >= 1) MarpHeadingKey(slide, heading) else MarpSlideKey(slide)
    }
}

private fun navigateToOffset(file: MarkdownFile, offset: Int, requestFocus: Boolean) {
    val virtualFile = file.virtualFile ?: return
    OpenFileDescriptor(file.project, virtualFile, offset.coerceIn(0, file.textLength)).navigate(requestFocus)
}

private fun canNavigateTo(file: MarkdownFile): Boolean = file.isValid && file.virtualFile != null

internal class MarpDeckElement(private val file: MarkdownFile) : StructureViewTreeElement {

    override fun getValue(): Any = file

    override fun getPresentation(): ItemPresentation = PresentationData(file.name, null, null, null)

    override fun getChildren(): Array<TreeElement> {
        if (!file.isValid) return TreeElement.EMPTY_ARRAY
        val deck: MarpDeck = MarpSlideParser.deck(file)
        return Array(deck.slides.size) { MarpSlideElement(file, deck.slides[it]) }
    }

    override fun navigate(requestFocus: Boolean) = Unit

    override fun canNavigate(): Boolean = false

    override fun canNavigateToSource(): Boolean = false
}

internal class MarpSlideElement(private val file: MarkdownFile, private val slide: MarpSlide) : StructureViewTreeElement {

    private val key = MarpSlideKey(slide.index)

    override fun getValue(): Any = key

    override fun getPresentation(): ItemPresentation {
        // Strings, so that a slide number of 1000 or more is not formatted as "1,000".
        val number = (slide.index + 1).toString()
        val title = slide.title
        val text = if (title == null) MarpBundle.message("structure.slide.untitled", number) else MarpBundle.message("structure.slide.titled", number, title)
        return PresentationData(text, null, null, null)
    }

    /** The first heading is the title of the slide, the others nest by level below it. */
    override fun getChildren(): Array<TreeElement> = nestHeadings(file, slide)

    override fun navigate(requestFocus: Boolean) = navigateToOffset(file, slide.contentOffset, requestFocus)

    override fun canNavigate(): Boolean = canNavigateTo(file)

    override fun canNavigateToSource(): Boolean = canNavigateTo(file)

    override fun equals(other: Any?): Boolean = other is MarpSlideElement && other.key == key

    override fun hashCode(): Int = key.hashCode()
}

internal class MarpHeadingElement(
    private val file: MarkdownFile,
    private val key: MarpHeadingKey,
    private val heading: MarpHeading,
    private val children: List<MarpHeadingElement>,
) : StructureViewTreeElement {

    override fun getValue(): Any = key

    override fun getPresentation(): ItemPresentation = PresentationData(heading.text, null, null, null)

    override fun getChildren(): Array<TreeElement> = Array(children.size) { children[it] }

    override fun navigate(requestFocus: Boolean) = navigateToOffset(file, heading.offset, requestFocus)

    override fun canNavigate(): Boolean = canNavigateTo(file)

    override fun canNavigateToSource(): Boolean = canNavigateTo(file)

    override fun equals(other: Any?): Boolean = other is MarpHeadingElement && other.key == key

    override fun hashCode(): Int = key.hashCode()
}

private class HeadingNode(val index: Int, val heading: MarpHeading) {
    val children = ArrayList<HeadingNode>()
}

/** The headings of [slide] after the first one, nested by level: a heading goes below the closest earlier lower level. */
private fun nestHeadings(file: MarkdownFile, slide: MarpSlide): Array<TreeElement> {
    val roots = ArrayList<HeadingNode>()
    val stack = ArrayList<HeadingNode>()
    for (index in 1 until slide.headings.size) {
        val node = HeadingNode(index, slide.headings[index])
        while (stack.isNotEmpty() && stack.last().heading.level >= node.heading.level) stack.removeAt(stack.lastIndex)
        (if (stack.isEmpty()) roots else stack.last().children) += node
        stack += node
    }
    fun element(node: HeadingNode): MarpHeadingElement =
        MarpHeadingElement(file, MarpHeadingKey(slide.index, node.index), node.heading, node.children.map(::element))
    return Array(roots.size) { element(roots[it]) }
}
