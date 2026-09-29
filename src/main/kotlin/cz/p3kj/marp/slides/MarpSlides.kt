package cz.p3kj.marp.slides

import cz.p3kj.marp.MarpDetector

/**
 * The top-level building blocks of a Markdown document that decide where Marp starts a new slide. They come from a
 * Markdown parser ([MarpSlideParser] uses the Markdown plugin's PSI) in document order, and [MarpSlideSplitter] applies
 * the Marp rules to them. Offsets are character offsets into the document text.
 */
sealed interface MarpBlock {
    val start: Int

    /** A thematic break (`---`, `***`, `___`) at the top level of the document. */
    data class Break(override val start: Int) : MarpBlock

    /** A heading of any depth: Marpit's `headingDivider` also splits before headings in blockquotes and lists. */
    data class Heading(override val start: Int, val level: Int, val text: String) : MarpBlock

    /** A whole HTML comment `<!-- ... -->`, block or inline. Comments are hidden in the slide and hold directives. */
    data class Comment(override val start: Int, val text: String) : MarpBlock

    /** Any other visible block: paragraph, list, code, table, HTML and so on. Only matters as "something visible". */
    data class Content(override val start: Int) : MarpBlock
}

/** A heading inside a slide. [text] has its whitespace collapsed, [offset] is where the heading starts. */
data class MarpHeading(val level: Int, val text: String, val offset: Int)

/**
 * One slide: the range `[startOffset, endOffset)` of the document, and the headings inside it. The slides of a
 * [MarpDeck] cover the whole text without gaps, so the front matter belongs to the first slide.
 *
 * - [startOffset] is the start of the line that starts the slide: the `---` line, the heading line, or 0.
 * - [bodyOffset] is where the slide content begins: after the front matter (first slide), after the `---` line
 *   (slides started by a separator) or at [startOffset] (slides started by a heading).
 * - [contentOffset] is the first non-blank line of the body, the place to put the caret to go to the slide. A slide
 *   without content points at [bodyOffset] while it still has blank lines and at [startOffset] otherwise, so that it
 *   never points into the next slide.
 */
data class MarpSlide(
    val index: Int,
    val startOffset: Int,
    val endOffset: Int,
    val bodyOffset: Int,
    val contentOffset: Int,
    val headings: List<MarpHeading>,
) {
    /** The text of the first heading, `null` when there is none or it is blank. */
    val title: String? get() = headings.firstOrNull()?.text?.takeIf { it.isNotEmpty() }
}

/** The slides of a document (always at least one) and the heading levels that start new slides. */
data class MarpDeck(val slides: List<MarpSlide>, val headingDivider: Set<Int>) {

    /**
     * Index of the slide that contains [offset]. An offset on a separator line belongs to the slide that separator
     * starts, like in the preview. Offsets outside the text clamp to the first or the last slide.
     */
    fun slideIndexAt(offset: Int): Int {
        var low = 0
        var high = slides.size - 1
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (slides[middle].startOffset <= offset) low = middle else high = middle - 1
        }
        return low
    }
}

/**
 * Splits a document into slides like Marpit does. Port of the rules in Marpit (https://github.com/marp-team/marpit,
 * MIT License, Copyright (c) 2018- Marp team (marp-team@marp.app)): `src/markdown/slide.js` splits at every top-level `hr` and
 * `src/markdown/heading_divider.js` adds a hidden `hr` before headings of the `headingDivider` levels, but only when
 * something visible precedes them. A separator directly followed by a divider heading therefore leaves an empty slide
 * in between, and a leading separator an empty first slide. The heading levels come from [MarpHeadingDivider].
 * Linear in the number of blocks.
 */
object MarpSlideSplitter {

    private enum class Kind { BREAK, HEADING }

    private class Boundary(val start: Int, val kind: Kind)

    /**
     * @param frontMatter the front matter of [text] ([MarpDetector.findFrontMatter]), or `null`
     * @param blocks the blocks of the document in document order, none of them inside the front matter
     */
    fun split(text: CharSequence, frontMatter: MarpDetector.FrontMatter?, blocks: List<MarpBlock>): MarpDeck {
        val comments = blocks.filterIsInstance<MarpBlock.Comment>().map { it.text }
        val levels = MarpHeadingDivider.resolve(frontMatter?.body, comments)

        val boundaries = ArrayList<Boundary>()
        var seenVisible = false
        for (block in blocks) {
            when (block) {
                is MarpBlock.Break -> {
                    boundaries += Boundary(lineStart(text, block.start), Kind.BREAK)
                    seenVisible = true
                }
                is MarpBlock.Heading -> {
                    if (block.level in levels && seenVisible) boundaries += Boundary(lineStart(text, block.start), Kind.HEADING)
                    seenVisible = true
                }
                is MarpBlock.Content -> seenVisible = true
                is MarpBlock.Comment -> Unit
            }
        }

        val length = text.length
        val slides = ArrayList<MarpSlide>(boundaries.size + 1)
        val headings = List(boundaries.size + 1) { ArrayList<MarpHeading>() }
        val starts = IntArray(boundaries.size + 1) { if (it == 0) 0 else boundaries[it - 1].start.coerceIn(0, length) }
        for (block in blocks) {
            if (block !is MarpBlock.Heading) continue
            headings[indexOfSlide(starts, block.start)] += MarpHeading(block.level, collapseWhitespace(block.text), block.start)
        }
        for (index in starts.indices) {
            val start = starts[index]
            val end = if (index + 1 < starts.size) starts[index + 1] else length
            val body = when {
                index == 0 -> frontMatter?.endOffset ?: 0
                boundaries[index - 1].kind == Kind.BREAK -> endOfLine(text, start)
                else -> start
            }.coerceIn(start, end)
            slides += MarpSlide(index, start, end, body, contentOffset(text, start, body, end), headings[index])
        }
        return MarpDeck(slides, levels)
    }

    private fun indexOfSlide(starts: IntArray, offset: Int): Int {
        var low = 0
        var high = starts.size - 1
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (starts[middle] <= offset) low = middle else high = middle - 1
        }
        return low
    }

    /** The first non-blank line start of `[body, end)`; without one, [body] if it is inside the slide, else [start]. */
    private fun contentOffset(text: CharSequence, start: Int, body: Int, end: Int): Int {
        var lineStart = body
        while (lineStart < end) {
            var i = lineStart
            while (i < end && (text[i] == ' ' || text[i] == '\t' || text[i] == '\r')) i++
            if (i >= end) break
            if (text[i] != '\n') return lineStart
            lineStart = i + 1
        }
        return if (body < end) body else start
    }

    private fun lineStart(text: CharSequence, offset: Int): Int {
        var i = offset.coerceIn(0, text.length)
        while (i > 0 && text[i - 1] != '\n') i--
        return i
    }

    /** Offset after the line break that ends the line containing [offset], or the text length. */
    private fun endOfLine(text: CharSequence, offset: Int): Int {
        var i = offset
        while (i < text.length && text[i] != '\n') i++
        return if (i < text.length) i + 1 else text.length
    }

    private fun collapseWhitespace(text: String): String {
        val result = StringBuilder(text.length)
        var pendingSpace = false
        for (c in text) {
            if (c.isWhitespace()) {
                pendingSpace = result.isNotEmpty()
            }
            else {
                if (pendingSpace) result.append(' ')
                pendingSpace = false
                result.append(c)
            }
        }
        return result.toString()
    }
}

/**
 * The `headingDivider` global directive, found the way Marpit finds it: in the front matter, then in every directive
 * comment in document order; the last valid value wins and applies to the whole deck (Marpit's `headingDivider` plugin
 * reads `lastGlobalDirectives`). Values are YAML strings (Marpit parses with the failsafe schema) converted by
 * `src/markdown/directives/directives.js`: a list keeps the levels 1 to 6 it contains, `false` turns dividing off, a
 * number `n` from 1 to 6 (read with JavaScript's `parseInt`) means the levels 1 to `n`, anything else is ignored.
 * Off (no levels) is the default, the preview page sets no `headingDivider` option.
 *
 * YAML is not parsed, only lines `headingDivider: value` at the start of a line are recognised, and a list may be
 * written inline (`[1, 3]`) or as a block sequence.
 */
object MarpHeadingDivider {

    /** The levels that start a new slide, from the [frontMatter] body and the raw text of all HTML [comments]. */
    fun resolve(frontMatter: String?, comments: List<String>): Set<Int> {
        var levels: Set<Int> = emptySet()
        val sources = ArrayList<String>(comments.size + 1)
        if (frontMatter != null) sources += frontMatter
        for (comment in comments) commentBody(comment)?.let { sources += it }
        for (source in sources) {
            if (!source.contains(KEY)) continue
            levels = valueIn(source) ?: continue
        }
        return levels
    }

    /** The levels for one directive value as written after `headingDivider:`; `null` when Marpit would ignore it. */
    internal fun parseValue(raw: String): Set<Int>? {
        val value = withoutYamlComment(raw.trim())
        if (value.startsWith("[") && value.endsWith("]")) return parseItems(value.substring(1, value.length - 1).split(','))
        val scalar = unquote(value)
        if (scalar == "false") return emptySet()
        val number = jsParseInt(scalar) ?: return null
        return if (number in 1..MAX_LEVEL) (1..number).toSet() else null
    }

    private const val KEY = "headingDivider"
    private const val MAX_LEVEL = 6

    private fun parseItems(items: List<String>): Set<Int> {
        val levels = HashSet<Int>()
        for (item in items) {
            val number = jsParseInt(unquote(withoutYamlComment(item.trim()))) ?: continue
            if (number in 1..MAX_LEVEL) levels += number
        }
        return levels
    }

    /** The last valid `headingDivider` value in the YAML-like [source], or `null` when there is none. */
    private fun valueIn(source: String): Set<Int>? {
        val lines = source.lines()
        var result: Set<Int>? = null
        var i = 0
        while (i < lines.size) {
            val line = lines[i++]
            if (!line.startsWith(KEY)) continue
            var j = KEY.length
            while (j < line.length && (line[j] == ' ' || line[j] == '\t')) j++
            if (j >= line.length || line[j] != ':') continue
            val value = withoutYamlComment(line.substring(j + 1).trim())
            if (value.isNotEmpty()) {
                parseValue(value)?.let { result = it }
                continue
            }
            // An empty value: a block sequence on the following lines, or nothing (null, ignored).
            val items = ArrayList<String>()
            while (i < lines.size) {
                val item = BLOCK_ITEM.matchEntire(lines[i]) ?: break
                items += item.groupValues[1]
                i++
            }
            if (items.isNotEmpty()) result = parseItems(items)
        }
        return result
    }

    private val BLOCK_ITEM = Regex("^\\s*-\\s+(.+)$")

    /**
     * The trimmed text between `<!--` and `-->` of an HTML comment (Marpit: `/<!--+\s*([\s\S]*?)\s*--+>/`), found
     * without a regular expression so that long runs of whitespace cost linear time. `null` when it is not a comment.
     */
    private fun commentBody(raw: String): String? {
        val open = raw.indexOf("<!--")
        if (open < 0) return null
        val close = raw.indexOf("-->", open + 4)
        if (close < 0) return null
        var start = open + 2
        while (start < close && raw[start] == '-') start++
        var end = close
        while (end > start && raw[end - 1] == '-') end--
        return raw.substring(start, end).trim()
    }

    /** YAML ends a plain scalar at ` #`; a quoted scalar is left alone, [unquote] handles what follows the quote. */
    private fun withoutYamlComment(value: String): String {
        if (value.startsWith("\"") || value.startsWith("'")) return value
        if (value.startsWith("#")) return ""
        for (i in 1 until value.length) {
            if (value[i] == '#' && (value[i - 1] == ' ' || value[i - 1] == '\t')) return value.substring(0, i).trimEnd()
        }
        return value
    }

    /** The text inside a pair of matching quotes at the start of [value], else [value]. */
    private fun unquote(value: String): String {
        if (value.isEmpty()) return value
        val quote = value[0]
        if (quote != '"' && quote != '\'') return value
        val end = value.indexOf(quote, 1)
        return if (end > 0) value.substring(1, end) else value
    }

    /** JavaScript `parseInt(value, 10)`: optional white space and sign, then the leading digits. `null` for NaN. */
    private fun jsParseInt(value: String): Int? {
        var i = 0
        while (i < value.length && value[i].isWhitespace()) i++
        var negative = false
        if (i < value.length && (value[i] == '+' || value[i] == '-')) {
            negative = value[i] == '-'
            i++
        }
        val digitsStart = i
        var number = 0
        while (i < value.length && value[i] in '0'..'9') {
            // Anything above 6 is out of range anyway, the cap only prevents overflow.
            number = minOf(number * 10 + (value[i] - '0'), 1_000_000)
            i++
        }
        if (i == digitsStart) return null
        return if (negative) -number else number
    }
}
