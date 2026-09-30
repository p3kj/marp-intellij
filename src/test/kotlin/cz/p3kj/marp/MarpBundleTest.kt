package cz.p3kj.marp

import com.intellij.BundleBase
import java.util.ResourceBundle

class MarpBundleTest : MarpLightTestCase() {

    fun testNoMessageTurnsIntoAMnemonic() {
        // BundleBase replaces the first plain & of a message with a mnemonic marker, so "&lt;" would show as garbage.
        val keys = ResourceBundle.getBundle("messages.MarpBundle").keySet()
        assertFalse(keys.isEmpty())
        val broken = keys.filter { BundleBase.MNEMONIC in MarpBundle.message(it) }.sorted()
        assertEquals("Escape & as \\\\& in MarpBundle.properties", emptyList<String>(), broken)
    }

    fun testEscapedEntitiesSurvive() {
        assertTrue(MarpBundle.message("settings.presenterNotes.comment").contains("<code>&lt;!-- Say hello --&gt;</code>"))
        assertTrue(MarpBundle.message("directive.doc.style").contains("<code>&lt;style&gt;</code>"))
    }
}
