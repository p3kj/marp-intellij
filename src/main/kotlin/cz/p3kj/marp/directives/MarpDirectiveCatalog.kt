package cz.p3kj.marp.directives

import cz.p3kj.marp.slides.MarpHeadingDivider

/** Where a directive applies: the whole deck ([GLOBAL], the last value wins) or from a slide on ([LOCAL]). */
enum class MarpDirectiveScope { GLOBAL, LOCAL }

/** Who defines a directive: Marpit (the framework) or marp-core (the engine on top of it). */
enum class MarpDirectiveOrigin { MARPIT, MARP_CORE }

/** How the value of a directive is checked, see [MarpDirectiveCatalog.isValid]. */
enum class MarpValueCheck { NONE, ONE_OF, ONE_OF_IGNORE_CASE, HEADING_DIVIDER }

/**
 * One Marp directive. [suggestions] are the values offered by completion and, with [MarpValueCheck.ONE_OF] or
 * [MarpValueCheck.ONE_OF_IGNORE_CASE], the only valid ones. [themeValue] marks `theme`, whose values are theme names.
 * The documentation text lives in the message bundle under [docKey].
 */
data class MarpDirective(
    val name: String,
    val scope: MarpDirectiveScope,
    val origin: MarpDirectiveOrigin = MarpDirectiveOrigin.MARPIT,
    val suggestions: List<String> = emptyList(),
    val check: MarpValueCheck = MarpValueCheck.NONE,
    val themeValue: Boolean = false,
) {
    val docKey: String get() = "directive.doc.$name"
}

/** What a key in a directive comment means, see [MarpDirectiveCatalog.resolve]. */
sealed interface MarpDirectiveKey {
    /** A directive; [spot] is true for the `_name` form that applies to the current slide only. */
    data class Known(val directive: MarpDirective, val spot: Boolean) : MarpDirectiveKey

    /** `_theme` and the like: Marp ignores a global directive with an underscore. */
    data class GlobalWithUnderscore(val directive: MarpDirective) : MarpDirectiveKey

    /** Not a directive. [suggestion] is a similar valid spelling, if there is one. */
    data class Unknown(val suggestion: String?) : MarpDirectiveKey
}

/**
 * The one source of facts about Marp directives, from Marpit 3.2 and marp-core 4.4 (the versions bundled in the
 * preview page). Comment features (completion, documentation, inspection) use it, and the front matter support reuses
 * [ALL], [resolve], [isValid] and the documentation keys. Keys that only the front matter knows, such as `marp`, are
 * not directives and are not listed here.
 *
 * Marpit recognises the global directives `theme`, `style`, `headingDivider` and `lang`, and the local directives
 * `paginate`, `header`, `footer`, `class`, `color` and the `background*` family. Every local directive also has a
 * spot form with an underscore (`_class`) that applies to one slide. marp-core adds the global `size` and `math`.
 */
object MarpDirectiveCatalog {

    /** Themes that ship with marp-core. */
    val BUILT_IN_THEMES: List<String> = listOf("default", "gaia", "uncover")

    val ALL: List<MarpDirective> = listOf(
        MarpDirective("theme", MarpDirectiveScope.GLOBAL, themeValue = true),
        MarpDirective("style", MarpDirectiveScope.GLOBAL),
        MarpDirective(
            "headingDivider", MarpDirectiveScope.GLOBAL,
            suggestions = listOf("1", "2", "3", "4", "5", "6", "false"), check = MarpValueCheck.HEADING_DIVIDER,
        ),
        MarpDirective("lang", MarpDirectiveScope.GLOBAL),
        MarpDirective("size", MarpDirectiveScope.GLOBAL, MarpDirectiveOrigin.MARP_CORE, suggestions = listOf("16:9", "4:3")),
        MarpDirective(
            "math", MarpDirectiveScope.GLOBAL, MarpDirectiveOrigin.MARP_CORE,
            suggestions = listOf("mathjax", "katex"), check = MarpValueCheck.ONE_OF,
        ),
        MarpDirective(
            "paginate", MarpDirectiveScope.LOCAL,
            suggestions = listOf("true", "false", "hold", "skip"), check = MarpValueCheck.ONE_OF_IGNORE_CASE,
        ),
        MarpDirective("header", MarpDirectiveScope.LOCAL),
        MarpDirective("footer", MarpDirectiveScope.LOCAL),
        MarpDirective("class", MarpDirectiveScope.LOCAL, suggestions = listOf("lead", "invert")),
        MarpDirective("backgroundColor", MarpDirectiveScope.LOCAL),
        MarpDirective("backgroundImage", MarpDirectiveScope.LOCAL),
        MarpDirective(
            "backgroundPosition", MarpDirectiveScope.LOCAL,
            suggestions = listOf("center", "top", "bottom", "left", "right"),
        ),
        MarpDirective(
            "backgroundRepeat", MarpDirectiveScope.LOCAL,
            suggestions = listOf("no-repeat", "repeat", "repeat-x", "repeat-y"),
        ),
        MarpDirective("backgroundSize", MarpDirectiveScope.LOCAL, suggestions = listOf("cover", "contain", "auto")),
        MarpDirective("color", MarpDirectiveScope.LOCAL),
    )

    private val BY_NAME: Map<String, MarpDirective> = ALL.associateBy { it.name }

    /** The directive called [name], spelled exactly (directive names are case sensitive). */
    fun find(name: String): MarpDirective? = BY_NAME[name]

    /** Classifies a key of a directive comment. */
    fun resolve(key: String): MarpDirectiveKey {
        val direct = BY_NAME[key]
        if (direct != null) return MarpDirectiveKey.Known(direct, spot = false)
        if (key.startsWith(SPOT_PREFIX)) {
            val base = BY_NAME[key.substring(1)]
            if (base != null) {
                return if (base.scope == MarpDirectiveScope.LOCAL) MarpDirectiveKey.Known(base, spot = true)
                else MarpDirectiveKey.GlobalWithUnderscore(base)
            }
        }
        return MarpDirectiveKey.Unknown(suggest(key))
    }

    /** Every key that Marp accepts: the directive names and the `_` form of the local ones. */
    val VALID_KEYS: List<String> = ALL.map { it.name } +
        ALL.filter { it.scope == MarpDirectiveScope.LOCAL }.map { SPOT_PREFIX + it.name }

    /**
     * The valid key closest to [key]: one that only differs in case, or within a small edit distance (a quarter of the
     * candidate's length, at least one edit). `null` when nothing is close, which is the case for ordinary words such
     * as `Note` or `Todo`, so that presenter notes written like `Note: text` are not reported.
     */
    fun suggest(key: String): String? {
        val lower = key.lowercase()
        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        for (candidate in VALID_KEYS) {
            val distance = editDistance(lower, candidate.lowercase())
            if (distance <= maxOf(1, candidate.length / 4) && distance < bestDistance) {
                best = candidate
                bestDistance = distance
            }
        }
        return best
    }

    /**
     * Whether Marp would honour [rawValue], the value as written (`[1, 3]`, `'lead'`) with [value] its unquoted
     * text. Only directives with a [MarpValueCheck] are checked, the free text ones accept everything.
     */
    fun isValid(directive: MarpDirective, rawValue: String, value: String): Boolean = when (directive.check) {
        MarpValueCheck.NONE -> true
        MarpValueCheck.ONE_OF -> value in directive.suggestions
        MarpValueCheck.ONE_OF_IGNORE_CASE -> value.lowercase() in directive.suggestions
        MarpValueCheck.HEADING_DIVIDER -> MarpHeadingDivider.parseValue(rawValue) != null
    }

    private const val SPOT_PREFIX = "_"

    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(substitution, previous[j] + 1, current[j - 1] + 1)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
