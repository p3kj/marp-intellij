package cz.p3kj.marp.themes

/** The theme name declared in a stylesheet and the offset of its first character in that text. */
data class MarpThemeDeclaration(val name: String, val nameOffset: Int)

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
    fun nameOf(css: CharSequence): String? = declarationOf(css)?.name

    /** The last theme declaration in [css] with the offset of the name, or `null` when it declares none. */
    fun declarationOf(css: CharSequence): MarpThemeDeclaration? {
        var declaration: MarpThemeDeclaration? = null
        for (comment in BLOCK_COMMENT.findAll(css)) {
            val body = comment.groups[1]!!
            for (meta in THEME_META.findAll(body.value)) {
                val value = meta.groupValues[1].trim()
                if (value.isNotEmpty()) declaration = MarpThemeDeclaration(value, body.range.first + meta.groups[1]!!.range.first)
            }
        }
        return declaration
    }
}
