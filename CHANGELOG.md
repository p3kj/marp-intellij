<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Marp Preview Changelog

## [Unreleased]

### Added

- Live Marp slide preview in JCEF next to the Markdown editor, refreshed while typing (no save needed)
- Editor / Split / Preview layouts for Markdown files with `marp: true`; other Markdown files keep the regular preview
- "Marp deck detected" banner when `marp: true` is added to a file that is already open
- Two-way scroll sync between the editor and the preview (an IDE-wide setting), highlight of the slide under the caret, double-click in the preview jumps to the source line
- Presenter notes: HTML comments that are not directives can be shown under each slide (Settings | Tools | Marp)
- Preview toolbar buttons to turn scroll sync and presenter notes on or off and to open Settings | Tools | Marp. The toggles change the same IDE-wide settings and are also available in Find Action
- A hint in the preview while the deck has no content yet; Escape closes the error banner
- Custom themes from files, folders and URLs, configured in Settings | Tools | Marp. Entries can be typed as a path and edited in place
- Theme folders skip `node_modules` and hidden folders and load at most 200 CSS files, 8 levels deep; theme URLs must serve CSS or plain text of at most 5 MB
- Automatic pickup of `themeSet` from `.marprc.yml`, `.marprc.yaml`, `.marprc.json` or `.marprc` in the project root
- Theme CSS edits show up in the preview while typing, before the file is saved
- Warning in the preview when the `theme:` directive names a theme that is not available
- File | New | Marp Presentation creates a starter deck: front matter with `marp: true`, `theme` and `paginate`, a lead title slide, example slides and presenter notes
- Live templates for Marp decks: `slide` (new slide), `lead` (`<!-- _class: lead -->`), `bg` (background image) and `notes` (presenter notes). They expand only in Markdown files with `marp: true` and are listed under Settings | Editor | Live Templates | Marp
- Local image support, including `![bg](...)` backgrounds
- Math rendering with MathJax (works offline) or KaTeX
- Setting for inline HTML in slides: off, default or all
- Spellchecker dictionary with Marp words (marp, Marpit, Twemoji, marprc), so `marp: true` in the front matter is no longer flagged as a typo
- Preview background follows the IDE light or dark theme
- Locked-down preview page: Content Security Policy, no navigation away from the preview. Links open in the IDE (local files inside the project) or in the system browser
- Untrusted projects render without HTML and without custom themes until trusted
- Third-party license notices for the bundled webview packages (THIRD-PARTY-NOTICES.txt inside the plugin)
- Compatible with all IntelliJ-based IDEs on the 2026.2 platform and newer

### Security

- Local files for the preview are checked against the project folders before any file access, so a deck cannot make the IDE open network (UNC/SMB) paths on Windows
- Every request to the preview's own host is answered by the plugin and never reaches the network
- `themeSet` entries of a `.marprc` must stay inside the project directory
- With "HTML in slides: All", frames, `object`, `embed`, `portal`, `base` and HTML import links are removed from deck HTML, and `autofocus` cannot move focus into the preview
- Dropping a file or link on the preview does not navigate

[Unreleased]: https://github.com/p3kj/marp-intellij/commits/main
