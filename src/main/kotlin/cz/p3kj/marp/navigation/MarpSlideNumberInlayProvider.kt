package cz.p3kj.marp.navigation

import com.intellij.codeInsight.hints.declarative.EndOfLinePosition
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.OwnBypassCollector
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import cz.p3kj.marp.MarpBundle
import cz.p3kj.marp.directives.MarpDirectiveComments
import cz.p3kj.marp.slides.MarpDeck
import cz.p3kj.marp.slides.MarpSlideParser
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownFile

/**
 * "Slide 3" at the end of the line where each slide of a Marp deck starts: the front matter line for the first slide,
 * the `---` line of a separator, the heading line of a slide that `headingDivider` started. The slides are the same as
 * in the preview. The hints come from a declarative inlay provider, so they are computed in the highlighting pass from
 * the committed PSI, off the EDT, and can be turned off in Settings | Editor | Inlay Hints.
 */
class MarpSlideNumberInlayProvider : InlayHintsProvider, DumbAware {

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        if (file !is MarkdownFile || !MarpDirectiveComments.isMarpDeck(file)) return null
        return SlideNumberCollector
    }

    private object SlideNumberCollector : OwnBypassCollector {
        override fun collectHintsForFile(file: PsiFile, sink: InlayTreeSink) {
            if (file !is MarkdownFile) return
            val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return
            for ((line, number) in labels(MarpSlideParser.deck(file), document)) {
                sink.addPresentation(EndOfLinePosition(line), null, null, HintFormat.default) {
                    text(MarpBundle.message("inlay.slideNumbers.label", number))
                }
            }
        }
    }

    companion object {
        /** The (zero-based line, one-based slide number) pairs to label, one per line and ascending. */
        internal fun labels(deck: MarpDeck, document: Document): List<Pair<Int, Int>> {
            val labels = ArrayList<Pair<Int, Int>>(deck.slides.size)
            for (slide in deck.slides) {
                val line = document.getLineNumber(slide.startOffset.coerceIn(0, document.textLength))
                if (labels.lastOrNull()?.first == line) continue
                labels += line to slide.index + 1
            }
            return labels
        }
    }
}
