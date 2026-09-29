package cz.p3kj.marp.themes

/**
 * Minimal reader for the `themeSet` option of a Marp CLI configuration file.
 *
 * Only `themeSet` is needed, so this avoids depending on a bundled YAML / JSON library: the platform's SnakeYAML and
 * Gson live in separate library modules whose availability to plugins differs between IDE products.
 */
object MarprcParser {
    private val keyRegex = Regex("""^(?:themeSet|"themeSet"|'themeSet')\s*:(.*)$""")

    /** Returns the `themeSet` entries (empty when absent). Throws [IllegalArgumentException] on malformed JSON. */
    fun parseThemeSet(fileName: String, text: String): List<String> {
        val body = text.removePrefix("\uFEFF")
        val isJson = fileName.endsWith(".json", ignoreCase = true) || body.trimStart().startsWith("{")
        return if (isJson) parseJson(body) else parseYaml(body)
    }

    // ---- JSON ----

    private fun parseJson(text: String): List<String> {
        val root = JsonReader(text).readDocument()
        val value = (root as? Map<*, *>)?.get("themeSet") ?: return emptyList()
        return when (value) {
            is String -> listOf(value)
            is List<*> -> value.filterIsInstance<String>()
            else -> emptyList()
        }.filter { it.isNotBlank() }
    }

    private class JsonReader(private val s: String) {
        private var i = 0

        fun readDocument(): Any? {
            val v = readValue()
            skipWs()
            require(i == s.length) { "Unexpected trailing content at $i" }
            return v
        }

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun readValue(): Any? {
            skipWs()
            require(i < s.length) { "Unexpected end of JSON" }
            return when (val c = s[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                else -> {
                    val start = i
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] in "+-.")) i++
                    require(i > start) { "Unexpected character '$c' at $i" }
                    // number / true / false / null are irrelevant here
                    null
                }
            }
        }

        private fun readObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            i++
            skipWs()
            if (peek() == '}') { i++; return map }
            while (true) {
                skipWs()
                require(peek() == '"') { "Expected a string key at $i" }
                val key = readString()
                skipWs()
                require(peek() == ':') { "Expected ':' at $i" }
                i++
                map[key] = readValue()
                skipWs()
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return map }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at $i")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val list = ArrayList<Any?>()
            i++
            skipWs()
            if (peek() == ']') { i++; return list }
            while (true) {
                list.add(readValue())
                skipWs()
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; return list }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at $i")
                }
            }
        }

        private fun readString(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "Unterminated string" }
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(i < s.length) { "Unterminated escape" }
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                require(i + 4 <= s.length) { "Bad unicode escape" }
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun peek(): Char = if (i < s.length) s[i] else '\u0000'
    }

    // ---- YAML (top-level `themeSet` only) ----

    private fun parseYaml(text: String): List<String> {
        val lines = text.lines()
        var idx = 0
        while (idx < lines.size) {
            val match = keyRegex.find(lines[idx])
            if (match == null) { idx++; continue }
            val rest = stripComment(match.groupValues[1]).trim()
            return when {
                rest.startsWith("[") -> parseFlowList(collectFlowList(rest, lines, idx))
                rest.isNotEmpty() -> listOf(unquote(rest)).filter { it.isNotBlank() }
                else -> parseBlockList(lines, idx + 1)
            }
        }
        return emptyList()
    }

    private fun collectFlowList(first: String, lines: List<String>, keyLine: Int): String {
        val sb = StringBuilder(first)
        var n = keyLine + 1
        while (!closesFlow(sb) && n < lines.size) {
            sb.append(' ').append(stripComment(lines[n]).trim())
            n++
        }
        return sb.toString()
    }

    private fun closesFlow(sb: CharSequence): Boolean {
        var quote = '\u0000'
        for (c in sb) {
            if (quote != '\u0000') { if (c == quote) quote = '\u0000' } else if (c == '"' || c == '\'') quote = c
            else if (c == ']') return true
        }
        return false
    }

    private fun parseFlowList(text: String): List<String> {
        val inner = text.substringAfter('[').let { t ->
            val end = lastUnquoted(t, ']')
            if (end >= 0) t.substring(0, end) else t
        }
        val items = ArrayList<String>()
        val cur = StringBuilder()
        var quote = '\u0000'
        for (c in inner) {
            when {
                quote != '\u0000' -> { cur.append(c); if (c == quote) quote = '\u0000' }
                c == '"' || c == '\'' -> { cur.append(c); quote = c }
                c == ',' -> { items.add(cur.toString()); cur.clear() }
                else -> cur.append(c)
            }
        }
        items.add(cur.toString())
        return items.map { unquote(it.trim()) }.filter { it.isNotBlank() }
    }

    private fun lastUnquoted(t: String, target: Char): Int {
        var quote = '\u0000'
        var found = -1
        for ((k, c) in t.withIndex()) {
            if (quote != '\u0000') { if (c == quote) quote = '\u0000' } else if (c == '"' || c == '\'') quote = c
            else if (c == target) found = k
        }
        return found
    }

    private fun parseBlockList(lines: List<String>, from: Int): List<String> {
        val items = ArrayList<String>()
        for (n in from until lines.size) {
            val raw = stripComment(lines[n])
            if (raw.isBlank()) continue
            val trimmed = raw.trim()
            if (trimmed == "-" || trimmed.startsWith("- ")) {
                items.add(unquote(trimmed.removePrefix("-").trim()))
            } else {
                break
            }
        }
        return items.filter { it.isNotBlank() }
    }

    /** Drops a trailing `# comment` that is not inside quotes. */
    private fun stripComment(line: String): String {
        var quote = '\u0000'
        for ((k, c) in line.withIndex()) {
            if (quote != '\u0000') {
                if (c == quote) quote = '\u0000'
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '#' && (k == 0 || line[k - 1].isWhitespace())) {
                return line.substring(0, k)
            }
        }
        return line
    }

    private fun unquote(v: String): String {
        if (v.length >= 2 && v.first() == '\'' && v.last() == '\'') return v.substring(1, v.length - 1).replace("''", "'")
        if (v.length >= 2 && v.first() == '"' && v.last() == '"') {
            return v.substring(1, v.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        }
        return v
    }
}
