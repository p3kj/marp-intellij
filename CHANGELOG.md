<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Marp Preview Changelog

## [Unreleased]

### Fixed

- `class` completion offers the classes of the deck's theme next to `lead` and `invert`: the `section.<name>` classes of a custom theme's CSS, `gaia` for the gaia theme, and the classes of all custom themes when the deck's theme cannot be found. It also completes a second class after a space and does not offer a class twice
- A sample deck inside a fenced `markdown` code block is left alone: no slide folds, directive colors or Marp inspections in it. The fold on its front matter comes from the bundled Markdown plugin, not from Marp
- Ready for 2026.3: the "Marp deck detected" banner reopens the file without an editor API that is deprecated there, so the Plugin Verifier no longer warns about it

## [0.9.0] - 2026-10-15

### Added

- Live preview next to the Markdown editor, rendered by marp-core, refreshed while you type, no Node.js needed
- Two-way scroll sync, highlight of the slide under the caret, double-click a slide to jump to its source
- Slide overview with thumbnails: click one to jump to it, drag one to reorder the deck
- Completion, hover docs and inspections for directives in comments and in the front matter, and for image keywords in alt text
- Quick fixes for unknown theme names, Ctrl+click a theme name to open its CSS
- Custom themes from files, folders and URLs, `themeSet` from `.marprc.yml`, live theme edits
- Slides in the Structure view, "Slide 3 / 12" in the status bar, Next / Previous Slide, Go to Slide, folding, Move Slide Up / Down
- Export to HTML and PDF built in, PowerPoint, PNG and JPEG through Marp CLI
- Present Deck in the browser, with Marp's presenter view, notes and timer when Marp CLI is installed
- File | New | Marp Presentation and live templates `slide`, `lead`, `bg`, `notes`
- MathJax or KaTeX, Twemoji, local images and backgrounds, inline HTML modes
- Locked-down preview page and a restricted mode for untrusted projects

[Unreleased]: https://github.com/p3kj/marp-intellij/compare/0.9.0...HEAD
[0.9.0]: https://github.com/p3kj/marp-intellij/commits/0.9.0
