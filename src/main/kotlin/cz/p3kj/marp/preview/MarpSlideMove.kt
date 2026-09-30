package cz.p3kj.marp.preview

import com.google.gson.JsonObject

/**
 * A thumbnail dropped on another place in the slide overview, as the page reports it (`didMoveSlide`): slide [from] goes
 * to the place of slide [to] (0-based indices of the thumbnails, [to] is the index after the move). [line] is the content
 * start line of the dragged slide and [count] the number of thumbnails on the page, so the host can check that the page
 * showed the same deck as the editor holds now. The page is only a source of requests, the host validates every one.
 */
data class MarpSlideMove(val from: Int, val to: Int, val line: Int, val count: Int) {

    companion object {
        /**
         * The move in [json], `null` unless all four fields are whole non-negative numbers, [from] differs from [to] and
         * both are below [count].
         */
        fun parse(json: JsonObject): MarpSlideMove? {
            val from = json.wholeNumber("from") ?: return null
            val to = json.wholeNumber("to") ?: return null
            val line = json.wholeNumber("line") ?: return null
            val count = json.wholeNumber("count") ?: return null
            if (from == to || from >= count || to >= count) return null
            return MarpSlideMove(from, to, line, count)
        }

        private fun JsonObject.wholeNumber(name: String): Int? {
            val element = get(name) ?: return null
            if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) return null
            val value = element.asDouble
            if (!value.isFinite() || value != Math.floor(value) || value < 0 || value > Int.MAX_VALUE) return null
            return value.toInt()
        }
    }
}
