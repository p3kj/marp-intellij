package cz.p3kj.marp.images

import com.intellij.codeInsight.completion.CompletionConfidence
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ThreeState

/**
 * Completes the Marp keywords in the alt text of an image in a Marp deck: `![b` offers `bg` and the filters that start
 * with a `b`, `![bg le` offers `left`. The keywords that only act on backgrounds (`left`, `cover`, ...) are offered when
 * the alt text has the word `bg`, and `bg` is offered when it does not. A keyword the alt text already has is left
 * out. Values are not completed: `w:` ends in the colon, and a filter or `left` is inserted bare, Marp then uses its
 * default (`blur` is `blur:10px`).
 *
 * Like the directive completion (see [cz.p3kj.marp.directives.MarpDirectiveCompletionContributor]) this is registered
 * for every language, because with the XML module the IDE parses a Markdown file a second time as HTML. The alt text is
 * found in the text of the line, see [MarpImageSyntax.altSpot]. Where the alt text holds nothing but keywords,
 * plain word completion would only add noise, so this contributor stops there. Ctrl+Space in a description still gets
 * the other completions and these keywords.
 */
class MarpImageCompletionContributor : CompletionContributor(), DumbAware {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.completionType != CompletionType.BASIC) return
        val spot = MarpImageSyntax.spotAt(parameters.originalFile, parameters.offset) ?: return
        val prefixed = result.withPrefixMatcher(spot.prefix)
        val background = MarpImageSyntax.hasBackground(spot)
        val taken = spot.otherWords.mapNotNull { MarpImageKeywordCatalog.resolve(it) }.toSet()
        val keywords = MarpImageKeywordCatalog.ALL.filter { keyword ->
            keyword !in taken && (if (background) keyword != MarpImageKeywordCatalog.BG else !keyword.backgroundOnly)
        }
        keywords.forEachIndexed { index, keyword ->
            val element = LookupElementBuilder.create(keyword.lookupString)
                .withTypeText(MarpImageDocs.typeText(keyword), true)
            prefixed.addElement(PrioritizedLookupElement.withPriority(element, (keywords.size - index).toDouble()))
        }
        if (MarpImageSyntax.optionsLikely(spot)) result.stopHere()
    }
}

/**
 * Decides whether the completion popup opens by itself while typing in the alt text of an image. It does where every
 * other word of the alt text is a keyword, and that includes the first word: typing `![b` opens the popup, typing
 * `![bg co` does too. It stays closed once the alt text has a word that is no keyword, as in `![A photo of b`, because
 * that is a description. The default of the platform to match the first letter's case keeps `![Diagram` quiet as well.
 * Outside alt text, and outside Marp decks, the default applies.
 */
class MarpImageCompletionConfidence : CompletionConfidence() {

    override fun shouldSkipAutopopup(editor: Editor, contextElement: PsiElement, psiFile: PsiFile, offset: Int): ThreeState {
        val spot = MarpImageSyntax.spotAt(psiFile, offset) ?: return ThreeState.UNSURE
        return if (MarpImageSyntax.optionsLikely(spot)) ThreeState.NO else ThreeState.YES
    }
}
