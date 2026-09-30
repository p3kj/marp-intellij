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
 * comments of Marp decks.
 *
 * The comment is looked up in the original Markdown tree, not at [CompletionParameters.position]: with the XML module
 * the IDE parses a Markdown file a second time as HTML (template data), so the caret element is usually an HTML comment
 * of the completion copy. That is why this contributor is registered for every language.
 */
class MarpDirectiveCompletionContributor : CompletionContributor(), DumbAware {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.completionType != CompletionType.BASIC) return
        val file = parameters.originalFile
        if (!MarpDirectiveComments.isMarpDeck(file)) return
        val context = MarpDirectiveCompletion.contextAt(file, parameters.offset) ?: return
        when (val spot = context.spot ?: return) {
            is MarpCompletionSpot.Key -> {
                addKeys(result.withPrefixMatcher(spot.prefix), spot.prefix)
                // Where a directive is being written, plain word completion would only add noise.
                if (context.directiveIsLikely) result.stopHere()
            }
            is MarpCompletionSpot.Value -> {
                val directive = (MarpDirectiveCatalog.resolve(spot.key) as? MarpDirectiveKey.Known)?.directive ?: return
                val values = MarpDirectiveCompletion.values(directive, file)
                if (values.isEmpty()) return
                val prefixed = result.withPrefixMatcher(spot.prefix)
                // Keep the order of the list: the common values come first.
                values.forEachIndexed { index, value ->
                    prefixed.addElement(PrioritizedLookupElement.withPriority(LookupElementBuilder.create(value), (values.size - index).toDouble()))
                }
                result.stopHere()
            }
        }
    }

    /**
     * Every global and local name, and the `_` form of the local ones. The matcher treats `_` as a word break, so
     * `foot` would also match `_footer`: the two forms are only offered together for an empty prefix.
     */
    private fun addKeys(result: CompletionResultSet, prefix: String) {
        val spotOnly = prefix.startsWith("_")
        val plainOnly = prefix.isNotEmpty() && !spotOnly
        for (directive in MarpDirectiveCatalog.ALL) {
            val type = MarpBundle.message(if (directive.scope == MarpDirectiveScope.GLOBAL) "completion.type.global" else "completion.type.local")
            if (!spotOnly) result.addElement(PrioritizedLookupElement.withPriority(key(directive.name, directive, type), 2.0))
            if (!plainOnly && directive.scope == MarpDirectiveScope.LOCAL) {
                result.addElement(PrioritizedLookupElement.withPriority(key("_" + directive.name, directive, MarpBundle.message("completion.type.spot")), 1.0))
            }
        }
    }

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
 * Decides where the completion popup opens by itself while typing in a comment of a Marp deck. It opens where a
 * directive is being written: after `key: ` of a directive with known values, after `_` (no sentence starts with it),
 * and on any line of a comment that already holds a directive. Everywhere else in a comment it stays closed, so typing
 * a presenter note never pops up directive names. Outside comments and outside Marp decks the default applies.
 */
class MarpDirectiveCompletionConfidence : CompletionConfidence() {

    override fun shouldSkipAutopopup(editor: Editor, contextElement: PsiElement, psiFile: PsiFile, offset: Int): ThreeState {
        if (!MarpDirectiveComments.isMarpDeck(psiFile)) return ThreeState.UNSURE
        val context = MarpDirectiveCompletion.contextAt(psiFile, offset) ?: return ThreeState.UNSURE
        val open = when (val spot = context.spot) {
            null -> false
            is MarpCompletionSpot.Value -> {
                val directive = (MarpDirectiveCatalog.resolve(spot.key) as? MarpDirectiveKey.Known)?.directive
                directive != null && MarpDirectiveCompletion.hasValues(directive)
            }
            is MarpCompletionSpot.Key -> context.directiveIsLikely
        }
        return if (open) ThreeState.NO else ThreeState.YES
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
