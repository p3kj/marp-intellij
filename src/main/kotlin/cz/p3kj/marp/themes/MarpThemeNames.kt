package cz.p3kj.marp.themes

/**
 * Reads the name of a Marp theme from its CSS the way Marpit does (`marpit-postcss-meta`): a line `@theme name` in a
 * CSS block comment (also with a leading `*` or `!`), where the name is the rest of the line. When a stylesheet
 * declares several, the last one wins.
 */
object MarpThemeNames {

    private val BLOCK_COMMENT = Regex("/\\*([\\s\\S]*?)\\*/")

    /** Marpit: `/^[*!\s]*@([\w-]+)\s+(.+)$/gim`, restricted to the `theme` meta. */
    private val THEME_META = Regex("^[*!\\s]*@theme\\s+(.+)$", RegexOption.MULTILINE)

    /** The theme name declared in [css], or `null` when it declares none. */
    fun nameOf(css: String): String? {
        var name: String? = null
        for (comment in BLOCK_COMMENT.findAll(css)) {
            for (meta in THEME_META.findAll(comment.groupValues[1])) {
                val value = meta.groupValues[1].trim()
                if (value.isNotEmpty()) name = value
            }
        }
        return name
    }
}
