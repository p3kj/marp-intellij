package cz.p3kj.marp.editor

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.vfs.VirtualFile
import cz.p3kj.marp.MarpDetector
import cz.p3kj.marp.MarpLightTestCase
import cz.p3kj.marp.notifications.MarpEditorNotificationProvider
import cz.p3kj.marp.settings.MarpHtmlMode

class MarpSplitEditorProviderTest : MarpLightTestCase() {

    private val deck = "---\nmarp: true\n---\n# Slide 1\n\n---\n\n# Slide 2\n"

    private fun accepts(file: VirtualFile): Boolean = runReadActionBlocking { MarpSplitEditorProvider().accept(project, file) }

    fun testAcceptsMarkdownWithMarpFrontMatter() {
        val file = myFixture.addFileToProject("deck.md", deck).virtualFile
        assertTrue(accepts(file))
    }

    fun testAcceptsMarkdownExtension() {
        val file = myFixture.addFileToProject("deck.markdown", deck).virtualFile
        assertTrue(accepts(file))
    }

    fun testRejectsMarkdownWithoutMarp() {
        assertFalse(accepts(myFixture.addFileToProject("plain.md", "# Just Markdown\n").virtualFile))
        assertFalse(accepts(myFixture.addFileToProject("off.md", "---\nmarp: false\n---\n# Hi\n").virtualFile))
    }

    fun testRejectsNonMarkdownFile() {
        assertFalse(accepts(myFixture.addFileToProject("deck.txt", deck).virtualFile))
    }

    fun testUsesUnsavedDocumentText() {
        val file = myFixture.addFileToProject("later.md", "# Not yet\n").virtualFile
        assertFalse(accepts(file))
        val document = runReadActionBlocking { FileDocumentManager.getInstance().getDocument(file) }!!
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "---\nmarp: true\n---\n") }
        assertTrue(accepts(file))
    }

    fun testReadsFileHeadWhenNoDocumentIsLoaded() {
        val file = myFixture.tempDirFixture.createFile("bom.md")
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + deck.toByteArray(Charsets.UTF_8)
        WriteAction.runAndWait<Throwable> { file.setBinaryContent(bytes) }
        assertNull(FileDocumentManager.getInstance().getCachedDocument(file))
        assertTrue(runReadActionBlocking { MarpDetector.isMarp(file) })
        assertTrue(accepts(file))
    }

    fun testEditorTypeAndPolicy() {
        val provider = MarpSplitEditorProvider()
        assertEquals("marp-preview-editor", provider.editorTypeId)
        assertEquals(FileEditorPolicy.HIDE_OTHER_EDITORS, provider.policy)
    }

    fun testNotificationOnlyForMarpDecks() {
        val provider = MarpEditorNotificationProvider()
        val marp = myFixture.addFileToProject("n-deck.md", deck).virtualFile
        val plain = myFixture.addFileToProject("n-plain.md", "# Plain\n").virtualFile
        assertNotNull(runReadActionBlocking { provider.collectNotificationData(project, marp) })
        assertNull(runReadActionBlocking { provider.collectNotificationData(project, plain) })
    }

    fun testUntrustedProjectsRenderNoHtml() {
        for (mode in MarpHtmlMode.entries) {
            assertEquals(MarpHtmlMode.OFF, MarpPreviewFileEditor.effectiveHtmlMode(mode, trusted = false))
            assertEquals(mode, MarpPreviewFileEditor.effectiveHtmlMode(mode, trusted = true))
        }
    }
}
