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
- Slide overview in the preview toolbar: shows the slides as a grid of thumbnails, a click on one moves the caret to that slide. Per editor, off by default, scroll sync pauses while it is on
- A hint in the preview while the deck has no content yet; Escape closes the error banner
- Custom themes from files, folders and URLs, configured in Settings | Tools | Marp. Entries can be typed as a path and edited in place
- Theme folders skip `node_modules` and hidden folders and load at most 200 CSS files, 8 levels deep; theme URLs must serve CSS or plain text of at most 5 MB
- Automatic pickup of `themeSet` from `.marprc.yml`, `.marprc.yaml`, `.marprc.json` or `.marprc` in the project root
- Theme CSS edits show up in the preview while typing, before the file is saved
- Warning in the preview when the `theme:` directive names a theme that is not available
- Structure tool window and File Structure popup list the slides of a Marp deck ("Slide 3: Agenda") with their headings nested; selecting one moves the caret there and the preview follows. Respects `headingDivider`; other Markdown files keep the heading outline
- File | New | Marp Presentation creates a starter deck: front matter with `marp: true`, `theme` and `paginate`, a lead title slide, example slides and presenter notes
- Live templates for Marp decks: `slide` (new slide), `lead` (`<!-- _class: lead -->`), `bg` (background image) and `notes` (presenter notes). They expand only in Markdown files with `marp: true` and are listed under Settings | Editor | Live Templates | Marp
- Directive comments in Marp decks are highlighted: directive keys and values get their own colors and presenter-note comments are shown differently (Settings | Editor | Color Scheme | Marp)
- Completion of directive names in comments (global, local and the `_` form for one slide) and of values: `paginate`, `math`, `size`, `headingDivider`, `class`, background options and built-in and custom theme names. Each name shows where it applies ("whole deck", "this and following slides", "this slide only"; "all slides" in the front matter), `class` and `_class` sit next to each other, and hover docs name the form under the caret and explain the difference
- Quick documentation and hover docs for directives
- Marp directive inspection: unknown directives (with a "did you mean"), global directives written with `_` such as `_theme`, invalid `paginate`, `math` and `headingDivider` values
- Completion, hover docs and the Marp directive inspection in the front matter of Marp decks: directive names and values, theme names, `marp`, and values Marp ignores or misreads
- Inspection for a `theme:` directive that names an unknown theme, with quick fixes to add a CSS file or folder in Settings | Tools | Marp, create a `.marprc.yml` with `themeSet` or open the existing one, and open the settings. It stays quiet while themes load, in untrusted projects and while a theme source has an error
- Ctrl+click (Cmd+click on macOS) or Go to Declaration on a theme name in `theme:`, in the front matter or a directive comment, opens the theme's CSS file at its `@theme` comment (themes from files)
- Completion and hover docs for Marp image keywords in the alt text of images: `bg`, `left`/`right` with a size, `fit`/`contain`/`cover`/`auto`, `vertical`, `w:`/`h:` and the CSS filters. The popup opens while the alt text holds only keywords, never for a description such as `![A photo of a cat]`; Ctrl+Space always works. The `bg` live template no longer expands inside the alt text of an image
- Export a deck to a standalone HTML file or to a PDF with one page per slide: File | Export | Marp Deck to HTML / PDF, or the Export Deck button in the preview toolbar. It exports what the preview shows (same themes, math and HTML setting) through the preview page, so it needs no Node.js and the preview has to be loaded. HTML keeps image paths as written. PPTX and images stay with Marp CLI
- Export a deck to PowerPoint (PPTX) or to PNG or JPEG images through Marp CLI: File | Export | Marp Deck to PowerPoint / PNG Images / JPEG Images (Marp CLI), and the Export Deck button in the preview toolbar. Marp CLI, Node.js and a browser are installed by you; the new Marp CLI field in Settings | Tools | Marp (IDE-wide, empty means `marp` on the PATH, with a Test button) says where it is. The export passes on the themes, HTML and math settings, only runs in trusted projects, ignores the project's own Marp config files, saves the open files first, can be cancelled, and shows the last lines of the CLI output when it fails
- Present Deck (play button in the preview toolbar): shows the deck in the system browser one slide at a time, starting at the slide under the caret. Arrow keys, Page Up / Page Down, Space, Enter, Backspace, Home and End move between slides, F toggles full screen. It is the HTML export with a small script, so it needs no Node.js and follows the same themes, math and HTML setting, but it is a snapshot: run Present again to see edits
- Present Deck shows Marp CLI's own presentation when Marp CLI is found, the project is trusted and the deck is a local file: on-screen controls, presenter view with notes and timer (P), overview (O), progress bar and transitions. It uses the same lookup, themes and settings as the export and saves the open files first, so it cannot show unsaved text. The new setting "Present decks with Marp CLI" in Settings | Tools | Marp (IDE-wide, on by default) turns it off; without Marp CLI, in an untrusted project or for a deck that is not a local file the built-in page is shown as before. A Marp CLI that is found but cannot be started is reported instead of falling back
- Marp CLI export finds a project install by itself: without a configured path, `node_modules/.bin/marp` (`marp.cmd` on Windows) in the deck's folder or a parent folder up to the project root is used before `marp` on the PATH, in trusted projects only. The Test button and the "not found" hint say so
- Slide navigation in Marp decks: "Slide 3 / 12" in the status bar (click to go to a slide), Next / Previous Slide (Ctrl+Alt+PageDown / Ctrl+Alt+PageUp, Cmd+Opt on macOS) and Go to Slide in the Navigate menu, and slide numbers as inlay hints at each slide start (Settings | Editor | Inlay Hints)
- Slides of a Marp deck fold in the editor, one region per slide with "Slide 3: Agenda" as the folded text. Respects `headingDivider`; slides are expanded by default
- Reorder slides: Code | Move Slide Up / Move Slide Down (Find Action: "Marp: Move Slide Up") move the slide under the caret before the previous or after the next slide, and Move Statement Up / Down (Ctrl+Shift+Up / Down, Cmd+Shift+Up / Down on macOS) moves the whole slide when the caret is on its `---` line. The front matter stays first, a slide's own directives and notes move with it, the caret follows the slide and a move is one undo step. Not available in decks with `headingDivider`. The actions have no default shortcut, assign one in Settings | Keymap
- Slides are counted like Marp when a `# heading`, another `---`, a one-line HTML comment (a presenter note) or a `<style>` line sits directly above a `---` separator, and a paragraph of several lines directly above `---` is a heading, not a separator (status bar, navigation, folding, Structure view and slide moves)
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
