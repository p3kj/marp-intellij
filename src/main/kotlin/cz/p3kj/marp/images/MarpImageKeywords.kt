package cz.p3kj.marp.images

/**
 * How a keyword of the image syntax is written: a bare word ([FLAG]), a name with a required value such as `w:400`
 * ([KEY_VALUE]), or a CSS filter that takes an optional argument such as `blur` or `blur:5px` ([FILTER]).
 */
enum class MarpImageKeywordKind { FLAG, KEY_VALUE, FILTER }

/**
 * One keyword of the Marp image syntax. [backgroundOnly] keywords only act when the alt text also has the word `bg`.
 * [defaultValue] is what Marp uses when the optional argument is left out. The documentation text lives in the message
 * bundle under [docKey].
 */
data class MarpImageKeyword(
    val name: String,
    val kind: MarpImageKeywordKind,
    val backgroundOnly: Boolean = false,
    val defaultValue: String? = null,
    val docKey: String = "image.doc.$name",
) {
    /** What completion inserts: a name with a required value ends in `:` and leaves the caret there. */
    val lookupString: String get() = if (kind == MarpImageKeywordKind.KEY_VALUE) "$name:" else name
}

/**
 * The one source of facts about the Marp image keywords, from the bundled Marpit 3.2.3 (`lib/markdown/image/parse.js`,
 * `lib/markdown/background_image/parse.js` and `lib/markdown/background_image/advanced.js`). marp-core 4.4.0 adds no
 * image keyword. The alt text is split at whitespace and every word is matched on its own, so the order does not matter.
 *
 * Any image takes the sizes `w:`/`width:` and `h:`/`height:` (a number of pixels, a CSS length or `auto`) and the CSS
 * filters. Only when the alt text has the word `bg` does the image become a background, with `fit`, `contain`, `cover`,
 * `auto`, a percentage, `left`/`right` with an optional size, `vertical` and `horizontal`.
 */
object MarpImageKeywordCatalog {

    private val PERCENT_WORD = Regex("^(\\d*\\.)?\\d+%$")

    val BG: MarpImageKeyword = MarpImageKeyword("bg", MarpImageKeywordKind.FLAG)

    /** In the order completion ranks them: the background words first, then the sizes, then the filters. */
    val ALL: List<MarpImageKeyword> = listOf(
        BG,
        MarpImageKeyword("left", MarpImageKeywordKind.FLAG, backgroundOnly = true, defaultValue = "50%"),
        MarpImageKeyword("right", MarpImageKeywordKind.FLAG, backgroundOnly = true, defaultValue = "50%"),
        MarpImageKeyword("fit", MarpImageKeywordKind.FLAG, backgroundOnly = true),
        MarpImageKeyword("contain", MarpImageKeywordKind.FLAG, backgroundOnly = true),
        MarpImageKeyword("cover", MarpImageKeywordKind.FLAG, backgroundOnly = true),
        MarpImageKeyword("auto", MarpImageKeywordKind.FLAG, backgroundOnly = true),
        MarpImageKeyword("vertical", MarpImageKeywordKind.FLAG, backgroundOnly = true),
        MarpImageKeyword("horizontal", MarpImageKeywordKind.FLAG, backgroundOnly = true),
        MarpImageKeyword("w", MarpImageKeywordKind.KEY_VALUE),
        MarpImageKeyword("h", MarpImageKeywordKind.KEY_VALUE),
        MarpImageKeyword("width", MarpImageKeywordKind.KEY_VALUE),
        MarpImageKeyword("height", MarpImageKeywordKind.KEY_VALUE),
        MarpImageKeyword("blur", MarpImageKeywordKind.FILTER, defaultValue = "10px"),
        MarpImageKeyword("brightness", MarpImageKeywordKind.FILTER, defaultValue = "1.5"),
        MarpImageKeyword("contrast", MarpImageKeywordKind.FILTER, defaultValue = "2"),
        MarpImageKeyword("drop-shadow", MarpImageKeywordKind.FILTER, defaultValue = "0 5px 10px rgba(0,0,0,.4)"),
        MarpImageKeyword("grayscale", MarpImageKeywordKind.FILTER, defaultValue = "1"),
        MarpImageKeyword("hue-rotate", MarpImageKeywordKind.FILTER, defaultValue = "180deg"),
        MarpImageKeyword("invert", MarpImageKeywordKind.FILTER, defaultValue = "1"),
        MarpImageKeyword("opacity", MarpImageKeywordKind.FILTER, defaultValue = ".5"),
        MarpImageKeyword("saturate", MarpImageKeywordKind.FILTER, defaultValue = "2"),
        MarpImageKeyword("sepia", MarpImageKeywordKind.FILTER, defaultValue = "1"),
    )

    /** `50%` and the like: the scale of a background. It has documentation but is not offered by completion. */
    val PERCENTAGE: MarpImageKeyword =
        MarpImageKeyword("N%", MarpImageKeywordKind.FLAG, backgroundOnly = true, docKey = "image.doc.percent")

    /** The keyword a [word] of the alt text stands for (`left:40%` and `w:400` included), `null` for any other word. */
    fun resolve(word: String): MarpImageKeyword? {
        if (PERCENT_WORD.matches(word)) return PERCENTAGE
        val name = word.substringBefore(':')
        return ALL.firstOrNull { it.name == name }
    }
}
