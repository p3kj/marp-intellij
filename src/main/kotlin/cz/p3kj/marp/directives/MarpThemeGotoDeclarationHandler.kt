package cz.p3kj.marp.directives

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import cz.p3kj.marp.themes.MarpThemeNames
import cz.p3kj.marp.themes.MarpThemeService

/**
 * Go to Declaration (Ctrl+B, Ctrl+click) on the value of `theme:`, in the front matter or in a directive comment of a
 * Marp deck: it opens the CSS file of a custom theme at its `@theme` comment. Built-in themes and themes from URLs have
 * no file, and nothing opens for them.
 *
 * It is a handler rather than a reference because the platform asks handlers first, before it looks at references, and
 * asks them about the injected file (the YAML of the front matter) and then about the Markdown file. One handler that
 * maps the position to the Markdown file ([MarpDirectiveComments.entriesAt]) covers the front matter with and without
 * the YAML plugin and the comments, and needs no reference on the injected leaves. Ctrl+hover uses the same targets,
 * so the underline follows the leaf under the mouse: exactly the name in the front matter, the whole comment text in a
 * directive comment.
 *
 * Only the cached theme set is used ([MarpThemeService.cachedThemeFile]): the handler never loads anything on the
 * calling thread. While the themes are not loaded it finds nothing and the load starts in the background. A name that
 * several files declare leads to the last one, like in the preview. It runs on every Ctrl+hover in every file, so a
 * file that is not a Marp deck is turned away early by the cached [MarpDirectiveComments.isMarpDeck].
 */
class MarpThemeGotoDeclarationHandler : GotoDeclarationHandler, DumbAware {

    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor): Array<PsiElement>? {
        val file = sourceElement?.containingFile ?: return null
        val (entries, at) = MarpDirectiveComments.entriesAt(file, offset) ?: return null
        val entry = entries.firstOrNull { isThemeValueAt(it, at) } ?: return null
        val project = file.project
        val path = MarpThemeService.getInstance(project).cachedThemeFile(entry.value) ?: return null
        // No refresh: a file that the virtual file system does not know is not worth a blocking call.
        val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(path) ?: return null
        val css = PsiManager.getInstance(project).findFile(virtualFile) ?: return null
        val nameOffset = MarpThemeNames.declarationOf(css.viewProvider.contents)?.nameOffset
        return arrayOf(nameOffset?.let(css::findElementAt) ?: css)
    }

    /** The value of a `theme` entry contains [offset], the end of the value included (the caret is often right behind it). */
    private fun isThemeValueAt(entry: MarpDirectiveEntry, offset: Int): Boolean {
        val key = entry.resolved as? MarpDirectiveKey.Known ?: return false
        if (!key.directive.themeValue || entry.value.isBlank()) return false
        val range = entry.valueRange ?: return false
        return offset >= range.startOffset && offset <= range.endOffset
    }
}
