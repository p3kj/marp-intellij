package cz.p3kj.marp.slides

/**
 * Swaps two neighbouring slides of a [MarpDeck] in the text, as one replacement of a range. Pure text logic, so the
 * caller decides how to apply it (the editor actions and the statement mover both use [move] and nothing else).
 *
 * A slide travels as the text from its separator line (`---`, `***`, `___`) to the start of the next slide, so its
 * separator style, its local directives and its presenter notes move with it. The front matter is the exception: it
 * belongs to the first slide in [MarpDeck] but it stays at the top. Swapping the first two slides swaps the bodies of
 * both and the separator line of the second slide stays between them.
 *
 * A slide that is moved before a following separator needs a blank line before it, otherwise a paragraph line right
 * above `---` becomes a setext heading and the slide break is lost. That only matters for a moved last slide or a deck
 * without blank lines before its separators, a deck that has them is not changed by the blank lines. What follows the
 * last slide (the line breaks at the end of the file) stays at the end of the file, so moving the last slide up and back
 * down gives the original text.
 *
 * Decks with a `headingDivider` are not supported ([supports]): swapping there can merge slides (a slide without a
 * divider heading at its start joins the slide before it) and reorder competing `headingDivider` comments.
 */
object MarpSlideReorder {

    /**
     * Replace `[start, end)` of the text with [text]. The moved slide was the piece `[movedFrom, movedFrom + movedLength)`
     * and starts at [movedTo] after the edit.
     */
    data class Edit(
        val start: Int,
        val end: Int,
        val text: String,
        val movedFrom: Int,
        val movedLength: Int,
        val movedTo: Int,
    ) {
        /**
         * Where a caret at [caret] (an offset before the edit) is after it: inside the moved piece it keeps its place in
         * it, anywhere else (the front matter, the separator line that stays) it goes to the start of the moved piece.
         */
        fun caretAfter(caret: Int): Int =
            if (caret in movedFrom..movedFrom + movedLength) movedTo + (caret - movedFrom) else movedTo
    }

    /** `false` for decks that use `headingDivider`, see the class comment. */
    fun supports(deck: MarpDeck): Boolean = deck.headingDivider.isEmpty()

    /**
     * The edit that moves slide [index] one place towards the start ([down] is `false`) or the end of [text], which
     * [deck] must describe. `null` when the deck is not supported, [index] is not a slide, or the slide is already the
     * first (up) or the last (down).
     */
    fun move(text: CharSequence, deck: MarpDeck, index: Int, down: Boolean): Edit? {
        if (!supports(deck) || index !in deck.slides.indices) return null
        val upperIndex = if (down) index else index - 1
        if (upperIndex < 0 || upperIndex + 1 > deck.slides.lastIndex) return null
        val upper = deck.slides[upperIndex]
        val lower = deck.slides[upperIndex + 1]
        val atEnd = lower.endOffset >= text.length

        // The first slide swaps bodies and leaves its front matter and the separator line of the second slide in place.
        val first = upperIndex == 0
        val start = if (first) upper.bodyOffset else upper.startOffset
        val qStart = if (first) lower.bodyOffset else lower.startOffset
        val separator = if (first) lineEnded(text.slice(lower.startOffset, lower.bodyOffset)) else ""
        val p = text.slice(start, lower.startOffset)
        val q = text.slice(qStart, lower.endOffset)

        val before = blankEnded(q) + separator
        val after = if (atEnd) p.trimEnd('\n') + q.takeLastWhile { it == '\n' } else blankEnded(p)
        val replacement = before + after
        return if (down) {
            Edit(start, lower.endOffset, replacement, movedFrom = start, movedLength = p.length, movedTo = start + before.length)
        } else {
            Edit(start, lower.endOffset, replacement, movedFrom = qStart, movedLength = q.length, movedTo = start)
        }
    }

    private fun CharSequence.slice(from: Int, to: Int): String = subSequence(from, to).toString()

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
