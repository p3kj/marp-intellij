package cz.p3kj.marp.editor

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope

/** Project-lifetime coroutine scope for Marp editors; each editor works in a child scope cancelled on dispose. */
@Service(Service.Level.PROJECT)
internal class MarpProjectScope(val scope: CoroutineScope) {
    companion object {
        fun getInstance(project: Project): MarpProjectScope = project.service()
    }
}
