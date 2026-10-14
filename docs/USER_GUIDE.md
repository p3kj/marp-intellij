# Marp Preview user guide

The [README](../README.md) lists the features and gets you started, and the [screenshots](SCREENSHOTS.md) show them. This guide explains how they behave, what they need and where their limits are.

- [Decks and the preview](#decks-and-the-preview)
- [Slide overview](#slide-overview)
- [Navigating and reordering](#navigating-and-reordering)
- [Editing help](#editing-help)
- [Export](#export)
- [Marp CLI](#marp-cli)
- [Present](#present)
- [Themes](#themes)
- [Settings](#settings)
- [Trust and security](#trust-and-security)
- [Coming from Marp for VS Code](#coming-from-marp-for-vs-code)
- [Troubleshooting](#troubleshooting)
- [Limitations](#limitations)

## Decks and the preview

A Markdown file is a Marp deck when its front matter contains `marp: true`. The front matter must start at the first character of the file and end within the first 64 K characters. Other Markdown files keep the regular Markdown editor and preview.

A deck opens in a split view, and the Editor / Split / Preview buttons in the editor toolbar switch the layout. If you add `marp: true` to a file that is already open, click the link in the "Marp deck detected" banner, or reopen the file.

- **Live**: the preview re-renders at most every 150 ms while you type, nothing needs to be saved. Edits to theme CSS files show up the same way.
- **Scroll sync** works in both directions, and the slide under the caret is outlined. Double-click a slide to put the caret on its source line.
- **Toolbar**: move the mouse over the editor or the preview and a small toolbar appears in the top-right corner. It toggles scroll sync, presenter notes and the [slide overview](#slide-overview), and has [Present Deck](#present), [Export Deck](#export) and a button for Settings | Tools | Marp. The two toggles change the IDE-wide [settings](#settings) of the same name.
- **Presenter notes**: HTML comments that are not directives can be shown under each slide.
- **Links**: files inside the project open in the IDE, `http(s)` and `mailto` links open in the system browser, everything else is ignored.
- **Errors**, such as a theme that cannot be loaded or a `theme:` that names an unknown theme, show as a banner in the preview. Escape or the close button dismisses it.
- The area around the slides follows the IDE light or dark theme. The slides keep the colors of their Marp theme.

## Slide overview

The Slide Overview button in the toolbar replaces the preview with a grid of thumbnails. It applies to that one editor, starts off and is not remembered when the editor is closed.

- **Click** a thumbnail to put the caret on that slide. In the Preview layout the editor opens too.
- **Drag** a thumbnail to reorder the deck. A bar shows where it will land, and the drop moves the slide the same way [Move Slide](#reordering-slides) does. Escape cancels. The grid does not scroll by itself while you drag, use the mouse wheel.
- Scroll sync pauses while the overview is on. Links do nothing and presenter notes are hidden in the grid.

## Navigating and reordering

Slides are counted the way the preview counts them, including slides started by `headingDivider`.

### Moving around

- The status bar shows "Slide 3 / 12". Click it to open Go to Slide.
- Navigate | Next Slide, Previous Slide and Go to Slide... move the caret to the start of a slide, and the preview follows. They work while the editor has the focus, not the preview.
- The Structure tool window and the File Structure popup show one node per slide, named after its first heading, with the other headings nested.
- Each slide folds in the editor, and the folded text reads "Slide 3: Agenda".
- A "Slide 3" label ends the line where each slide starts. Turn it off in Settings | Editor | Inlay Hints | Other | Markdown.

### Reordering slides

- Code | Move Slide Up / Down moves the slide under the caret past its neighbor, from anywhere in the slide.
- With the caret on a slide's separator line (`---`, `***` or `___`), Move Statement Up / Down moves the whole slide, the way it moves a method from its header line. Elsewhere it moves lines as usual, and Move Line Up / Down always moves only the line.
- To move a slide further in one go, drag its thumbnail in the [slide overview](#slide-overview).

A slide moves with its separator, its own directives (`<!-- _class: lead -->`) and its notes, in one undo step. The front matter stays at the top, so moving the first slide swaps its content with the second. A directive without the underscore (`<!-- class: lead -->`) applies from where it stands to the end of the deck, so moving its slide changes which slides it affects.

Decks that use `headingDivider` cannot be reordered yet, because a swap could merge slides. The actions show a hint and the thumbnails cannot be dragged.

### Shortcuts

| Action | Default shortcut (macOS) |
| --- | --- |
| Next / Previous Slide | Ctrl+Alt+PageDown / PageUp (Cmd+Opt+PageDown / PageUp) |
| Move Statement Up / Down on a `---` line | Ctrl+Shift+Up / Down (Cmd+Shift+Up / Down) |
| File Structure popup | Ctrl+F12 (Cmd+F12) |
| Completion | Ctrl+Space |
| Quick documentation | Ctrl+Q (F1) |
| Open the CSS of a theme name | Ctrl+B or Ctrl+click (Cmd+B or Cmd+click) |
| Quick fixes | Alt+Enter (Opt+Enter) |
| Go to Slide, Move Slide Up / Down, Present Deck | none |

Assign or change them in Settings | Keymap (search "Marp"). The NetBeans and Visual Studio keymaps use Ctrl+Alt+PageUp / PageDown for something else, so there Next and Previous Slide have no default.

## Editing help

All of this works in decks only, so the file has to contain `marp: true` already. A sample deck inside a fenced `markdown` code block does not count as a deck.

### Directives

Directives work in HTML comments (`<!-- _class: lead -->`) and as top-level keys of the front matter.

- **Highlighting**: directive names and values get their own colors, and comments that Marp shows as presenter notes get another (Settings | Editor | Color Scheme | Marp).
- **Completion** offers directive names, the `_` form that applies to one slide, and values such as `paginate: hold`, `size: 4:3`, `class: lead` and theme names, including your custom themes. For `class` it also offers the classes the deck's theme styles: the `section.name` classes of a custom theme, `gaia` for the gaia theme, and the classes of all custom themes while the deck's theme is not found. Separate several classes with spaces, each one is completed. Each name says where it applies: "whole deck", "this and following slides" or "this slide only". In the front matter it also offers `marp` and leaves out keys that are already there, because a repeated key makes Marp ignore the whole front matter.
- **Documentation**: quick documentation and hovering explain the directive under the caret.
- **Inspection**: warns about unknown directives ("did you mean `_class`?"), global directives written with an underscore such as `_theme` (Marp ignores them), and values Marp ignores or misreads for `paginate`, `math` and `headingDivider`. Front matter keys that are not directives, such as `title`, are left alone unless one is a near miss (`pagiante`). Comments that are only notes, like `<!-- Note: say hello -->`, are left alone too.
- Flow style (`<!-- { class: lead } -->`) is read as a note. Use one `key: value` per line.

### Theme names

A `theme:` that names an unknown theme (not `default`, `gaia`, `uncover` or one of your [custom themes](#themes)) is marked. Theme names are case-sensitive. Alt+Enter offers to add a theme CSS file or folder to the settings, to create a `.marprc.yml` with a `themeSet` (or open yours) and to open the settings. The mark stays off while themes are loading, in an untrusted project and while a theme source has an error.

Ctrl+click a theme name, or press Ctrl+B, to open the CSS file that declares it. Built-in themes and themes from URLs have no file.

### Image keywords

In the alt text of an image (`![bg left:40% blur](photo.png)`), completion offers the keywords Marp reads there, and quick documentation explains them together with Marp's default value.

- With `bg` in the alt text you also get `left`, `right`, `fit`, `contain`, `cover`, `auto`, `vertical` and `horizontal`. Without it you get `bg`, the sizes (`w:`, `h:`, `width:`, `height:`) and the filters (`blur`, `sepia`, ...).
- Values are not completed: `w:` stops at the colon, and `blur` is inserted bare, so Marp uses its default (`blur:10px`).
- The popup opens by itself only while every other word of the alt text is a keyword, so `![bg co` opens it and `![A photo of a cat]` does not. Ctrl+Space always works.
- Inside the alt text the `bg` live template does not expand on Tab.

### New decks and templates

- File | New | Marp Presentation creates a starter deck.
- Live templates `slide`, `lead`, `bg` and `notes` expand in decks only (Settings | Editor | Live Templates | Marp).
- The spellchecker knows Marp words, so `marp: true` is not a typo.

## Export

Use File | Export or the Export Deck button in the preview toolbar. The save dialog starts in the deck's folder, and a notification opens the result when it is ready.

### HTML and PDF

Built in, no Node.js needed. They export what the preview shows: the same themes, math library and HTML setting, including unsaved changes. The export goes through the preview page, so the preview has to be loaded: open the Split or Preview layout and wait for the slides. Presenter notes are not exported.

- **HTML** is one standalone file with the same markup Marp CLI writes. Images keep the paths you wrote, so keep the file next to the deck or copy the images with it. Twemoji, KaTeX fonts and a theme's web fonts still load from the network. The front matter's `title:` and `lang:` become the page title and language.
- **PDF** has one page per slide, sized like the deck (16:9 by default, or the `size` directive), with backgrounds and local images inside. It has no outline.

### PowerPoint and images

Marp Deck to PowerPoint, PNG Images and JPEG Images run [Marp CLI](#marp-cli). They do not use the preview, so they work without JCEF.

- **PPTX** has one picture per slide. The text is not editable.
- **Images**: for `deck.png` Marp CLI writes `deck.001.png`, `deck.002.png` and so on (`.jpg` for JPEG), and overwrites files with those names without asking. The notification has a Show in Folder button.

## Marp CLI

PowerPoint and image export and the richer [presentation](#present) use [Marp CLI](https://github.com/marp-team/marp-cli), which you install yourself together with Node.js. PPTX and images also need Chrome, Edge or Firefox, because Marp CLI renders the slides in a headless browser. Install it in the project (`npm install -D @marp-team/marp-cli`) or globally (`npm install -g @marp-team/marp-cli`).

It is looked for in this order:

1. The Marp CLI path in Settings | Tools | Marp. When it is set, nothing else is tried.
2. `node_modules/.bin/marp` in the deck's folder or a folder above it, up to the project root (`marp.cmd` on Windows).
3. `marp` on the PATH of the IDE.

The Test button in the settings runs `marp --version` with the same lookup and says where it found the CLI. The path is stored in the IDE configuration, never in the project, so a cloned repository cannot make the IDE run a program of its choice. `npx` is not used, because its first run downloads a package and can take minutes.

- **Trusted projects only**: the CLI can come from the repository and reads its files. In an untrusted project a notification asks you to trust it and nothing is started.
- **Same input as the preview**: the plugin passes the preview's custom themes, HTML setting and math setting in a temporary configuration of its own. The project's `.marprc`, `marp.config.js` or `package.json` `marp` section is not loaded. Only the `themeSet` of `.marprc.yml` is used, as in the preview.
- **Saves first**: Marp CLI reads the deck and its themes from disk, so the open files are saved. Only decks that are local files work.
- **Runs in the background** with a progress you can cancel, and stops after five minutes. When it fails, the notification shows the last lines Marp CLI printed.
- Marp CLI has its own copy of marp-core, so a different version can render a deck slightly differently from the preview.

## Present

Present Deck (the play button in the preview toolbar, or Find Action "Present Deck") opens the deck in the system browser, starting at the slide under the caret. You get one of two presentations:

- **Marp CLI's presentation** when "Present decks with Marp CLI" is on (the default), the project is trusted, the deck is a local file and [Marp CLI](#marp-cli) is found.
- **The built-in page** in every other case, without a message. Only a wrong Marp CLI path in the settings, or a Marp CLI that is found but does not start, is reported.

| | Marp CLI presentation | Built-in page |
| --- | --- | --- |
| Needs | Marp CLI and Node.js, no extra browser | the loaded preview |
| Unsaved changes | saves the open files first | shown as they are |
| Keys | arrows, Space, Page Up / Down; F full screen, P presenter view, O overview | Right, Down, Page Down, Space, Enter: next; Left, Up, Page Up, Backspace, Shift+Space: previous; Home, End; F full screen |
| Presenter view with notes and timer | yes, in its own window | no |
| Transitions, progress bar, on-screen buttons | yes | no |

Both are snapshots: they do not follow later edits, so press Present Deck again for a new tab with the current text. Reloading the tab keeps the slide, because its number is in the address (`#3`). In the built-in page, links to another slide (`[agenda](#2)`) work too.

The page is a temporary file that only you can read, deleted when the IDE exits. It points at the deck's folder, so relative images and theme `url()`s keep working.

## Themes

A Marp theme is a CSS file that starts with a `/* @theme name */` comment, see the [Marpit theme documentation](https://marpit.marp.app/theme-css). Select it in a deck with `theme: name`. Names are case-sensitive.

- **In the settings**: add CSS files, folders or URLs in Settings | Tools | Marp. A folder loads all `.css` files inside, skipping `node_modules` and hidden folders, at most 200 files and 8 levels deep. A URL must serve CSS or plain text of at most 5 MB.
- **With `.marprc.yml`**: a `themeSet` in the project root, the same file Marp CLI uses, is picked up automatically (`.marprc.yaml`, `.marprc.json` and `.marprc` work too):

  ```yaml
  themeSet: themes
  ```

  Paths are relative to the file and must stay inside the project, because the file comes with the repository. Add themes from elsewhere in the settings. See `samples/.marprc.yml` and `samples/themes/demo.css`.

## Settings

Settings | Tools | Marp:

| Setting | What it does | Stored in |
| --- | --- | --- |
| Themes | Theme CSS files, folders or URLs, see [Themes](#themes). Double-click an entry to edit it. | project |
| Also use themeSet from .marprc.yml | Loads the `themeSet` of the project's `.marprc.yml`. The quick fixes that create or open one need it on. | project |
| HTML in slides | Off, Default (marp-core's allow list) or All. Always off in untrusted projects. | project |
| Math typesetting | MathJax (works offline), KaTeX or Off. A `math:` directive wins where marp-core allows it. | project |
| Marp CLI | Path to the executable. Empty uses the [automatic lookup](#marp-cli). | IDE |
| Present decks with Marp CLI | On by default, see [Present](#present). | IDE |
| Show presenter notes under slides | Off by default. | IDE |
| Synchronize scrolling between editor and preview | Scroll sync. | IDE |

Project settings live in `.idea/marp.xml`, which you can commit to share them with your team. IDE settings are personal.

## Trust and security

Until you trust a project, raw HTML in slides is off, custom themes are not loaded and Marp CLI is not started. This is the same idea as restricted mode in Marp for VS Code, and exports and presentations of such a project lack the HTML and themes too.

- With HTML set to All in a trusted project, the deck's own HTML and scripts end up in the exported HTML file and run in Present Deck, the same as `marp --html`. Only open exported decks you trust. Scripts never run in the preview.
- The preview page is locked down: a Content Security Policy allows only the bundled script, the page never navigates away, and it serves local files only from the project (base directory, content roots and the deck's folder).
- A remote theme CSS can observe the deck content through CSS techniques, the same as in Marp for VS Code. Only add theme URLs you trust.

To report a vulnerability, see [SECURITY.md](../SECURITY.md).

## Coming from Marp for VS Code

Both use marp-core, so a deck looks the same in both editors.

- Copy the entries of `markdown.marp.themes` (files, folders or URLs) into Settings | Tools | Marp. Relative paths like `./themes/sketch.css` work as they are.
- A `.marprc.yml` with a `themeSet` needs no migration.

Live preview, scroll sync, custom themes, math, Twemoji, inline HTML modes and restricted mode work the same. The differences:

| | Marp Preview | Marp for VS Code |
| --- | --- | --- |
| `.marprc.yml` themeSet | yes | no, it uses the setting |
| Export to HTML and PDF | built in, no Node.js | yes |
| Export to PPTX and images | through Marp CLI you install | through Marp CLI |
| Present in the browser | yes, Marp's own presentation with Marp CLI | no, open the exported HTML |
| Directive completion and diagnostics | in comments and the front matter | yes |
| Toggle Marp feature command | not yet | yes |

## Troubleshooting

**The file opens in the normal Markdown editor.** Check [what makes a file a deck](#decks-and-the-preview). If you added `marp: true` after opening the file, click the banner or reopen it.

**The preview is empty or shows a message about JCEF.** The IDE must run on the JetBrains Runtime with JCEF. Help | About should name "JetBrains s.r.o." in the Runtime line, not Temurin, Zulu or a system JDK. Switch it with Find Action | Choose Boot Java Runtime for the IDE. Snap and Flatpak packages sometimes ship without JCEF, use the Toolbox App or the tarball.

**Emoji or KaTeX glyphs are missing offline.** They come from a CDN. MathJax works offline, select it in the settings.

**My theme is not applied.** Check the `/* @theme name */` comment, that the file is in the settings or the `themeSet`, and the case of the name. In an untrusted project custom themes are not loaded, and a `.marprc` theme outside the project is never loaded. The preview banner names the reason.

**Images do not show.** Relative paths resolve from the deck's folder, and only files inside the project are served. Remote images need https: Chromium blocks or upgrades plain http.

**Images are missing in the exported HTML.** The file keeps the image paths as written. Save it next to the deck or keep the folder structure. A PDF contains the images.

**Inline HTML is stripped.** Set "HTML in slides" to Default or All, and trust the project.

**Export to HTML or PDF is missing or does nothing.** The actions need a deck open in the Marp editor and an IDE with JCEF. If a notification says the preview is not loaded, show the Split or Preview layout, wait for the slides and export again. An error banner in the preview can also stop an export, fix the deck first.

**Marp CLI was not found.** Install it (see [Marp CLI](#marp-cli)), or set its path in the settings and press Test. An IDE started from the desktop or the dock may not see your shell's PATH, so a `marp` from nvm, fnm or a shell profile is missing: set the absolute path. If the output says `env: node: No such file or directory`, start the IDE from a terminal or install Node.js system-wide.

**"No suitable browser found".** Marp CLI needs Chrome, Edge or Firefox for PPTX and images. Install one of them.

**Present Deck opens a blank page, an error page or nothing.** A browser installed as a Snap or Flatpak package (Firefox on Ubuntu, for example) cannot read the temporary file. Use a browser from a normal package or its tarball, or export to HTML next to the deck and open that.

**Present Deck has no presenter view.** That is the built-in page. See [Present](#present) for when you get Marp CLI's presentation, and press Test in the settings.

**Present Deck shows old text.** It is a snapshot. Press Present Deck again.

**Logs**: Help | Show Log in Files. Errors from the preview page are logged too.

## Limitations

- JCEF is required. Without it the preview shows a message, and only PPTX and image export work.
- Twemoji images and KaTeX fonts need a network connection.
- Decks that use `headingDivider` cannot be reordered. Slides cannot be dragged in the Structure view or in the normal preview, and only one at a time in the overview.
- PPTX and image export always export the whole deck: no single slide, no editable PPTX, no image scale, no notes.
- Present Deck is a snapshot, with no live reload.
- The bundled Markdown plugin folds the front matter of a sample deck inside a fenced `markdown` code block. That fold is not Marp's and is reported to JetBrains.
- Marp CLI on Windows and Remote Development (JetBrains Gateway) are not tested. On a thin client the preview may stay empty, because JCEF has to run in the process that shows the editor. Please report what you see.
