package cz.p3kj.marp.notifications

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Key
import com.intellij.ui.EditorNotifications
import cz.p3kj.marp.MarpDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Application-wide document listener (`editorFactoryDocumentListener`): when a Markdown document is edited near its
 * start, re-checks for Marp front matter after a short pause and refreshes the editor banners only if the answer changed.
 * Runs on every keystroke, so it does nothing but a few cheap checks there.
 */
class MarpNotificationDocumentListener : DocumentListener {
    override fun documentChanged(event: DocumentEvent) {
        // Detection only looks at the start of the document; edits further down cannot turn a deck on or off.
        if (event.offset > MarpDetector.FRONT_MATTER_SCAN_CHARS) return
        val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
        if (!MarpDetector.isMarkdown(file)) return
        service<MarpNotificationUpdater>().schedule(event.document)
    }
}

/** Debounces Marp detection per document and updates [EditorNotifications] when the result flips. */
@Service(Service.Level.APP)
internal class MarpNotificationUpdater(private val scope: CoroutineScope) {

    private val pending = ConcurrentHashMap<Document, Job>()

    fun schedule(document: Document) {
        val job = scope.launch {
            delay(DEBOUNCE_MS)
            check(document)
        }
        pending.put(document, job)?.cancel()
        job.invokeOnCompletion { pending.remove(document, job) }
    }

    private suspend fun check(document: Document) {
        val (file, isMarp) = readAction {
            val file = FileDocumentManager.getInstance().getFile(document) ?: return@readAction null
            file to MarpDetector.isMarp(document.immutableCharSequence)
        } ?: return
        val previous = document.getUserData(LAST_DETECTION)
        document.putUserData(LAST_DETECTION, isMarp)
        if (previous == isMarp) return
        for (project in ProjectManager.getInstance().openProjects) {
            if (!project.isDisposed) EditorNotifications.getInstance(project).updateNotifications(file)
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 300L
        val LAST_DETECTION: Key<Boolean> = Key.create("cz.p3kj.marp.lastDetection")
    }
}
