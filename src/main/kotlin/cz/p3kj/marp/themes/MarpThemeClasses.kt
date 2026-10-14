package cz.p3kj.marp.themes

/**
 * Reads the slide classes a Marp theme styles, for `class` completion. A slide class is a class on the compound
 * selector of the slide element: `section.lead`, `section.a.b h1`, `section:where(.lead)`, `:is(section.x)`,
 * `:root.invert`, also inside `@media` and in nested rules (`section { &.lead { } }`). Classes that only appear further
 * along a selector (`section .columns`, `section > .note`) or on other elements are not slide classes. Classes in
 * `:not(...)` are skipped. Escaped characters in class names are not supported, and `@import` is not followed.
 */
object MarpThemeClasses {

    /** The slide element: `section`, `:root`, or `:is(section)` / `:where(section)`, not part of a longer word or selector. */
    private val ELEMENT = Regex("(?<![\\w\\-.#:\\\\])(?:section|:root|:(?:is|where)\\(\\s*section\\s*\\))(?![\\w-])", RegexOption.IGNORE_CASE)

    private val NOT = Regex(":not\\([^()]*\\)", RegexOption.IGNORE_CASE)
    private val ATTRIBUTE = Regex("\\[[^\\]]*]")
    private val CLASS = Regex("\\.(-?[A-Za-z_][\\w-]*)")

    /** The slide classes [css] styles, each once, in order of first appearance. */
    fun classesOf(css: CharSequence): List<String> {
        val text = blankCommentsAndStrings(css)
        val found = LinkedHashSet<String>()
        // The selector list of each enclosing block, `null` for a block that is no style rule (top level, at-rules at top level).
        val stack = ArrayDeque<String?>()
        var segmentStart = 0
        for (i in text.indices) {
            when (text[i]) {
                '{' -> {
                    val prelude = text.substring(segmentStart, i).trim()
                    val parent = stack.lastOrNull()
                    if (prelude.startsWith("@")) {
                        stack.addLast(parent)
                    } else {
                        val selector = if (parent == null) prelude else resolveNesting(parent, prelude)
                        collect(selector, found)
                        stack.addLast(selector)
                    }
                    segmentStart = i + 1
                }
                '}' -> {
                    if (stack.isNotEmpty()) stack.removeLast()
                    segmentStart = i + 1
                }
                ';' -> segmentStart = i + 1
            }
        }
        return found.toList()
    }

    /** Comments become one space, strings become spaces (their quotes included), so braces in them mean nothing. */
    private fun blankCommentsAndStrings(css: CharSequence): String {
        val out = StringBuilder(css.length)
        var i = 0
        while (i < css.length) {
            val c = css[i]
            if (c == '/' && i + 1 < css.length && css[i + 1] == '*') {
                val end = css.indexOf("*/", i + 2)
                val stop = if (end < 0) css.length else end + 2
                out.append(' ')
                i = stop
            } else if (c == '"' || c == '\'') {
                var j = i + 1
                while (j < css.length && css[j] != c && css[j] != '\n') {
                    j += if (css[j] == '\\') 2 else 1
                }
                val stop = if (j < css.length && css[j] == c) j + 1 else minOf(j, css.length)
                repeat(stop - i) { out.append(' ') }
                i = stop
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** The selector of a nested rule: `&` stands for the parent, without it the child is a descendant. */
    private fun resolveNesting(parent: String, child: String): String {
        val parents = splitTopLevel(parent)
        return splitTopLevel(child).flatMap { part ->
            parents.map { p -> if ('&' in part) part.replace("&", p) else "$p $part" }
        }.joinToString(", ")
    }

    private fun splitTopLevel(selectors: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        for (i in selectors.indices) {
            when (selectors[i]) {
                '(', '[' -> depth++
                ')', ']' -> if (depth > 0) depth--
                ',' -> if (depth == 0) {
                    parts += selectors.substring(start, i).trim()
                    start = i + 1
                }
            }
        }
        parts += selectors.substring(start).trim()
        return parts.filter { it.isNotEmpty() }
    }

    private fun collect(selector: String, found: MutableSet<String>) {
        for (element in ELEMENT.findAll(selector)) {
            val compound = compoundAfter(selector, element.range.last + 1)
                .replace(NOT, "")
                .replace(ATTRIBUTE, "")
            for (match in CLASS.findAll(compound)) found += match.groupValues[1]
        }
    }

    /** The text from [start] up to the end of the compound selector: white space, a combinator or a comma at depth 0, or an unmatched `)`. */
    private fun compoundAfter(selector: String, start: Int): String {
        var depth = 0
        var i = start
        while (i < selector.length) {
            val c = selector[i]
            when {
                c == '(' || c == '[' -> depth++
                c == ')' || c == ']' -> if (depth == 0) break else depth--
                depth == 0 && (c.isWhitespace() || c == '>' || c == '+' || c == '~' || c == ',') -> break
            }
            i++
        }
        return selector.substring(start, i)
    }
}
