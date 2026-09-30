# Marp Preview

[![Build](https://github.com/p3kj/marp-intellij/actions/workflows/build.yml/badge.svg)](https://github.com/p3kj/marp-intellij/actions/workflows/build.yml)
<!-- Enable after the first Marketplace release, replace NNNNN with the numeric plugin id:
[![Version](https://img.shields.io/jetbrains/plugin/v/NNNNN-marp-preview.svg)](https://plugins.jetbrains.com/plugin/NNNNN-marp-preview)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/NNNNN-marp-preview.svg)](https://plugins.jetbrains.com/plugin/NNNNN-marp-preview)
-->
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Marp Preview renders [Marp](https://marp.app/) slide decks inside your JetBrains IDE. The slides appear in a JCEF preview next to the Markdown editor and refresh while you type.

![Marp Preview: Markdown on the left, the rendered slides on the right, the slide under the caret outlined](docs/screenshot.png)

The plugin works in all IntelliJ-based IDEs on the 2026.2 platform and newer (IntelliJ IDEA, PhpStorm, WebStorm, PyCharm, GoLand, CLion, Rider, RubyMine and others). It depends only on the bundled Markdown plugin and JCEF. No Node.js is needed for the preview.

## Installation

The plugin is not on the JetBrains Marketplace yet. Until it is, install a release build from disk (see below).

**From the JetBrains Marketplace** (once it is published)

1. Open Settings | Plugins, select the Marketplace tab and search for "Marp Preview".
2. Click Install. No restart is needed.

**From a ZIP (Install Plugin from Disk)**

1. Download `marp-intellij-<version>.zip` from the GitHub Releases page (or build it yourself, see [Development](#development)).
2. Open Settings | Plugins, click the gear icon and choose Install Plugin from Disk.
3. Select the ZIP (do not unpack it). The plugin loads without restarting the IDE.

Requires an IDE on the 2026.2 platform or newer, running on the JetBrains Runtime with JCEF (the default in all JetBrains IDEs).

## Quick start

1. Install the plugin.
2. Create `deck.md` with File | New | Marp Presentation, or by hand with a Marp front matter and a few slides:

   ```markdown
   ---
   marp: true
   theme: default
   paginate: true
   ---

   # Hello, Marp

   Slides in your IDE, live.

   ---

   ## Second slide

   - Separate slides with `---`
   - Images: `![w:400](images/diagram.png)`
   - Math: $E = mc^2$
   ```

3. The file opens in a split view: Markdown on the left, slides on the right. Use the Editor / Split / Preview buttons in the editor toolbar to change the layout.
4. If the file was already open when you added `marp: true`, click the link in the "Marp deck detected" banner at the top of the editor.

For a bigger example open the `samples/` folder of this repository as a project. It has a custom theme, a `.marprc.yml`, local images and two linked decks.

## Features

- Live preview of Marp decks next to the Markdown editor, refreshed while typing
- Two-way scroll sync, highlight of the slide under the caret, double-click a slide to jump to its source line
- Slide outline in the Structure tool window and the File Structure popup (Ctrl+F12 / Cmd+F12), with each slide's headings; click a slide to jump to it
- Slide navigation: "Slide 3 / 12" in the status bar (click to go to a slide), Next / Previous Slide (Ctrl+Alt+PageDown / Ctrl+Alt+PageUp, Cmd+Opt on macOS) and Go to Slide in the Navigate menu, slide numbers in the editor, and folding of each slide
- File | New | Marp Presentation starter deck, and live templates `slide`, `lead`, `bg` and `notes` (type the abbreviation and press Tab)
- Directives in comments and in the front matter: completion, hover docs and an inspection for unknown directives, `_theme` and invalid values, and highlighting in comments
- Unknown theme names are marked in the editor, with quick fixes to add a theme file or folder
- Ctrl+click a theme name to open its CSS file
- Custom themes from files, folders and URLs, and automatic pickup of `themeSet` from `.marprc.yml`
- Theme CSS edits show up live, before the file is saved
- Local images with relative paths, including `![bg](...)` backgrounds
- Math with MathJax (works offline) or KaTeX
- Emoji support through Twemoji
- The IDE spellchecker knows Marp words such as `marp`, Marpit and Twemoji, so `marp: true` is not flagged as a typo
- Inline HTML in slides: off, Marp's allow list or all
- The preview follows the IDE light or dark theme
- Locked-down preview page (Content Security Policy, no navigation away) and a restricted mode for untrusted projects
- Works in every IntelliJ-based IDE, no IDE-specific APIs

A Markdown file is treated as a Marp deck when its front matter contains `marp: true`. Other Markdown files keep the regular Markdown preview.

## How the preview behaves

- **Typing** re-renders the preview at most every 150 ms. Nothing needs to be saved.
- **Scrolling** the editor scrolls the preview to the same source line, and the other way round.
- **The caret** highlights the slide it is in.
- **Structure view** (Structure tool window, Ctrl+F12 / Cmd+F12): one node per slide, named after its first heading, with the other headings of the slide nested below it. Slides follow the same rules as the preview, including `headingDivider`. Clicking a node moves the caret there, so the preview highlights that slide and, with scroll sync on, scrolls to it. Other Markdown files keep the regular heading outline.
- **Slide navigation**: the status bar shows the slide under the caret ("Slide 3 / 12") in a Marp deck and nothing elsewhere, and a click opens Go to Slide. Navigate | Next Slide, Previous Slide and Go to Slide... move the caret to the start of a slide (Next and Previous stay at the last and the first slide). Slides follow the same rules as the preview, including `headingDivider`, so moving the caret makes the preview follow (with scroll sync on, it scrolls to the slide). The actions work while the editor has the focus, not while the preview does. Editor labels ("Slide 3") at the end of the line where each slide starts can be turned off in Settings | Editor | Inlay Hints | Other | Markdown. Change the shortcuts in Settings | Keymap (search "Marp"). The NetBeans and Visual Studio keymaps use Ctrl+Alt+PageUp / PageDown for something else, so they get no default shortcut. Each slide folds in the editor, and the folded text reads "Slide 3: Agenda". A slide started by `---` folds from the end of that line (the closing front matter line for the first slide) to its last content, and a slide that `headingDivider` started folds from its heading, which the placeholder replaces. Slides start expanded. Code | Folding | Collapse All folds every slide, and also the Markdown regions inside the slides (lists, code fences). The slide regions replace the Markdown heading regions that would run across a `---` line.
- **Directive comments**: in an HTML comment of a deck, directive names and values are highlighted, and comments that Marp shows as presenter notes get their own color (Settings | Editor | Color Scheme | Marp). Basic completion (Ctrl+Space) offers directive names, the `_` form that applies to one slide, and values such as `paginate: hold`, `size: 4:3`, `class: lead` and theme names, including your custom themes. Quick documentation (Ctrl+Q, F1 on macOS) and hovering explain a directive. The Marp directive inspection warns about unknown directives ("did you mean `_class`?"), global directives written with an underscore such as `_theme` (Marp ignores them), and values Marp ignores or misreads for `paginate`, `math` and `headingDivider`. Comments that are only presenter notes, like `<!-- Note: say hello -->`, are left alone. A `theme:` that names an unknown theme (not `default`, `gaia`, `uncover` or one of your custom themes) is marked by the separate "Unknown Marp theme" inspection. Press Alt+Enter on it to add a theme CSS file or folder to Settings | Tools | Marp, create a `.marprc.yml` with a `themeSet` (or open the one you have), or open the settings. The name is case-sensitive. The inspection stays quiet while the themes are loading, in an untrusted project, and while a theme source has an error (the preview banner names it), because the theme may be missing for that reason. Ctrl+click (Cmd+click on macOS) a theme name, or press Ctrl+B (Cmd+B), to open the CSS file that declares it. Built-in themes and themes from URLs have no file to open, and nothing opens while the themes are still loading. Flow style (`<!-- { class: lead } -->`) is read as a note, use one `key: value` per line.
- **Front matter**: the same completion, documentation and inspection work on the top-level keys of the front matter of a deck, which also offers `marp` and does not offer a key that is already there (a repeated key makes Marp ignore the whole front matter). The keys of the Markdown plugin's front matter schema, such as `title`, keep working next to the Marp keys. Keys that are not Marp directives (`title`, `author`, ...) are left alone by the inspection, unless one is a near miss of a directive (`pagiante`), which is a weak warning. Nested values and lists are left to the YAML support. Like everything else, this needs a deck: the file has to contain `marp: true` already.
- **Double-click** a slide to move the caret to its source line and focus the editor.
- **Toolbar**: move the mouse over the editor or the preview and a small toolbar appears in the top-right corner. It toggles scroll sync and presenter notes and opens Settings | Tools | Marp. Turning scroll sync back on realigns the preview with the editor.
- **Links**: links to files inside the project open in the IDE. `http(s)` and `mailto` links open in the system browser. Everything else is ignored. The preview page itself never navigates away.
- **Errors** (a theme that cannot be loaded, a render error, a `theme:` directive that names an unknown theme) show as a banner in the preview that you can dismiss.
- **Theme CSS edits** are picked up while you type.
- **IDE theme**: the area around the slides follows the IDE light or dark theme. The slides keep the colors of their Marp theme.
- **Untrusted projects**: raw HTML in slides is off, and no custom themes are loaded until you trust the project. This is the same idea as restricted mode in Marp for VS Code.

## Settings

Open Settings | Tools | Marp.

| Setting | Description |
| ------- | ----------- |
| Themes | List of theme CSS files, folders or URLs available to your decks. Each theme declares its name with a `/* @theme name */` comment. Use + to pick a file or folder, enter a URL, or type a path (for example a folder that does not exist yet); double-click an entry to edit it. |
| Also use themeSet from .marprc.yml | When enabled, the `themeSet` from `.marprc.yml` (or `.yaml`, `.json`, `.marprc`) in the project root is loaded automatically. The quick fixes that create or open a `.marprc.yml` need this on. |
| HTML in slides | Inline HTML: Off, Default (marp-core allowlist) or All. Forced to Off in untrusted projects. |
| Math typesetting | MathJax, KaTeX or Off. The `math:` directive in the front matter takes precedence over this setting where marp-core allows it. |
| Show presenter notes under slides | Shows each slide's presenter notes (HTML comments that are not directives, like `<!-- Say hello -->`) under the slide. Off by default, applies to all projects. |
| Synchronize scrolling between editor and preview | Turns scroll sync on or off. This setting applies to all projects. |

Presenter notes and scroll sync can also be toggled from the preview toolbar; that changes these same settings.

The theme, HTML and math settings are stored per project in `.idea/marp.xml`. You can commit that file to share them with your team. Presenter notes and scroll sync are personal preferences and are stored in the IDE configuration.

## Themes

Marp themes are plain CSS files that start with a `/* @theme name */` comment, see the [Marpit theme documentation](https://marpit.marp.app/theme-css). Select a theme in the deck's front matter with `theme: name`.

Ways to add themes:

- In Settings | Tools | Marp, add CSS files, folders or URLs to the themes list. For a folder, all `.css` files inside, including subfolders, are loaded. `node_modules` and hidden folders are skipped, and at most 200 files, 8 levels deep, are loaded from one folder. A theme URL must serve CSS or plain text of at most 5 MB.
- Put a `.marprc.yml` with a `themeSet` in the project root, the same file Marp CLI uses. It is picked up automatically:

  ```yaml
  themeSet: themes
  ```

  Paths are relative to the `.marprc.yml` file and must stay inside the project directory: the file comes with the repository, so it cannot load CSS from elsewhere on your machine. Add such themes in the settings instead. See `samples/.marprc.yml` and `samples/themes/demo.css` for a working example.

### Migrating from VS Code

If you used the Marp for VS Code extension:

- Setting `markdown.marp.themes`: copy its entries (files, folders or URLs) into Settings | Tools | Marp. Relative paths like `./themes/sketch.css` work as they are.
- A `.marprc.yml` with `themeSet` needs no migration. It is picked up automatically.

## Troubleshooting and FAQ

**The file opens in the normal Markdown editor.** The front matter must start at the first character of the file, contain the line `marp: true`, and end within the first 64 K characters. If you added it after opening the file, click the link in the "Marp deck detected" banner, or close and reopen the file.

**The preview is empty, or shows a message about JCEF.** The IDE must run on the JetBrains Runtime with JCEF. Check Help | About: the Runtime line should say "JetBrains s.r.o.", not Temurin, Zulu or a system JDK. Switch it with Help | Find Action | Choose Boot Java Runtime for the IDE. Snap and Flatpak packages sometimes ship without JCEF. Use the Toolbox App or the tarball instead.

**Remote Development (JetBrains Gateway).** Not tested. The preview needs JCEF in the process that shows the editor, so on a thin client it may stay empty. Please report what you see.

**Emoji or KaTeX glyphs are missing offline.** Twemoji images and KaTeX fonts come from a CDN. MathJax works offline, so select it in Settings | Tools | Marp.

**My theme is not applied.** The CSS file must start with a `/* @theme name */` comment and be listed in Settings | Tools | Marp, or be part of the `themeSet` of a `.marprc.yml` in the project root. The name is case-sensitive. A `theme:` directive with an unknown name shows a warning in the preview, and the editor marks it too: press Alt+Enter for fixes.

**Images do not show.** Relative paths resolve from the Markdown file's folder. Only files inside the project (content roots or the deck's folder) are served. Remote images need https: plain http images are mixed content in Chromium and are upgraded or blocked.

**Inline HTML is stripped.** Set "HTML in slides" to Default or All. In an untrusted project HTML stays off until you trust the project. Scripts in slides never run.

**Custom themes do not load.** The project is probably untrusted: the preview then shows a banner saying so. Trust it (the IDE asks when you open it) and the themes load. A `.marprc` theme outside the project directory is not loaded either, the banner names it.

**Where are the settings stored?** See [Settings](#settings).

**Logs.** Help | Show Log in Files. Errors reported by the preview page are logged too.

## Comparison with Marp for VS Code

| | Marp Preview (JetBrains) | Marp for VS Code |
| --- | --- | --- |
| Live preview | yes, JCEF, refreshes while typing | yes |
| Scroll sync, active slide highlight | yes, both directions | yes |
| Custom themes (files, folders, URLs) | yes, same semantics as `markdown.marp.themes` | yes |
| `.marprc.yml` themeSet | yes | no, it uses the setting |
| Math (MathJax / KaTeX), Twemoji, fitting headers | yes (marp-core) | yes (marp-core) |
| Inline HTML modes off / default / all | yes | yes |
| Restricted mode for untrusted projects | yes | yes |
| Export to PDF, PPTX, HTML, images | not yet | yes |
| Directive completion and diagnostics | yes, in comments and the front matter | yes |
| Toggle Marp feature command | not yet | yes |

Both use marp-core for rendering, so a deck looks the same in both editors. The rendering engine is bundled, so the preview needs no Node.js.

## Why not the built-in Markdown preview?

The bundled Markdown plugin renders a document, not a deck. It has no notion of slide breaks, directives, themes or backgrounds, and it has no stable API for a plugin to replace its renderer. Marp Preview registers its own split editor for files with `marp: true` and renders them with marp-core, the engine behind Marp CLI and Marp for VS Code.

## Known limitations

- Twemoji images and KaTeX fonts are loaded from a CDN. Offline you lose emoji and KaTeX glyphs. MathJax works offline, so prefer it if you work without a network.
- Export is not available yet.
- Speaker notes are not shown in the preview.
- JCEF is required. Without it the preview shows a message instead of the slides.
- Remote Development (JetBrains Gateway thin client) is untested.

## Roadmap

- Export through Marp CLI (HTML, PDF, PPTX, images)
- Presenter notes in the preview
- Toggle Marp action
- PDF export through JCEF, without Node.js

These are plans, not promises. Ideas and votes go to the [issue tracker](https://github.com/p3kj/marp-intellij/issues).

## Development

Requirements: JDK 25 and Node.js LTS. The usual commands:

```sh
./gradlew buildPlugin        # build the plugin ZIP into build/distributions
./gradlew runIde             # run a sandbox IDE with samples/ opened
./gradlew check              # Kotlin tests, webview tests, typecheck, NOTICE check
```

The preview page lives in `webview/`. `cd webview && npm run dev` starts a dev page with a mock IDE host. See [CONTRIBUTING.md](CONTRIBUTING.md) for the full workflow (PhpStorm run, UI test IDE, DevTools ports, third-party notices) and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how the plugin is put together. Releases are described in [docs/RELEASING.md](docs/RELEASING.md).

## Security

The preview page is locked down: a Content Security Policy allows only the bundled script, the page cannot navigate away, and local files are served only from the project (base directory, content roots and the deck's folder). Untrusted projects render without raw HTML and without custom themes, and a `.marprc` cannot load theme files from outside the project. A remote theme CSS from a third party can observe deck content through CSS techniques, the same as in Marp for VS Code, so only add theme URLs you trust. To report a vulnerability see [SECURITY.md](SECURITY.md).

## License

MIT, see [LICENSE](LICENSE).

## Credits

- [Marp](https://marp.app/) by the Marp team: marp-core and Marpit do the actual rendering.
- Code ported from [marp-vscode](https://github.com/marp-team/marp-vscode) (MIT, Marp team) and from the scroll sync of the [VS Code](https://github.com/microsoft/vscode) Markdown preview (MIT, Microsoft Corporation).
- Third-party packages bundled in the webview are listed in [NOTICE](NOTICE).

Marp Preview is an independent project and is not affiliated with the Marp team.
