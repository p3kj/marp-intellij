package cz.p3kj.marp.directives

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionConfidence
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ThreeState
import cz.p3kj.marp.MarpBundle
import cz.p3kj.marp.themes.MarpThemeService

/**
 * Completes directive names (`_pa` gives `_paginate`) and values (`paginate: ` offers `true`, `hold`, ...) in HTML
 * comments and in the front matter of Marp decks. The front matter also offers `marp`, and does not offer a key that
 * the front matter already has (a repeated key makes Marp reject the whole front matter). Every name shows where the
 * directive applies as its type text (`class` and `_class` differ in exactly that).
 *
 * The comment is looked up in the original Markdown tree, not at [CompletionParameters.position]: with the XML module
 * the IDE parses a Markdown file a second time as HTML (template data), so the caret element is usually an HTML comment
 * of the completion copy. That is why this contributor is registered for every language. The same registration is what
 * reaches the front matter: with the YAML plugin the Markdown plugin injects YAML there, the caret is then in an injected
 * file, and [MarpFrontMatter.hostOf] maps it back to the Markdown file. The other completion of the front matter (the
 * keys of the Markdown plugin's front matter schema, such as `title`) stays, only the values of a Marp key are ours alone.
 */
class MarpDirectiveCompletionContributor : CompletionContributor(), DumbAware {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.completionType != CompletionType.BASIC) return
        val (file, offset) = MarpFrontMatter.hostOf(parameters.originalFile, parameters.offset)
        if (!MarpDirectiveComments.isMarpDeck(file)) return
        val text = MarpFrontMatter.text(file) ?: return
        val frontMatterSpot = MarpFrontMatter.completionSpot(text, offset)
        if (frontMatterSpot != null) {
            fillFrontMatter(frontMatterSpot, file, text, offset, result)
            return
        }
        val context = MarpDirectiveCompletion.contextAt(file, offset) ?: return
        when (val spot = context.spot ?: return) {
            is MarpCompletionSpot.Key -> {
                addKeys(result.withPrefixMatcher(spot.prefix), spot.prefix, MarpDirectiveCatalog.ALL, frontMatter = false)
                // Where a directive is being written, plain word completion would only add noise.
                if (context.directiveIsLikely) result.stopHere()
            }
            is MarpCompletionSpot.Value -> addValues(result, spot, MarpDirectiveCatalog.resolve(spot.key), file)
        }
    }

    /** The front matter: no `stopHere()` for keys, so the other front matter keys stay. */
    private fun fillFrontMatter(spot: MarpCompletionSpot, file: PsiFile, text: CharSequence, offset: Int, result: CompletionResultSet) {
        when (spot) {
            is MarpCompletionSpot.Key -> {
                val taken = MarpFrontMatter.keysOutsideLine(text, offset)
                addKeys(result.withPrefixMatcher(spot.prefix), spot.prefix, MarpDirectiveCatalog.FRONT_MATTER_ONLY + MarpDirectiveCatalog.ALL, frontMatter = true, exclude = taken)
            }
            is MarpCompletionSpot.Value -> addValues(result, spot, MarpDirectiveCatalog.resolveInFrontMatter(spot.key), file)
        }
    }

    /** The values of the directive [key] resolves to, in the order of the list: the common values come first. */
    private fun addValues(result: CompletionResultSet, spot: MarpCompletionSpot.Value, key: MarpDirectiveKey, file: PsiFile) {
        val directive = (key as? MarpDirectiveKey.Known)?.directive ?: return
        val values = MarpDirectiveCompletion.values(directive, file)
        if (values.isEmpty()) return
        val prefixed = result.withPrefixMatcher(spot.prefix)
        values.forEachIndexed { index, value ->
            prefixed.addElement(PrioritizedLookupElement.withPriority(LookupElementBuilder.create(value), (values.size - index).toDouble()))
        }
        result.stopHere()
    }

    /**
     * Every name of [directives] and the `_` form of the local ones, except the names in [exclude]. The matcher treats
     * `_` as a word break, so `foot` would also match `_footer`: the two forms are only offered together for an empty
     * prefix. The items keep the order of [directives] and each `_` form sits right behind its plain name, so that the
     * pair is seen together: the priorities count down from the first item. Each item shows in its type text where the
     * directive applies, see [scopeText].
     */
    private fun addKeys(result: CompletionResultSet, prefix: String, directives: List<MarpDirective>, frontMatter: Boolean, exclude: Set<String> = emptySet()) {
        val spotOnly = prefix.startsWith("_")
        val plainOnly = prefix.isNotEmpty() && !spotOnly
        val items = ArrayList<LookupElement>()
        for (directive in directives) {
            if (!spotOnly && directive.name !in exclude) items.add(key(directive.name, directive, scopeText(directive, spot = false, frontMatter)))
            val spotName = "_" + directive.name
            if (!plainOnly && directive.scope == MarpDirectiveScope.LOCAL && spotName !in exclude) {
                items.add(key(spotName, directive, scopeText(directive, spot = true, frontMatter)))
            }
        }
        items.forEachIndexed { index, item -> result.addElement(PrioritizedLookupElement.withPriority(item, (items.size - index).toDouble())) }
    }

    /**
     * Where the directive applies, for the type text. In a comment a plain local directive holds from its slide on and
     * the `_` form for that slide only. The front matter comes before the first slide, so a plain local directive holds
     * for all slides there and the `_` form for the first one only (Marpit applies a spot directive of the front matter
     * to the first slide). The `marp` key belongs to the front matter and is no directive of the engine.
     */
    private fun scopeText(directive: MarpDirective, spot: Boolean, frontMatter: Boolean): String = MarpBundle.message(
        when {
            directive.origin == MarpDirectiveOrigin.MARP_VSCODE -> "completion.type.frontMatter"
            directive.scope == MarpDirectiveScope.GLOBAL -> "completion.scope.deck"
            spot -> if (frontMatter) "completion.scope.firstSlide" else "completion.scope.spot"
            else -> if (frontMatter) "completion.scope.allSlides" else "completion.scope.following"
        },
    )

    private fun key(name: String, directive: MarpDirective, type: String): LookupElement =
        LookupElementBuilder.create(name).withTypeText(type, true).withInsertHandler(KeyInsertHandler(directive))

    /** Adds `: ` after the name and, for directives with a list of values, opens the value list. */
    private class KeyInsertHandler(private val directive: MarpDirective) : InsertHandler<LookupElement> {
        override fun handleInsert(context: InsertionContext, item: LookupElement) {
            val document = context.document
            val editor = context.editor
            val tail = context.tailOffset
            val text = document.charsSequence
            if (tail < text.length && text[tail] == ':') {
                // Replacing the name of an existing entry: only move behind its colon.
                var caret = tail + 1
                if (caret < text.length && text[caret] == ' ') caret++
                editor.caretModel.moveToOffset(caret)
            }
            else {
                document.insertString(tail, ": ")
                editor.caretModel.moveToOffset(tail + 2)
            }
            if (MarpDirectiveCompletion.hasValues(directive)) AutoPopupController.getInstance(context.project).scheduleAutoPopup(editor)
        }
    }
}

/**
 * Decides where the completion popup opens by itself while typing in a Marp deck.
 *
 * In a comment it opens where a directive is being written: after `key: ` of a directive with known values, after `_`
 * (no sentence starts with it), and on any line of a comment that already holds a directive. Everywhere else in a
 * comment it stays closed, so typing a presenter note never pops up directive names.
 *
 * In the front matter it opens for every key and for the values of a directive that has some. Other values are left to
 * the YAML support, which is asked when the caret is in the injected YAML file and gets mapped back to the Markdown
 * file here. Outside comments, the front matter and Marp decks the default applies.
 */
class MarpDirectiveCompletionConfidence : CompletionConfidence() {

    override fun shouldSkipAutopopup(editor: Editor, contextElement: PsiElement, psiFile: PsiFile, offset: Int): ThreeState {
        val (file, hostOffset) = MarpFrontMatter.hostOf(psiFile, offset)
        if (!MarpDirectiveComments.isMarpDeck(file)) return ThreeState.UNSURE
        val text = MarpFrontMatter.text(file) ?: return ThreeState.UNSURE
        val frontMatterSpot = MarpFrontMatter.completionSpot(text, hostOffset)
        if (frontMatterSpot != null) return frontMatterConfidence(frontMatterSpot)
        val context = MarpDirectiveCompletion.contextAt(file, hostOffset) ?: return ThreeState.UNSURE
        val open = when (val spot = context.spot) {
            null -> false
            is MarpCompletionSpot.Value -> valuesFollow(MarpDirectiveCatalog.resolve(spot.key))
            is MarpCompletionSpot.Key -> context.directiveIsLikely
        }
        return if (open) ThreeState.NO else ThreeState.YES
    }

    private fun frontMatterConfidence(spot: MarpCompletionSpot): ThreeState = when (spot) {
        is MarpCompletionSpot.Key -> ThreeState.NO
        is MarpCompletionSpot.Value -> if (valuesFollow(MarpDirectiveCatalog.resolveInFrontMatter(spot.key))) ThreeState.NO else ThreeState.UNSURE
    }

    private fun valuesFollow(key: MarpDirectiveKey): Boolean {
        val directive = (key as? MarpDirectiveKey.Known)?.directive ?: return false
        return MarpDirectiveCompletion.hasValues(directive)
    }
}

/** What the contributor and the confidence share. */
internal object MarpDirectiveCompletion {

    /**
     * The completion [spot] (`null` when the caret is in a comment but not at a spot, as in a sentence) and whether a
     * directive is likely being written there: the name starts with `_` (no sentence does) or the comment already holds
     * a directive. Everywhere else the comment may be a presenter note.
     */
    class Context(val spot: MarpCompletionSpot?, val directiveIsLikely: Boolean)

    /**
     * The completion context at [offset] of [file], `null` when the caret is not in a comment. The caret right before
     * `<!--` or right after `-->` is not in the comment, although the comment element is found there.
     */
    fun contextAt(file: PsiFile, offset: Int): Context? {
        val markdown = MarpDirectiveComments.markdownFile(file) ?: return null
        val element = MarpDirectiveComments.commentElementAt(markdown, offset) ?: return null
        val text = element.text
        val caret = offset - element.textRange.startOffset
        val range = MarpDirectiveComments.parse(text)?.range
        if (range != null && (caret <= range.startOffset || caret >= range.endOffset)) return null
        val spot = MarpDirectiveComments.completionSpot(text, caret)
        val likely = spot is MarpCompletionSpot.Key && (spot.prefix.startsWith("_") || isDirectiveWithoutLineAt(text, caret))
        return Context(spot, likely)
    }

    /** Whether the comment [text] is a directive comment when the line with the [caret], still being typed, is left out. */
    private fun isDirectiveWithoutLineAt(text: String, caret: Int): Boolean {
        var lineStart = caret
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        // On the first line the comment marker stays.
        lineStart = maxOf(lineStart, text.indexOf("<!--") + "<!--".length)
        var lineEnd = text.indexOf('\n', caret).let { if (it < 0) text.length else it }
        val close = text.indexOf("-->", caret)
        if (close in caret until lineEnd) lineEnd = close
        if (lineStart > lineEnd) return false
        return MarpDirectiveComments.parse(text.removeRange(lineStart, lineEnd))?.isDirective == true
    }

    fun hasValues(directive: MarpDirective): Boolean = directive.suggestions.isNotEmpty() || directive.themeValue

    /** The values to offer for [directive]: its suggestions, and for `theme` the built-in and the custom theme names. */
    fun values(directive: MarpDirective, file: PsiFile): List<String> {
        if (!directive.themeValue) return directive.suggestions
        return (MarpDirectiveCatalog.BUILT_IN_THEMES + MarpThemeService.getInstance(file.project).cachedThemeNames()).distinct()
    }
}
