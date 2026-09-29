package cz.p3kj.marp.spellchecker

import com.intellij.spellchecker.BundledDictionaryProvider

/**
 * Adds the Marp words the English dictionary does not know (`marp`, `Marpit`, `Twemoji`, ...) to the IDE spellchecker,
 * so `marp: true` in the front matter is not flagged as a typo.
 *
 * The dictionary is a plain word list, one lowercase word per line and matched case-insensitively. Words the English
 * dictionary already accepts (`paginate`, `katex`, `gaia`, ...) are deliberately not listed.
 */
class MarpBundledDictionaryProvider : BundledDictionaryProvider {

    override fun getBundledDictionaries(): Array<String> = arrayOf(DICTIONARY)

    companion object {
        /** Absolute resource path: the engine also uses this string as the dictionary name, a bare file name could collide. */
        const val DICTIONARY = "/cz/p3kj/marp/spellchecker/marp.dic"
    }
}
