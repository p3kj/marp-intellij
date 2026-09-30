package cz.p3kj.marp.slides

/**
 * Moves a slide of a [MarpDeck] to another place in the text, as one replacement of a range. Pure text logic, so the
 * caller decides how to apply it (the editor actions and the statement mover use [move], the drag in the slide overview
 * uses [moveTo]). The moved slide swaps places with the slides it passes, so a move by one place is a swap of two
 * neighbouring slides.
 *
 * A slide travels as the text from its separator line (`---`, `***`, `___`) to the start of the next slide, so its
 * separator style, its local directives and its presenter notes move with it. The front matter is the exception: it
 * belongs to the first slide in [MarpDeck] but it stays at the top. When the first slide is involved, the bodies swap and
 * the separator line that was between the two swapped runs stays between them.
 *
 * A separator that lands right below a paragraph line, or a paragraph line right above a separator, turns `---` into a
 * setext heading underline and the slide break is lost. So a blank line is added where a moved slide would meet a
 * separator without one: after a moved last slide, and before the first piece when the slide above ends in a paragraph
 * (a `***` deck, tight decks). A deck with a blank line before each separator is not changed by this. What follows the
 * last slide (the line breaks at the end of the file) stays at the end of the file, so moving the last slide up and back
 * down gives the original text.
 *
 * Decks with a `headingDivider` are not supported ([supports]): swapping there can merge slides (a slide without a
 * divider heading at its start joins the slide before it) and reorder competing `headingDivider` comments.
 */
object MarpSlideReorder {

    /**
     * Replace `[start, end)` of the text with [text]. The moved slide was the piece `[movedFrom, movedFrom + movedLength)`
     * and starts at [movedTo] after the edit, its first non-blank line at [contentTo].
     */
    data class Edit(
        val start: Int,
        val end: Int,
        val text: String,
        val movedFrom: Int,
        val movedLength: Int,
        val movedTo: Int,
        val contentTo: Int,
    ) {
        /**
         * Where a caret at [caret] (an offset before the edit) is after it: inside the moved piece it keeps its place in
         * it, anywhere else (the front matter, the separator line that stays) it goes to the first non-blank line of the
         * moved piece, like the navigation puts the caret at the content of a slide.
         */
        fun caretAfter(caret: Int): Int =
            if (caret in movedFrom..movedFrom + movedLength) movedTo + (caret - movedFrom) else contentTo
    }

    /** `false` for decks that use `headingDivider`, see the class comment. */
    fun supports(deck: MarpDeck): Boolean = deck.headingDivider.isEmpty()

    /**
     * The edit that moves slide [index] one place towards the start ([down] is `false`) or the end of [text], which
     * [deck] must describe. `null` when the deck is not supported, [index] is not a slide, or the slide is already the
     * first (up) or the last (down). The one-place case of [moveTo].
     */
    fun move(text: CharSequence, deck: MarpDeck, index: Int, down: Boolean): Edit? =
        moveTo(text, deck, index, if (down) index + 1 else index - 1)

    /**
     * The edit that moves slide [from] to the place of slide [to] (its index after the move) in [text], which [deck]
     * must describe. `null` when the deck is not supported, [from] or [to] is not a slide, or they are the same.
     *
     * The moved slide swaps places with the run of slides it passes: moving down, the slide and the run after it up to
     * [to]; moving up, the run from [to] up to the slide before it, and the slide. That is one swap of two neighbouring
     * runs of slides, and [move] is the case where the run is a single slide.
     */
    fun moveTo(text: CharSequence, deck: MarpDeck, from: Int, to: Int): Edit? {
        val slides = deck.slides
        if (!supports(deck) || from !in slides.indices || to !in slides.indices || from == to) return null
        val down = to > from
        val firstIndex = minOf(from, to)
        val lastIndex = maxOf(from, to)
        // The upper run starts at firstIndex, the lower run at lowerFirst and ends with lastIndex. Whichever contains the
        // moved slide, that slide is a run of its own, so the moved text is one piece below.
        val lowerFirst = if (down) from + 1 else from
        val upper = slides[firstIndex]
        val lower = slides[lowerFirst]
        val lowerEnd = slides[lastIndex].endOffset
        val atEnd = lowerEnd >= text.length

        // The first slide swaps bodies and leaves its front matter and the separator line of the next run in place.
        val first = firstIndex == 0
        val start = if (first) upper.bodyOffset else upper.startOffset
        val qStart = if (first) lower.bodyOffset else lower.startOffset
        val separator = if (first) lineEnded(text.slice(lower.startOffset, lower.bodyOffset)) else ""
        val p = text.slice(start, lower.startOffset)
        val q = text.slice(qStart, lowerEnd)

        // The piece that lands first starts with a separator line (unless the front matter is above). Directly under a
        // paragraph line a `---` would be a setext underline and the two slides would merge, so a blank line goes between.
        // A slide without a body is preceded by a separator or the front matter fence, and neither is a paragraph.
        val lead = if (!first && slides[firstIndex - 1].bodyOffset < start && !lastLineIsBlank(lineBefore(text, start))) "\n" else ""
        val before = lead + blankEnded(q) + separator
        // An empty first slide that moves down would leave two separator lines in a row, so a blank line goes between.
        val after = when {
            atEnd -> p.trimEnd('\n') + q.takeLastWhile { it == '\n' }
            p.isEmpty() -> "\n"
            else -> blankEnded(p)
        }
        val replacement = before + after
        return if (down) {
            val movedTo = start + before.length
            Edit(start, lowerEnd, replacement, start, p.length, movedTo, movedTo + firstContent(p))
        } else {
            val movedTo = start + lead.length
            Edit(start, lowerEnd, replacement, qStart, q.length, movedTo, movedTo + firstContent(q))
        }
    }

    private fun CharSequence.slice(from: Int, to: Int): String = subSequence(from, to).toString()

    /** The line that ends just before [offset] (an offset at the start of a line), with its line break. */
    private fun lineBefore(text: CharSequence, offset: Int): String {
        var lineStart = offset - 1
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        return text.slice(lineStart, offset)
    }

    /** Offset in [piece] of the first line that is not blank, 0 when all of it is. */
    private fun firstContent(piece: String): Int {
        var lineStart = 0
        while (lineStart < piece.length) {
            var i = lineStart
            while (i < piece.length && (piece[i] == ' ' || piece[i] == '\t' || piece[i] == '\r')) i++
            if (i >= piece.length) break
            if (piece[i] != '\n') return lineStart
            lineStart = i + 1
        }
        return 0
    }

    /** [s] with a line break at the end, unless it is empty or has one. */
    private fun lineEnded(s: String): String = if (s.isEmpty() || s.endsWith('\n')) s else s + "\n"

    /** [lineEnded] plus a blank line, unless [s] is empty or already ends with one. */
    private fun blankEnded(s: String): String {
        if (s.isEmpty()) return s
        val ended = lineEnded(s)
        return if (lastLineIsBlank(ended)) ended else ended + "\n"
    }

    /** Whether the last line of [ended], which ends with a line break, holds only spaces and tabs. */
    private fun lastLineIsBlank(ended: String): Boolean {
        val lineEnd = ended.length - 1
        var lineStart = lineEnd
        while (lineStart > 0 && ended[lineStart - 1] != '\n') lineStart--
        return (lineStart until lineEnd).all { ended[it] == ' ' || ended[it] == '\t' || ended[it] == '\r' }
    }
}
