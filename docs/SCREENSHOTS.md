# Screenshots

The deck in these pictures is [showcase/marp-preview.md](showcase/marp-preview.md), a presentation about the plugin written with the plugin. Open `docs/showcase` as a project to try it. The [user guide](USER_GUIDE.md) explains every feature shown here.

## Live editing

The preview adds a new slide while you type, and completing `_class: lead` restyles it.

![Typing a new slide: the preview adds it while you type, and completing `_class: lead` restyles it](media/live-editing.gif)

## Split view with scroll sync

Markdown on the left, slides on the right, the slide under the caret outlined.

![Split view: Markdown on the left, slides on the right, the slide under the caret outlined](media/split.png)

## Slide overview

All slides as a grid of thumbnails. Click one to jump to it, drag one to reorder the deck.

![Slide overview: the deck as a grid of thumbnails](media/overview.png)

![Dragging a thumbnail in the slide overview moves the slide in the Markdown source](media/drag-reorder.gif)

## Directive completion

Every directive says where it applies: the whole deck, this and the following slides, or this slide only.

![Directive completion shows where each directive applies](media/completion.png)

## Hover docs

![Hover docs for a directive, next to the live preview](media/hover-docs.png)

## Image keywords

Completion of the keywords Marp reads in the alt text of an image.

![Completion of image keywords in alt text](media/image-keywords.png)

## Unknown theme quick fixes

![Quick fixes for an unknown theme name](media/quick-fix.png)

## Structure view

![Slides in the Structure tool window](media/structure.png)

## Export

HTML and PDF are built in, PowerPoint and images go through Marp CLI.

![Export menu of the preview toolbar](media/export.png)

## Settings

![Settings | Tools | Marp: themes, themeSet from .marprc.yml, HTML in slides, math, presenter notes and scroll sync](media/settings.png)
