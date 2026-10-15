package cz.p3kj.marp.directives

import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import cz.p3kj.marp.slides.MarpSlideParser

/**
 * The values `class` completion offers: `lead` and `invert` first, then the slide classes of the theme of the deck.
 *
 * The theme is chosen like Marpit does: every `theme` directive naming a known theme sets it, so the last one that
 * names a custom or built-in theme wins and a name nobody knows is ignored. A custom theme wins over a built-in one with
 * the same name. A deck without any `theme` value uses `default`. When only unknown names are written (the theme may be
 * missing or still loading) the classes of all custom themes are offered.
 */
internal object MarpClassValues {

    /**
     * The values of the global `theme` directive in document order: the front matter first, then the directive comments.
     * Only plain `theme` entries count (not `_theme`, which Marp ignores), in comments that are directives and not notes.
     */
    fun themeValues(frontMatter: MarpParsedFrontMatter?, comments: List<String>): List<String> {
        val entries = ArrayList<MarpDirectiveEntry>()
        if (frontMatter != null && frontMatter.isMapping) entries += frontMatter.entries
        for (comment in comments) {
            val parsed = MarpDirectiveComments.parse(comment)?.takeIf { it.isMapping && !it.isMagic } ?: continue
            entries += parsed.entries
        }
        return entries.mapNotNull { entry ->
            val key = entry.resolved as? MarpDirectiveKey.Known ?: return@mapNotNull null
            if (!key.directive.themeValue || key.spot) return@mapNotNull null
            entry.value.trim().takeIf { it.isNotEmpty() }
        }
    }

    /** [themeValues] of the deck [file] belongs to, cached until the file changes. Call in a read action. */
    fun themeValuesOf(file: PsiFile): List<String> {
        val markdown = MarpDirectiveComments.markdownFile(file) ?: return emptyList()
        return CachedValuesManager.getCachedValue(markdown) {
            val frontMatter = MarpFrontMatter.parse(markdown.viewProvider.contents)
            CachedValueProvider.Result.create(themeValues(frontMatter, MarpSlideParser.deck(markdown).comments), markdown)
        }
    }

    /** The values to offer for the theme values of a deck and the classes of the custom themes by name. */
    fun offered(themeValues: List<String>, customClasses: Map<String, List<String>>): List<String> {
        val builtIn = MarpDirectiveCatalog.BUILT_IN_THEMES
        val theme = themeValues.lastOrNull { it in customClasses || it in builtIn }
        val extra = when {
            theme != null -> customClasses[theme] ?: MarpDirectiveCatalog.BUILT_IN_THEME_CLASSES[theme].orEmpty()
            themeValues.isEmpty() -> MarpDirectiveCatalog.BUILT_IN_THEME_CLASSES["default"].orEmpty()
            else -> customClasses.values.flatten()
        }
        return (CLASS_SUGGESTIONS + extra).distinct()
    }

    private val CLASS_SUGGESTIONS: List<String> =
        MarpDirectiveCatalog.find("class")?.suggestions.orEmpty()
}
