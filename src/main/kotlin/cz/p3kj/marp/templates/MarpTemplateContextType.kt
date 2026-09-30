package cz.p3kj.marp.templates

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.openapi.project.DumbAware
import cz.p3kj.marp.MarpBundle
import cz.p3kj.marp.MarpDetector
import cz.p3kj.marp.images.MarpImageSyntax

/**
 * The `MARP_DECK` live template context: a Markdown file whose front matter contains `marp: true`. It is a child of the
 * Markdown context (see `baseContextId` in plugin.xml), so the Applicable in chooser of Settings | Editor | Live
 * Templates shows it under Markdown. The Marp templates therefore expand only in Marp decks and never in other
 * Markdown files.
 *
 * Detection scans at most [MarpDetector.FRONT_MATTER_SCAN_CHARS] characters, in linear time.
 *
 * The alt text of an image is not in the context: `bg` is also a keyword there (`![bg`), and the Tab that completes a
 * keyword must not turn it into a whole image.
 */
class MarpTemplateContextType : TemplateContextType(MarpBundle.message("liveTemplate.context")), DumbAware {

    override fun isInContext(templateActionContext: TemplateActionContext): Boolean {
        val file = templateActionContext.file
        val virtualFile = file.viewProvider.virtualFile
        val contents = file.viewProvider.contents
        return MarpDetector.isMarkdown(virtualFile) && MarpDetector.isMarp(contents) &&
            MarpImageSyntax.altSpot(contents, templateActionContext.startOffset) == null
    }
}
