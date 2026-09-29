package cz.p3kj.marp.themes

import com.google.gson.JsonParseException
import com.google.gson.JsonParser

/**
 * Minimal reader for the `themeSet` option of a Marp CLI configuration file: JSON through the platform's Gson, YAML
 * through a small parser for the top-level `themeSet` key only (scalar, flow list or block list).
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
        val root = try {
            JsonParser.parseString(text)
        }
        catch (e: JsonParseException) {
            throw IllegalArgumentException(e.message ?: "Malformed JSON", e)
        }
        if (!root.isJsonObject) return emptyList()
        val value = root.asJsonObject.get("themeSet") ?: return emptyList()
        val items = when {
            value.isJsonArray -> value.asJsonArray.toList()
            else -> listOf(value)
        }
        return items
            .filter { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            .map { it.asString }
            .filter { it.isNotBlank() }
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
