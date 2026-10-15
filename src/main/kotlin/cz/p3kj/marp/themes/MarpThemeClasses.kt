package cz.p3kj.marp.themes

/**
 * Reads the slide classes a Marp theme styles, for `class` completion. A slide class is a class on the compound
 * selector of the slide element: `section.lead`, `section.a.b h1`, `section:where(.lead)`, `:is(section.x)`,
 * `:root.invert`, also inside `@media` and in nested rules (`section { &.lead { } }`). Classes that only appear further
 * along a selector (`section .columns`, `section > .note`) or on other elements are not slide classes. The arguments
 * of `:not(...)` and `:has(...)` are skipped. Escaped characters in class names are not supported, and `@import` is not
 * followed. A theme can be large and comes from the network, so the work stays linear: a nested rule whose resolved
 * selector would be longer than [MAX_SELECTOR] or have more than [MAX_COMBINATIONS] parent and child pairs is not
 * resolved and its classes are not collected, and neither is a block nested deeper than [MAX_DEPTH].
 */
object MarpThemeClasses {

    /** The slide element: `section`, `:root`, or `:is(section)` / `:where(section)`, not part of a longer word or selector. */
    private val ELEMENT = Regex("(?<![\\w\\-.#:\\\\])(?:section|:root|:(?:is|where)\\(\\s*section\\s*\\))(?![\\w-])", RegexOption.IGNORE_CASE)

    private val ATTRIBUTE = Regex("\\[[^\\[\\]]*]")

    /** A CSS identifier after a dot: letters, digits, `-`, `_` and any non-ASCII character. */
    private val CLASS = Regex("""\.(-?[A-Za-z_\u0080-\uFFFF][\w\-\u0080-\uFFFF]*)""")

    private const val MAX_SELECTOR = 2048
    private const val MAX_COMBINATIONS = 256

    /** Blocks nested deeper are not resolved or collected: every open block holds a selector. */
    private const val MAX_DEPTH = 32

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
                    if (stack.size >= MAX_DEPTH) {
                        stack.addLast(null)
                    } else if (prelude.startsWith("@")) {
                        stack.addLast(parent)
                    } else {
                        val selector = if (parent == null) prelude else resolveNesting(parent, prelude)
                        if (selector != null) collect(selector, found)
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

    /**
     * The selector of a nested rule: `&` stands for the parent, without it the child is a descendant. `null` when the
     * result would be too big, see [MAX_SELECTOR] and [MAX_COMBINATIONS].
     */
    private fun resolveNesting(parent: String, child: String): String? {
        if (parent.length > MAX_SELECTOR || child.length > MAX_SELECTOR) return null
        val parents = splitTopLevel(parent)
        val children = splitTopLevel(child)
        if (parents.size * children.size > MAX_COMBINATIONS) return null
        val out = StringBuilder()
        for (part in children) {
            val ampersands = part.count { it == '&' }
            for (p in parents) {
                val size = if (ampersands > 0) part.length + ampersands * p.length else p.length + 1 + part.length
                if (out.length + size + 2 > MAX_SELECTOR) return null
                if (out.isNotEmpty()) out.append(", ")
                if (ampersands > 0) out.append(part.replace("&", p)) else out.append(p).append(' ').append(part)
            }
        }
        return out.toString()
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
        val text = withoutNegations(selector)
        var position = 0
        while (position <= text.length) {
            val element = ELEMENT.find(text, position) ?: break
            val end = compoundEnd(text, element.range.last + 1)
            val compound = text.substring(element.range.last + 1, end).replace(ATTRIBUTE, "")
            for (match in CLASS.findAll(compound)) found += match.groupValues[1]
            // What lies inside the compound was read with it, an unclosed parenthesis must not be scanned again.
            position = maxOf(end, element.range.last + 1)
        }
    }

    /** [selector] without the arguments of `:not(...)` and `:has(...)`, found by counting parentheses. */
    private fun withoutNegations(selector: String): String {
        val out = StringBuilder(selector.length)
        var i = 0
        while (i < selector.length) {
            if (selector[i] == ':' && (selector.startsWith(":not(", i, ignoreCase = true) || selector.startsWith(":has(", i, ignoreCase = true))) {
                var depth = 0
                i += 4
                while (i < selector.length) {
                    val c = selector[i++]
                    if (c == '(') depth++ else if (c == ')' && --depth == 0) break
                }
            } else {
                out.append(selector[i++])
            }
        }
        return out.toString()
    }

    /** The end of the compound selector that starts at [start]: white space, a combinator or a comma at depth 0, or an unmatched `)`. */
    private fun compoundEnd(selector: String, start: Int): Int {
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
        return i
    }
}
