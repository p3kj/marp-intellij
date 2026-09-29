package cz.p3kj.marp.spellchecker

import com.intellij.spellchecker.SpellCheckerManager
import cz.p3kj.marp.MarpLightTestCase

class MarpSpellcheckerDictionaryTest : MarpLightTestCase() {

    private val manager get() = SpellCheckerManager.getInstance(project)

    private fun dictionaryWords(): List<String> {
        val stream = MarpBundledDictionaryProvider::class.java.getResourceAsStream(MarpBundledDictionaryProvider.DICTIONARY)
        assertNotNull("Dictionary resource ${MarpBundledDictionaryProvider.DICTIONARY} is missing", stream)
        return stream!!.use { it.readBytes().toString(Charsets.UTF_8) }.lines().dropLastWhile { it.isEmpty() }
    }

    fun testDictionaryIsRegistered() {
        // Only true when the optional marp-spellchecker.xml descriptor loaded and its extension is picked up.
        assertTrue(MarpBundledDictionaryProvider.DICTIONARY in SpellCheckerManager.bundledDictionaries)
    }

    fun testDictionaryFormat() {
        val lines = dictionaryWords()
        assertFalse("Dictionary is empty", lines.isEmpty())
        assertEquals("Dictionary must be sorted and free of duplicates", lines.sorted().distinct(), lines)
        for (line in lines) {
            assertTrue("Blank line in dictionary", line.isNotBlank())
            assertEquals("Words must be trimmed: '$line'", line.trim(), line)
            assertEquals("Words must be lowercase: '$line'", line.lowercase(), line)
        }
    }

    fun testEngineFlagsUnknownWords() {
        // Control for the tests below: proves the spellchecker engine is running and rejects nonsense.
        assertTrue(manager.hasProblem("qwzxv"))
    }

    fun testMarpWordsAreKnown() {
        val words = dictionaryWords() + listOf("Marp", "MARP", "Marpit", "Twemoji", "Marp's")
        assertEquals(emptyList<String>(), flagged(words))
    }

    fun testMarpVocabularyKnownWithoutEntries() {
        // The English dictionary already accepts these, so they are deliberately not listed in marp.dic.
        val words = listOf("paginate", "uncover", "gaia", "katex", "mathjax", "bespoke", "coverflow", "grayscale", "sepia")
        assertEquals(emptyList<String>(), flagged(words))
    }

    /** The flagged [words]. Fails first when no engine runs, since `hasProblem` then accepts every word. */
    private fun flagged(words: List<String>): List<String> {
        assertTrue("Spellchecker engine is not running", manager.hasProblem("qwzxv"))
        return words.filter { manager.hasProblem(it) }
    }
}
