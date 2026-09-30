# Architecture

Marp Preview renders Marp decks with [marp-core](https://github.com/marp-team/marp-core) inside JCEF (the IDE's
Chromium). The plugin owns its split editor; it does not plug into the bundled Markdown preview (that has no stable
renderer API).

```
Markdown file with `marp: true`
  -> MarpSplitEditorProvider (HIDE_OTHER_EDITORS)
     -> TextEditorWithPreview(text editor, MarpPreviewFileEditor)   (see Platform API note)
        -> MarpPreviewPanel (placeholder, then JBCefBrowser; see Threading and lifecycle rules)
           loads https://marp.localhost/app/index.html
           Kotlin -> JS: window.marpBridge.*(json)
           JS -> Kotlin: one JBCefJSQuery, JSON messages
```

## Platform API note

The split editor builds on `TextEditorWithPreviewProvider`. JetBrains marks the Kotlin file that declares it
`@file:ApiStatus.Internal`, although the class itself carries no annotation in bytecode, so the Plugin Verifier does not
flag it. Its `createSplitEditorAsync` is `@ApiStatus.Experimental`. JetBrains' own Markdown plugin subclasses the same
class, so breaking changes are unlikely. The risk is accepted knowingly and covered by verification against the next
EAP (`verifyPlugin` with `recommended()` plus the weekly `verify-eap.yml` workflow). Apart from this class, the plugin
avoids internal, deprecated and experimental APIs.

## Source layout

| Path | Owner / purpose |
|---|---|
| `webview/` | npm project: preview page, marp-core bundle (esbuild), vitest tests. `src/export-html.ts` is the standalone export document, `src/present.ts` the presentation CSS and the script that goes into it |
| `cz.p3kj.marp.MarpDetector` | front-matter `marp: true` detection, and where the front matter starts and ends |
| `cz.p3kj.marp.slides` | slide model: Markdown PSI -> blocks (`MarpSlideParser`), Marp's split rules (`MarpSlideSplitter`, `MarpHeadingDivider`), `MarpDeck` / `MarpSlide`, and the pure slide swap (`MarpSlideReorder`) |
| `cz.p3kj.marp.structure` | slide outline for the Structure tool window and the File Structure popup |
| `cz.p3kj.marp.navigation` | slide navigation: Next / Previous / Go to Slide actions, "Slide 3 / 12" status bar widget, slide number inlay hints; slide reordering: Move Slide Up / Down actions and the Move Statement mover |
| `cz.p3kj.marp.folding` | one fold region per slide of a Marp deck |
| `cz.p3kj.marp.directives` | directives in comments and the front matter: catalog of the Marp directives, comment and front matter parser, highlighting and color page (comments), completion, documentation, theme navigation, inspection |
| `cz.p3kj.marp.images` | image syntax in alt text: catalog of the Marp image keywords, alt text finder, completion (contributor and confidence), documentation |
| `cz.p3kj.marp.editor` | file editor provider, split editor, preview file editor, preview toolbar actions (group `Marp.PreviewToolbar`) |
| `cz.p3kj.marp.preview` | JCEF panel, JS bridge (state setters and export commands with their replies), resource request handler, PDF print settings, `MarpPresentOptions` (the `present` argument of `exportHtml`) |
| `cz.p3kj.marp.export` | File \| Export \| Marp Deck to HTML / PDF, the toolbar popup, save dialog, progress and notifications; PPTX and images through an external Marp CLI (`MarpCliExporter`, `MarpCliCommand`, `MarpCliRunner`); Present Deck (`MarpPresentAction`, `MarpPresenter`, `MarpPresentFiles`) |
| `cz.p3kj.marp.sync` | editor <-> preview scroll sync, caret -> active slide |
| `cz.p3kj.marp.notifications` | "Marp deck detected, reopen with preview" banner |
| `cz.p3kj.marp.settings` | project settings (`.idea/marp.xml`), settings page |
| `cz.p3kj.marp.themes` | theme resolution, reading, URL cache, VFS watching, theme names from `@theme` comments |

## Build wiring

`webviewInstall` (Gradle `Exec`) runs `npm ci`. `buildWebview` runs the build (`node build.mjs
--outdir=build/generated/webview/webview`) after it. That directory is a resources source dir, so the jar contains `/webview/index.html`, `/webview/marp-preview.js`,
`/webview/marp-preview.css`. `testWebview` (vitest), `typecheckWebview` (tsc) and `checkNotices` run as part of `check`.

## URLs served inside JCEF

A `CefRequestHandler` on the preview browser answers every request to host `marp.localhost`, whatever the scheme or
port and also with a trailing dot (`MarpResourceRequestHandler.route`): the URLs below are served, anything else on that
host gets 404, because Chromium resolves `*.localhost` to the loopback interface and such a request must never reach
it. Requests to other hosts go to the network as usual (remote images, Google Fonts `@import`, CDN fonts).

| URL | Served from |
|---|---|
| `https://marp.localhost/app/<name>` | plugin classpath `/webview/<name>` |
| `https://marp.localhost/doc/<absolute path>` | local file. Path uses `/` separators, each segment percent-encoded; Windows drive paths look like `/doc/C:/Users/...`, UNC paths keep an empty first segment: `\\server\share\x` is `/doc//server/share/x` (only valid on Windows). Only served when the canonical file is inside the project base dir, a project content root, or the directory of the Markdown file being previewed (recomputed on every render; a content root change re-renders); otherwise 404. `MarpResourcePaths.resolveAllowedFile` checks the normalized path against the roots lexically before any file-system access (on Windows merely looking up `\\host\share\x` opens an SMB connection that sends NTLM credentials), accepts a UNC path only when that root is UNC too, and then checks the real path (no symlink escape). |

The document's base href is `https://marp.localhost/doc/<markdown file dir>/`, so relative images, `![bg](...)` and
links resolve to local files.

## Page security

Deck HTML (`html: all` in a trusted project) must never run script in the page that holds `window.__marpHost`, is
same-origin with the project files under `/doc/`, and could otherwise navigate the main frame.

- `index.html` sets this Content-Security-Policy (the dev page in `webview/dev/` gets the same one, with `'self'` for
  scripts and `connect-src 'self'` for the mock host):

  ```
  default-src 'none'; script-src https://marp.localhost/app/; style-src 'self' 'unsafe-inline' https:;
  img-src 'self' https: http: data: blob:; font-src 'self' https: data:; media-src 'self' https: http:;
  connect-src 'none'; frame-src 'none'; object-src 'none'; form-action 'none'; base-uri 'self'
  ```

  `frame-src 'none'` blocks frames with a `src`. Frames without one (`about:blank`, `srcdoc`) are not covered by it, so the
page removes `<meta http-equiv>`, `<iframe>`, `<frame>`, `<object>`, `<embed>`, `<portal>`, `<base>` and
`<link rel=import>` from the deck HTML, and every `autofocus` attribute, before it is inserted (under every HTML
setting). The page also cancels `dragover` / `drop`, so a dropped file or link never becomes a navigation. Remote
images work over https. `http:` images are mixed content in Chromium (upgraded to https or blocked) although the CSP
lists `http:`. Only the bundled `/app/` script runs; inline `<script>`, event handler attributes, `javascript:` URLs, frames,
  plugins and `fetch` are blocked, and forms cannot submit (`form-action 'none'`, they still render). Theme CSS (injected as a `<style>` text), marp-core's inline styles, KaTeX /
  Google Fonts `@import`s, remote and local images, fonts and media keep working. Kotlin's `executeJavaScript` and the
  `JBCefJSQuery` function are not subject to the page CSP.
- The main frame only ever shows `https://marp.localhost/app/...` (initial load and reloads). `onBeforeBrowse` cancels
  every other main-frame navigation (`about:blank`, `data:`, `file:`, `<meta http-equiv=refresh>` targets, links that
  escape the page's click handler); user-initiated ones to `http(s)` / `mailto` URLs go to the `openLink` rules below.
  JCEF's error page is disabled (it would be such a navigation). CEF never calls `onBeforeBrowse` for `about:blank`, so
  the page also drops `<meta http-equiv>` from deck HTML and intercepts clicks on HTML `<a>` / `<area>` and SVG `<a>`
  (`href` or `xlink:href`); should the main frame still end up on another page, Kotlin loads the preview page again
  (same cap as crash reloads).
- `/doc/` files are only served for requests whose CEF request initiator is the preview page (`https://marp.localhost`,
  or empty / `null` for browser-initiated requests); other initiators get 404 and the request never reaches the network.
- `openLink` (click messages, popups, cancelled navigations): `https://marp.localhost/doc/...` opens the file in the IDE
  only when it passes the same allowed-roots check as the resource handler (any file type); other URLs on
  `marp.localhost` are ignored; `http(s)` with a host and `mailto` go to `BrowserUtil.browse`; everything else is
  ignored (debug log).

## JS bridge (Kotlin -> JS)

The page defines `window.marpBridge`. Kotlin calls it with `executeJavaScript` and JSON-encoded arguments, only after
the page sent `ready`. Line numbers are 0-based editor lines; fractional values mean "part way into that line".

Every method in the interface below is a state setter, except the two commands described after it. `MarpBridgeState` keeps the latest argument per method; calls made while the page is
loading are only recorded, and on every `ready` (also after a reload) the latest arguments are replayed in the order
listed under `ready` below. When the renderer process dies, or the main frame shows anything but an `/app/` page, the
preview page is loaded again, at most 3 times in a row (a page that stayed up for 10 s starts a new row).

```ts
interface MarpBridge {
  /**
   * The page's user-visible texts from MarpBundle (sent first on every `ready`; English defaults until then).
   * `{0}`, `{1}` are placeholders the page fills in.
   */
  setStrings(arg: {
    dismiss: string      // error banner close button: tooltip and aria-label
    themeError: string   // {0} theme source, {1} marp-core's error
    renderError: string  // {0} error message
    unknownTheme: string // {0} theme name from a `theme:` directive that no built-in/registered theme has
    emptyDeck: string    // hint under a deck without content; no placeholders
  }): void
  /** Replace custom themes. errors: Kotlin-side problems (missing file, download failed) shown in the preview. */
  setThemes(arg: { themes: { source: string; css: string }[]; errors: string[] }): void
  /** Render. Called on load and on document changes (throttled, at most every 150 ms while typing). */
  update(arg: {
    markdown: string
    baseHref: string            // https://marp.localhost/doc/<dir>/
    options: {
      html: 'off' | 'default' | 'all'
      math: 'mathjax' | 'katex' | 'off'
      notes?: boolean           // presenter notes under each slide; absent or false = off
    }
  }): void
  /** Editor scrolled: make the preview show `line` at its top (VS Code scroll-sync interpolation). */
  scrollToLine(line: number): void
  /** Caret moved: highlight the slide that contains `line`. */
  setActiveLine(line: number): void
  /** IDE look and feel. Colors are CSS hex strings. */
  setIdeTheme(arg: { dark: boolean; background: string; foreground: string }): void
  /** Slide overview: the slides as a grid of thumbnails, scroll sync suspended, a click jumps to the slide's source. */
  setOverview(on: boolean): void
}
```

### Commands (Kotlin -> JS)

`exportHtml` and `flushRender` are not state setters: `MarpBridgeState.command` never records them and never replays
them on `ready`, and only sends them while the page is ready (it returns `false` otherwise, and after dispose). Every
command carries a numeric `id` and gets exactly one `reply` message with that id (see JS -> Kotlin messages). Kotlin
tracks the ids in `MarpPageReplies`; a reload, a lost page or dispose fails the commands still waiting.

```ts
interface MarpBridge {
  /**
   * Renders the last `update` (markdown, options, current themes) with a separate export Marp instance and replies with
   * the complete standalone HTML document. `title` is used when the deck has no `title:` directive. Replies with an
   * `error` when there was no update yet or marp-core threw. With `present` the document is a presentation instead (see
   * Present): `baseHref` is emitted as `<base href>` (omitted: no base), `start` is the 0-based slide it starts at.
   */
  exportHtml(arg: { id: number; title: string; present?: { baseHref?: string; start: number } }): void
  /**
   * Renders a pending update right away (`if (renderPending) render()`), then replies after the next frame and once the
   * web fonts and the `<img>` elements of the slides have loaded or failed, at most 5 s later (printing does not wait for
   * either). Used before printing the page to PDF. Replies with an `error` when there was no update or the last render
   * failed. The render that is still queued in the page runs later with the same argument, and the slide diff makes that
   * a no-op. CSS background images (`![bg]`) are not waited for.
   */
  flushRender(arg: { id: number }): void
}
```

Rendering follows marp-vscode's preview options: `container: {tag:'div', id:'__marp-preview'}`,
`slideContainer: {tag:'div', 'data-marp-slide-wrapper': ''}`, `inlineSVG: {backdropSelector: false}`,
`minifyCSS: false`, `script: false`, `html` (`off` -> `false`, `default` -> marp-core allowlist, `all` -> `true`),
`math`. Front-matter `math:` can pick the library (`mathjax`, `katex`) or turn math off for a deck. It cannot enable
math when the setting is off, because the math plugin is then not loaded at all. After each render the page calls `browser()`
update from `@marp-team/marp-core/browser` (fitting headers, auto-scaling).

With `options.notes` (the IDE-wide "Show presenter notes" setting), the page inserts an
`<aside class="marp-notes" data-marp-notes-for="N" role="note">` (1-based slide number) after every slide wrapper with
the slide's non-directive comments from `marp.render().comments` as text paragraphs, hidden when the slide has none.
Cards are siblings of the wrappers, so the slide diff replaces only a changed card; they carry no `code-line` and
scroll sync ignores them. Toggling notes does not rebuild Marp. When the deck has no content (Marpit renders one empty
slide) the page shows the `emptyDeck` hint below it.

Slide overview (#14, `setOverview`, `webview/src/overview.ts`): turns the slides that are already rendered into a grid of
thumbnails. There is no second render and no second DOM: the class `marp-overview` on `#marp-root` makes `#__marp-preview`
a CSS grid (`repeat(auto-fill, minmax(200px, 1fr))`), the wrappers take the width of their cell, and marpit's inline SVG
(a `viewBox` and no size) scales to it. The rules are inside `@media screen` in `preview.css`, so printing the page to PDF
with the grid on still gives one slide per page. In the grid:

- Presenter notes are hidden, and slides and links are inert: the SVG has `pointer-events: none`, and the page's click
  handler prevents every click and posts no `openLink` (middle click does nothing).
- A single click on a thumbnail posts `didClick` with the slide's `data-marp-content-start-line` (`slideLineAt`, the line
  the active-slide ranges start at; a click in the gap posts nothing). Kotlin moves the caret as for a double-click, so
  `setActiveLine` moves the highlight to the clicked slide and the editor scrolls to the caret. The grid stays on. There is
  no new host message and the page never changes Kotlin state. The double-click handler does nothing in the grid, the two
  clicks already posted.
- Scroll sync is suspended in both directions, because source lines have no monotonic position in a grid: `applyScroll`
  returns at once and the scroll listener sends no `revealLine`. `scrollToLine` still records the anchor, so the anchor
  follows the editor and turning the overview off re-applies it like after a resize (`realign`), landing on the slide
  that was clicked when scroll sync is on. Kotlin keeps sending `scrollToLine`, nothing in `MarpScrollSync` changes.
- The active-slide highlight stays. Entering the grid scrolls the active thumbnail into view (`block: 'nearest'`; when
  the overview is turned on before the first render, as after a page reload, the first render does it once), and so does
  `setActiveLine` when the highlight moves to another slide. A caret move inside the same slide and a render do not
  scroll (typing must not jump, and the user's own scrolling of the grid must not be undone).

The state is per editor, not IDE-wide and not persisted: it is a view mode of one deck, like the Editor / Split / Preview
layout, and an IDE-wide setting would flip every open preview and reopen decks as a grid after a restart.
`MarpPreviewFileEditor.overview` holds it (so it also works without JCEF), `setOverview(on)` passes it to the panel, and
`MarpBridgeState` replays it after a reload, last in the order. The toolbar button (`Marp.ToggleOverview`,
`MarpOverviewToggleAction`, `AllIcons.Actions.GroupBy`, `BGT`) finds its editor in the data context like the export actions
(`MarpExporter.previewOf`) and is hidden without a preview page.

Scroll sync interpolates the same way in both directions: linearly between the tops of adjacent visible `code-line`
elements, proportionally inside fenced code, so a preview position maps to a line that maps back to the same position.

## JS -> Kotlin messages

On every main-frame load end Kotlin injects:

```js
window.__marpHost = { post: function (msg) { /* JBCefJSQuery.inject("msg") */ } };
if (window.__marpHostReady) window.__marpHostReady();
```

The page queues messages until `__marpHost` exists (`__marpHostReady` flushes the queue). Each message is a JSON
string:

| Message | Meaning |
|---|---|
| `{"type":"ready"}` | bridge is installed; Kotlin sends `setStrings`, `setIdeTheme`, `setThemes`, `update`, `scrollToLine`, `setActiveLine`, `setOverview` |
| `{"type":"revealLine","line":n}` | the user scrolled the preview; scroll the editor so fractional line `n` is at the top |
| `{"type":"didClick","line":n}` | double-click in a slide, or a click on a thumbnail in the slide overview (the slide's `data-marp-content-start-line`); move the caret to line `n` and focus the editor |
| `{"type":"openLink","href":"..."}` | a link was clicked (the page always prevents navigation). `https://marp.localhost/doc/...` inside the allowed roots -> open that file in the IDE; `http(s)`/`mailto` -> `BrowserUtil.browse`; anything else ignored (see Page security) |
| `{"type":"error","message":"..."}` | render/theme error, already shown in the preview; Kotlin logs it |
| `{"type":"reply","id":n,"html"?:"...","error"?:"..."}` | the one answer to the command with that `id` (`exportHtml`: `html` is the document; `flushRender`: no payload). `error` means it failed, the text is for the log |

## Kotlin contracts

- `MarpSettings` (project service, `.idea/marp.xml`): `themes`, `useMarprcThemeSet`, `html`, `math`;
  `update { }` publishes `MarpSettingsListener.TOPIC` when the block changed anything.
- `MarpAppSettings` (application service, `options/marp.xml`, settings category Tools): `scrollSync`,
  `presenterNotes` and `marpCliPath` (blank is stored as `null`), personal preferences that must not travel with
  `.idea/marp.xml`; `update { }` publishes `MarpAppSettingsListener.TOPIC` on the
  application bus. Settings | Tools | Marp (`MarpConfigurable`) edits both.
- `MarpThemeService` (project service): `suspend fun loadThemes(): MarpThemeSet` (cached, never on EDT);
  publishes `MarpThemeListener.TOPIC` when watched theme files, folders or `.marprc` change, or theme settings change.
  It also restarts the daemon (`DaemonCodeAnalyzer.restart(PsiFile, Object)`) for the open Markdown files after every
  publish (the set is loaded first, so the highlighting restarts once with a cached set) and after a load that
  `themeNamesForInspection()` started, because the unknown-theme warnings depend on the set.
- Theme sources: settings `themes` entries (file / folder / `http(s)` URL; relative paths resolve against the project
  dir, and are reported as invalid when there is none) plus, when enabled, `themeSet` from `.marprc.yml` /
  `.marprc.yaml` / `.marprc.json` / `.marprc` (no extension) in the project root (string or list; relative to the
  `.marprc` file). `.marprc` entries must stay inside the project directory, also by real path (the traversal check of
  marp-vscode 3.5.2), otherwise they are skipped with an error; settings entries are the user's own choice and may
  point anywhere.
- Folder entries (`MarpThemeFolder`): every `*.css` below the folder like marp-cli, but bounded because users do pick a
  whole project: `node_modules` and hidden directories are never entered, hidden files are skipped, symlinked
  directories inside are not followed, at most 8 levels deep and 200 files (an error line says when files were left
  out).
- Remote URLs: `HttpRequests`, 5 s connect + 5 s read timeout, all URLs of one load downloaded in parallel (theme
  order stays the entry order). A response must be `text/css`, `text/plain` or have no Content-Type, and at most 5 MB
  (declared length checked first, the body is never read past the limit). Successful downloads are cached until
  settings change, failed or rejected ones for 60 s (or until settings change).
- Invalidation (`MarpThemeWatch`): VFS events arrive on the EDT inside the write action, so matching is string
  comparison only. File entries react to the file and its parent directories; folder entries (and missing entries, which
  may become either) to the folder, its parents, and CSS files or directories inside where `MarpThemeFolder` looks;
  plus `.marprc*` files in the project root and document edits of the theme files that were read.
- Untrusted projects (`TrustedProjects.isProjectTrusted` is false), same as marp-vscode's restricted mode: `html` is
  always `off` and no custom theme is loaded at all (the settings can come from the repository as much as the
  `.marprc`); when any theme is configured, one line in `errors` says so. Problems of an untrusted `.marprc` are not
  shown. `MarpThemeService` invalidates and publishes `MarpThemeListener.TOPIC`
  on `TrustedProjectsListener` trust changes; open previews re-request themes and re-render.
- Theme CSS is sent as text; marp-core's `themeSet.add(css)` picks it up by its `@theme` name. Relative `url()`
  inside theme CSS resolve against the document base (same as marp-vscode).

## Slide model (Kotlin)

`MarpDeck` (package `cz.p3kj.marp.slides`) is the Kotlin side's view of a deck: a list of `MarpSlide`s that partition the
whole text (`startOffset` / `endOffset`, the front matter belongs to slide 1, a `---` line belongs to the slide it
starts), the headings of each slide, and `slideIndexAt(offset)`. Features that need slides use it: the Structure view,
slide navigation and slide folding.

- `MarpSlideParser.deck(MarkdownFile)` (cached per PSI modification, call in a read action) walks the Markdown plugin's
  PSI for the blocks that matter: top-level thematic breaks, headings at any depth, HTML comments (block and inline,
  they carry directives) and "other visible content". CommonMark parsing therefore decides what is a break: code
  fences and indented code, setext headings, `---` inside `<!-- -->` or `<style>`, blockquotes and lists are handled
  by the parser, like markdown-it does for Marpit. The front matter is located by `MarpDetector.findFrontMatter`
  (same rules as detection), not by the PSI front matter elements (`@ApiStatus.Experimental`); PSI nodes that start
  inside it are skipped. That end follows marp-vscode's detection regular expression, which disagrees with
  markdown-it-front-matter (what the preview uses) in exotic cases, for example an opening fence of `-----` closed by
  `---`, or an indented closing fence. Block-level `<style>` elements are hidden in Marpit and so are not visible
  content here. Heading titles are built from the token types of the heading content (emphasis, code and strikethrough
  markers, inline HTML and comments, images left out, links reduced to their text), not from
  `MarkdownHeader.buildVisibleText` (`@ApiStatus.Experimental`). Only `MarkdownFile`, `MarkdownHeader.level` and the
  element and token type constants are used from the Markdown plugin.
- Where the Markdown plugin's parser (JetBrains/markdown) is not CommonMark around `---` and `===`, `MarpSlideParser`
  corrects it so that the slides are those of markdown-it (Marpit). The parser builds `SETEXT_1` / `SETEXT_2` from ANY
  single line above the underline, not only from a paragraph line. A setext node with a single content line is
  therefore read as what markdown-it makes of the line: a thematic break (`---` above `---`) becomes a `Break`; an ATX
  line (`# A` above `---`) becomes a `Heading` with the level of the `#` markers and the text without them (also the
  closing ones); a one-line HTML comment or `<style>` element (a presenter note right above `---`) is hidden HTML, not a
  heading, and its comment still reaches the directives. For `---` the underline is then a `Break` of its own, for
  `===` it is visible text. The other way round, the parser leaves a paragraph of several lines above `---` as a
  paragraph and a rule (or, when another `---` follows, as a setext node holding the first `---`), where markdown-it
  makes an `h2` of it: a top-level `PARAGRAPH` followed by exactly one line break and a `-` only line (up to three spaces
  of indentation, no spaces inside, so not `- - -`, `***` or `___`) is a level-2 `Heading` and that line is not a break.
  Inside blockquotes and lists the setext correction gives the heading its real level, or no heading, but never a
  break, because Marpit splits at top-level `hr` only. Real setext headings (one paragraph line above the underline) are
  unchanged. Open gaps: the PSI lets `2. item` interrupt a paragraph, so `text\n2. item\n---` is one slide in Marp and
  two in the plugin; and a paragraph of two or more lines above `===` stays a paragraph in the PSI (an `h1` in Marp),
  which only matters for titles and `headingDivider: 1`.
- `MarpSlideSplitter.split` (pure, unit-tested) mirrors Marpit's `markdown/slide.js` (split at every top-level `hr`)
  and `markdown/heading_divider.js` (a hidden `hr` before headings of the `headingDivider` levels, only when something
  visible precedes it, so a real `---` followed by a divider heading leaves an empty slide between them). This has to
  agree with the preview: keep it in sync if the webview ever sets marp options that change slide splitting, such as a
  default `headingDivider`, or if Marpit changes the rules (the code cites the Marpit files it ports).
- `MarpHeadingDivider.resolve` finds the `headingDivider` global directive like Marpit: front matter first, then every
  directive comment in document order, the last valid value wins for the whole deck. Values follow Marpit's conversion
  (a number `n` means levels 1..n, a list keeps 1..6, `false` is off, anything else is ignored). They are read with Marpit's
  loose YAML: an unquoted value is the rest of the line, so `false # off` is not `false` and is ignored. YAML is not parsed,
  only `headingDivider:` at the start of a line (inline value, `[1, 3]` list or block sequence) is recognised.
- `MarpStructureViewBuilder` (returned by `MarpSplitEditor.getStructureViewBuilder`, so only Marp decks get it, other
  Markdown files keep the Markdown plugin's outline) lists one node per slide, `Slide 3: Agenda`, titled by its first
  heading, with the other headings nested by level. Tree values are keys (`MarpSlideKey`, `MarpHeadingKey`: indices, not
  offsets) so that node identity, expansion and selection survive typing. Navigation goes through
  `OpenFileDescriptor`, which the split editor routes to its text editor; the caret listener of the scroll sync then
  highlights the slide in the preview. The tree follows the caret through `getCurrentEditorElement`. It does not
  implement `ExpandInfoProvider` (experimental).

## Slide navigation (Kotlin)

Package `cz.p3kj.marp.navigation` (#9). Three features on top of `MarpDeck`, all `DumbAware`, all limited to Marp decks
(`MarpDirectiveComments.isMarpDeck`), so the slides are the ones of the preview and the Structure view, `headingDivider`
included. Moving the caret is all they do, scroll sync then makes the preview follow.

- `MarpSlideNavigation` holds the logic. `withDeck` reads the deck in a non-blocking read action
  (`ReadAction.nonBlocking(...).withDocumentsCommitted(project)`, expires when the editor is disposed) and calls the
  continuation on the EDT (`finishOnUiThread`). The deck comes from the committed PSI, and a write action that lands
  before the continuation restarts the read, so inside the continuation the deck matches `editor.document` and the LIVE
  caret offset (read there, not in the background part) can be compared with it. That makes quick repeated presses each
  advance one slide and makes an edit that is not committed yet count. Documents are never committed on the EDT.
  `moveTo` puts the caret at `MarpSlide.contentOffset`, drops secondary carets and the selection, and scrolls with
  `ScrollType.CENTER_UP`. The functions return the promise so that tests can wait for it.
- Actions `Marp.NextSlide`, `Marp.PreviousSlide`, `Marp.GoToSlide` (group `Marp.SlideNavigation` in the Navigate menu,
  `GoToMenu`). Shortcuts Ctrl+Alt+PageDown / Ctrl+Alt+PageUp (`$default` keymap, so Cmd+Opt on macOS); Go to Slide has
  none because Ctrl+G is Go to Line. The bundled NetBeans and Visual Studio keymaps use these strokes for other actions,
  so the plugin removes them from those keymaps (`<keyboard-shortcut ... remove="true"/>`) instead of clashing. Update
  is `BGT` and enables the actions only for the editor of a Marp deck (they are hidden elsewhere). Deliberately not
  `EditorAction` / `EditorActionHandler`: they need the PSI synchronously, which would mean committing documents on the
  EDT. So the actions do not work while focus is in the JCEF preview (no editor in the data context).
- Go to Slide is an input dialog (`Messages.showInputDialog`), prefilled with the current number, with a validator
  (`parseSlideNumber`: 1 to the slide count). After the dialog the deck is read again and the caret goes to the slide
  with that number, because the dialog is modal and the text may have changed meanwhile.
- `MarpSlideWidgetFactory` (`statusBarWidgetFactory` `Marp.SlidePosition`, before the platform's line:column widget) is a
  `StatusBarEditorBasedWidgetFactory`, and `MarpSlideWidget` an `EditorBasedWidget` with a `TextPresentation`: "Slide 3 /
  12", click opens Go to Slide. An empty text hides the status bar component, that is how it only shows for Marp decks.
  It refreshes on caret moves in the widget's editor (`getEditor()`), document changes and editor selection changes.
  The deck of a document is cached in the document's user data together with the `modificationStamp` it was computed for
  (also `null` for "not a deck"), so a caret move costs nothing and typing starts one coalesced non-blocking read. Files
  that are not Markdown never start a read. Not used: `TextWidgetPresentation` (experimental),
  `StatusBarWidget.getPresentation(PlatformType)` (deprecated), `EditorBasedWidgetHelper` (internal), `myProject` and
  `registerCustomListeners` (deprecated).
- `MarpSlideNumberInlayProvider` is a declarative inlay provider (`codeInsight.declarativeInlayProvider`, language
  Markdown, group `OTHER_GROUP`, id `marp.slideNumbers`, on by default, switchable in Settings | Editor | Inlay Hints).
  Its `OwnBypassCollector` adds "Slide N" at the end of the line where each slide starts (`EndOfLinePosition`): line 0
  (the front matter) for the first slide, the `---` line of a separator, the heading line of a `headingDivider` slide.
  Declarative hints run in the highlighting pass on the committed PSI, off the EDT. `createCollector` returns `null`
  outside Marp decks. There is no settings preview file (`inlayProviders/marp.slideNumbers/preview.md`), the platform
  shows the description alone. Chosen over a line marker (icons only) and `EditorLinePainter` (paints on the EDT, would
  need the deck there).

## Slide reordering (Kotlin)

Issue #15. One pure edit function and two ways to trigger it, both in Marp decks only.

- `MarpSlideReorder.move(text, deck, index, down)` (package `slides`, plain Kotlin) returns an `Edit` or `null`. An `Edit`
  replaces `[start, end)` with `text` and knows the moved piece (`movedFrom`, `movedLength`, `movedTo`, `contentTo`) so that
  `caretAfter(caret)` can keep a caret inside the moved piece at its place and send any other caret to the first
  non-blank line of it.
  Both triggers apply exactly this edit, there is no second edit path.
- Two neighbouring slides are swapped as one range. A slide travels as the text from its separator line to the start of
  the next slide, so separator styles (`---`, `***`, `___`), local directives and notes move with it. The front matter
  belongs to slide 0 in `MarpDeck` but stays on top: a swap that involves slide 0 swaps the BODIES (`bodyOffset` to the
  next separator) and the separator line of slide 1 stays between them.
- `blankEnded` puts a blank line after a piece that is followed by a separator and does not end with one. A paragraph line
  directly above `---` would otherwise become a setext heading and swallow the slide break. Decks with a blank line before
  each separator are not touched by it. The same goes for the top of the range: the piece that lands first starts with a
  separator line, and directly under a paragraph line (a `***` deck, tight decks) a `---` would be a setext underline,
  so a blank line is added there too (not below the front matter or below an empty slide). An empty first slide that
  moves down gets a blank line after its separator so that two separator lines do not end up in a row. What follows the last slide (the line breaks at the end of the file) stays at
  the end of the file, so moving the last slide up and back down gives the original text.
- `supports(deck)` is `deck.headingDivider.isEmpty()`. With a `headingDivider` a swap can merge slides (a slide without a
  divider heading at its start joins the slide before it) and reorder the competing `headingDivider` comments, so those
  decks are not supported: `move` returns `null`, the actions show `hint.moveSlide.headingDivider` and the mover steps
  aside. Structure view drag and drop is not offered either: the tree has no public drop hook (`StructureViewComponent` builds a
  private `DnDAwareTree`).
- `MarpSlideReordering` (package `navigation`) is the platform side. `move(project, editor, down)` reuses
  `MarpSlideNavigation.withDeck` (non-blocking read of the committed PSI, continuation on the EDT), takes the slide under
  the LIVE primary caret, and applies the edit in `WriteCommandAction.writeCommandAction(project, psiFile).withName(...)`,
  so the file's read-only status is honoured and the move is one undo step. `apply` reads the caret first, drops
  secondary carets and the selection, calls `document.replaceString`, moves the caret and scrolls
  (`ScrollType.MAKE_VISIBLE`). The actions `Marp.MoveSlideUp` / `Marp.MoveSlideDown` (group `Marp.SlideReordering` in
  `CodeMenu`, after `MoveLineUp`; `MarpMoveSlideAction` extends `MarpSlideAction`, so hidden outside decks, plus
  disabled in a viewer) have no default shortcut: every candidate clashes with a bundled keymap.
- `MarpSlideStatementMover` is a `StatementUpDownMover` (`com.intellij.statementUpDownMover`, `order="first"`) so that
  Move Statement Up / Down (Ctrl+Shift+Up/Down, Cmd+Shift+Up/Down) moves a whole slide when the caret is on the
  separator line of slide 1 or later. Nothing in the classes used is `@ApiStatus` flagged or deprecated on 2026.2.3.
  Everywhere else `checkAvailable` returns `false` before touching `MoveInfo` (the info is shared between movers, so it
  must only be mutated when returning `true`): several carets, a selection, a caret off a separator line, a plain
  Markdown file, a `headingDivider` deck. Markdown has no mover of its own, so `LineMover` then moves lines as before, and
  Move Line Up / Down (`MoveLineHandler` uses `LineMover` only) still moves just the separator line. On a separator line
  the move is always handled here: at the end of the deck `info.prohibitMove()` (sets `toMove2 = null`, returns `true`)
  makes it do nothing instead of sliding the separator into the last slide.
- The platform's `MoverWrapper` only swaps two line ranges, which cannot express the first slide keeping its front
  matter or the blank line a moved last slide needs. So the edit is made in `beforeMove` and `info.toMove2` is set to
  the SAME `LineRange` instance as `info.toMove`: `LineRange` has no `equals`, so the wrapper's
  `!toMove.equals(toMove2)` check skips its own swap (the documented way for movers that move in `beforeMove`, such
  as the Python one). `indentSource` and `indentTarget` must be `false`: the wrapper indents `range2`, which stays
  `null`. `BaseMoveHandler` still needs `toMove.startLine > 0 || down` and `toMove.endLine < lineCount || !down`, which
  a separator line of slide 1 or later and a next slide satisfy. All of it runs inside the platform's command and write
  action, so it is one undo step. The deck comes from the cached PSI (the handler commits the document first), read on
  the EDT like every mover does; a caret on the separator line of a moved slide stays on it, except when slide 1 moves up
  (its separator stays between the swapped bodies) where the caret goes to the first non-blank line of the moved body.
- Not done: reordering from the preview or the overview, moving by more than one place, several slides at once,
  keeping the collapsed state of the slide folds (the replaced range drops the fold regions inside it).

## Slide folding (Kotlin)

Package `cz.p3kj.marp.folding` (#10). `MarpSlideFoldingBuilder` is a `FoldingBuilderEx` (`lang.foldingBuilder`, language
Markdown, `DumbAware`) that returns one region per slide of a Marp deck and nothing for other files
(`MarpDirectiveComments.isMarpDeck`). The slides come from `MarpSlideParser.deck`, so they are the ones of the preview,
`headingDivider` included. No settings, regions are never collapsed by default.

- `folds(deck, document)` (pure, tested) maps a slide to a region. A slide started by the front matter or a `---` line
  (`bodyOffset > startOffset`) gets a region that starts at the end of that line (`bodyOffset - 1`), so the marker sits
  on the visible line and it reads `---[Slide 3: Agenda]`. A slide that `headingDivider` started (`bodyOffset ==
  startOffset`) begins with its heading, so its region starts at the heading (`startOffset`) and the placeholder
  replaces it (`[Slide 4: B]`). That is the range of the Markdown plugin's heading region, so the composite keeps ours
  and drops Markdown's duplicate: one marker on the line, and Collapse All shows our placeholder. The region ends after
  the last non-blank character of the slide, so trailing blank lines stay outside and it never reaches the next `---`
  or heading. A region has to span more than one line, so a slide with nothing after its first line (a lone divider
  heading, a `---` followed by blanks or the end of the file) has none. A slide with a single line of content folds
  that line.
- The placeholder is `Slide 3: Agenda` (`folding.slide.titled`) or `Slide 3` (`folding.slide.untitled`), so a collapsed
  slide reads `---[Slide 3: Agenda]`. The title is the first heading inside the region (the divider heading of a
  `headingDivider` slide counts) and is shortened to 60 characters. The number is passed as a string so that 1000 is not "1,000".
  Descriptors carry their own placeholder text, `getPlaceholderText(ASTNode)` is not used.
- Descriptors hang on the file node (like the Markdown plugin's TOC regions), so the fold state is not restored for
  slides after the file is closed and reopened.
- Registered with `order="first"`. `LanguageFolding` wraps all builders of a language in a composite that keeps the
  first of two identical ranges, and `FoldingModelImpl.createFoldRegion` refuses a region that strictly overlaps an
  existing one (nested and adjacent regions are fine). The Markdown plugin folds a heading up to the next heading of
  the same or a higher level, which often runs across a `---` line, so a slide region can never avoid every overlap and
  has to be created first. Dropped are the Markdown heading regions that cross a slide boundary (they would fold parts
  of two slides) and, rarely, a list or block quote that a `headingDivider` heading splits. The front matter region ends
  where the first slide region starts, block regions never cross a top-level `---`. While typing, a still valid earlier
  Markdown region can make the platform refuse a new conflicting slide region until the next full rebuild (reopening
  the file).
- Not used: `CodeFoldingManager.buildInitialFoldings` (deprecated), `FoldingDescriptor.getCachedPlaceholderText`
  (internal), `CustomFoldingBuilder` (the Markdown plugin already handles custom region comments).

## Directive comments and front matter (Kotlin)

Package `cz.p3kj.marp.directives`. Every feature is limited to Marp decks: `MarpDirectiveComments.isMarpDeck(file)`
(front matter `marp: true`, cached per PSI modification) is checked first, and other Markdown files are untouched.
The front matter is covered in the "Front matter" bullet at the end of this section, everything else is about comments.

- `MarpDirectiveCatalog` is the one source of directive facts, taken from Marpit 3.2 and marp-core 4.4 (the versions in
  `webview/node_modules`): the 16 directives with scope (global or local), origin (Marpit or marp-core), value
  suggestions and value check, `resolve(key)` (known, global written with `_`, or unknown with a "did you mean" from a
  small edit distance), and `isValid(directive, rawValue, value)`. Marpit recognises `theme`, `style`, `headingDivider`
  and `lang` as global and the `background*` family, `class`, `color`, `footer`, `header` and `paginate` as local (each
  local one also as `_name` for one slide); marp-core adds the globals `size` and `math`. A global with an underscore
  (`_theme`) is recognised by nothing, so Marp ignores it. The front matter reuses the catalog, the value checks and the
  bundle documentation keys (`directive.doc.<name>`). The `marp` key is the one thing that is not in `ALL`: it comes from
  Marp for VS Code (origin `MARP_VSCODE`), exists only in the front matter, and lives in `MarpDirectiveCatalog.MARP` and
  `FRONT_MATTER_ONLY`, so comments do not know it (`resolveInFrontMatter` adds it).
- A comment is a directive comment to Marp when its YAML is a mapping with at least one recognised key, otherwise a
  presenter note. Magic comments of other tools (`prettier-ignore`, `markdownlint-...`, `lint disable ...`, as in
  Marpit's `comment.js`) are neither, and `<!-- fit -->` inside a heading is marp-core's fitting header.
- `MarpDirectiveComments.parse` is a small line scanner for the `key: value` shape (Marpit's comment regular expression,
  then `key: value` lines, comment lines, indented and `- item` continuation lines). It is not YAML: flow mappings are
  read as notes. It follows marp-core's loose YAML: for the Marpit directives (`_` form included) a value that does not
  start with one of ``["'{|>~&*`` is the whole rest of the line, so `paginate: true # c` has the value `true # c`,
  which Marp reads as `false`; other keys (`size`, `math`, unknown ones) end a plain value at ` #`. A body that YAML
  rejects is a note to Marp: a value starting with `*` (`header: **Bold**` is an alias), a repeated key, and an indented
  line after a value that is already complete (a quoted scalar, or a loose Marpit value). It works on the text of one
  comment element, so it is pure and unit-tested. The YAML language injection
  was rejected: `MarkdownHtmlBlock` is not an injection host, and the plugin would need the YAML plugin.
- Finding the comment: with the XML module the IDE parses a Markdown file a second time as HTML (the view provider is a
  template view provider), so the same `<!-- ... -->` also exists as an `XmlComment` in a second tree, and
  `PsiFile.findElementAt` on the Markdown file returns that HTML leaf. `commentElementAt` therefore walks the AST of the
  Markdown file itself (`node.findLeafElementAt`) up to the `HTML_BLOCK` or inline `HTML_TAG` that starts with `<!--`
  (the same rule as the slide parser). Comments inside blockquotes and list items contain `>` or indentation on their
  continuation lines and are read as notes.
- Highlighting is a `MarpDirectiveAnnotator` on the Markdown language (`MARP_DIRECTIVE_KEY`, `MARP_DIRECTIVE_VALUE` and
  `MARP_PRESENTER_NOTE`, which fall back to the metadata, string and doc comment colors) and a `ColorSettingsPage`
  (Settings | Editor | Color Scheme | Marp). A comment that looks like directives (a known key or a global with `_`) gets
  key and value colors, anything else that is not magic or empty gets the note color.
- Completion is a `CompletionContributor` and a `CompletionConfidence`, both registered for `language="any"` because the
  caret element is usually in the HTML tree. The contributor ignores `parameters.position` and looks at the original
  Markdown tree (`parameters.originalFile`, `parameters.offset`), then asks `completionSpot` whether the caret is at a key
  (`_pa`) or a value (`paginate: h`). Custom theme names come from `MarpThemeService.cachedThemeNames()`, which reads the
  cached theme set without waiting (it only starts loading when nothing is cached). The confidence opens the automatic
  popup where a directive is written (after `_`, after `key: ` of a directive with values, on a new line of a directive
  comment) and skips it elsewhere in comments, because the IDE would otherwise pop up directive names while typing a
  presenter note. Documentation in the completion popup is not provided: that needs
  `LookupElementDocumentationTargetProvider`, which is `@ApiStatus.Experimental`.
- Documentation (Ctrl+Q and hover) is a `DocumentationTargetProvider` returning a `DocumentationTarget` for the key under
  the caret; the popup HTML (`MarpDirectiveDocs`) uses the public `DocumentationMarkup` strings, not the `CLASS_*`
  constants (`@ApiStatus.Internal`). The provider interface is `@ApiStatus.OverrideOnly`, which is fine to implement.
- `MarpDirectiveInspection` (`LocalInspectionTool`, `DumbAware`, short name `MarpDirective`) reports unknown keys, globals
  written with `_`, and invalid `paginate`, `math` and `headingDivider` values (inline values only). A comment that Marp
  reads as a note is only reported, as a weak warning, when a key is a near miss of a directive (`Class: lead`), so `Note: text` stays
  quiet. Unknown theme names are left to `MarpUnknownThemeInspection` (below).
- `MarpUnknownThemeInspection` (#7, `LocalInspectionTool`, `DumbAware`, short name `MarpUnknownTheme`) reports a `theme`
  value (front matter and comments, `_theme` is the directive inspection's) that is neither in
  `MarpDirectiveCatalog.BUILT_IN_THEMES` nor a custom theme name, case-sensitive like Marpit. It is a separate inspection
  because it depends on external state and must be switchable alone. It reads `MarpThemeService.themeNamesForInspection()`
  (cached set only, never blocks) and is quiet when that is `null`: nothing cached yet (loading starts and the service
  restarts the highlighting when done), an untrusted project, or any error in the set (missing file, failed download, bad
  `.marprc`), because the theme may be missing for that reason and the preview banner names it. The service is only asked
  once a theme directive with a value is found, so a deck without one never triggers a load.
  - Quick fixes (`MarpThemeQuickFixes`) are plain `LocalQuickFix`es, not ModCommand: they show a file chooser or a dialog
    and write settings, so `startInWriteAction()` and `availableInBatchMode()` are false and `generatePreview` is
    `IntentionPreviewInfo.EMPTY`. They keep strings only, never PSI. (A) add a chosen CSS file or folder to
    `MarpSettings.themes` (project-relative when inside the project, no duplicates), (B) only while `useMarprcThemeSet` is
    on: create `.marprc.yml` with `themeSet` for a file or folder chosen inside the project (chooser rooted at the project
    dir, outside choices are refused like the loader would), or, when a `.marprc*` exists, open it, (C) open Settings |
    Tools | Marp. An existing `.marprc` is never edited, in-place YAML or JSON editing is fragile. The chooser goes through
    `MarpThemeChooser.choose` so tests can replace it.
  - Not used: `DaemonCodeAnalyzer.restart()` and `restart(PsiFile)` (deprecated),
    `FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor*` (obsolete), `FileEditorManager.openFile` overloads
    (experimental flags, `OpenFileDescriptor` instead).
- Theme navigation (#8): `MarpThemeGotoDeclarationHandler` (`GotoDeclarationHandler`, `DumbAware`) opens the CSS file of a
  custom theme from the value of `theme:` (front matter and comments, not `_theme`). It is a handler rather than a
  reference because the platform asks handlers first, before references, about the injected YAML and then about the host
  file, so one handler that maps (file, offset) with `MarpDirectiveComments.entriesAt` covers the front matter with and
  without the YAML plugin and the comments, and needs no reference on injected leaves. `entriesAt` only returns what Marp
  reads as directives (a YAML mapping, no magic comment), like the highlighting. The handler reads only
  `MarpThemeService.cachedThemeFile()` (cached set, never loads, `null` while nothing is cached, for URL themes and for
  built-in names; a custom theme wins over a built-in one and the last file that declares a name wins, like the preview).
  The target is the `@theme` comment token of the CSS PSI, or the whole file without the CSS plugin. Ctrl+hover underlines
  the leaf under the mouse: the name in the front matter, the whole comment text in a comment. Not used: references,
  `GotoDeclarationOrUsageHandler2` and `CtrlMouseData` (internal).
- Front matter (#6) reuses the code above on the front matter TEXT, not on a schema and not on the front matter PSI:
  - Located by `MarpDetector.findFrontMatter` (the rule that decides whether the file is a deck, and that the slide model
    uses; `FrontMatter.bodyStart` is the offset in the original text, byte order mark included), then read by
    `MarpFrontMatter.parse` with the same line reader as comments (`MarpDirectiveComments.readEntries`, ranges are document
    offsets). `MarpFrontMatter.completionSpot` and `keysOutsideLine` do the caret work. The rules differ from the Markdown
    plugin's, which needs opening and closing lines of exactly `---+` (closing may be `...+`); marp-vscode's rule, which
    `MarpDetector` follows, allows white space after the opening fence and an indented or trailing-text closing fence, so
    following it keeps the features right in both cases.
  - Differences from comments: `marp` is a known key (`resolveInFrontMatter`), only unindented lines are keys (nested
    YAML and lists belong to the entry above and get no completion), and a key that the front matter already has is not
    offered again (a repeated key makes YAML reject the whole front matter). Values are read exactly as in comments: loose
    YAML for the Marpit directives only. `size` and `math` are plain YAML in the front matter too (marp-core's custom
    directive lists are empty at runtime, so the loose set is Marpit's built-ins), so `math: katex # c` is KaTeX.
  - YAML injection: with the YAML plugin (bundled in IntelliJ IDEA) the Markdown plugin parses `FRONT_MATTER_HEADER` and
    injects YAML into it. Completion, completion confidence, documentation and go to declaration are then called with the injected YAML
    file and injected offsets (hover asks the injected file first, then the host), and without the YAML plugin the caret is
    in Markdown PSI. Every entry point therefore maps (file, offset) to the Markdown file with
    `InjectedLanguageManager` (`MarpFrontMatter.hostOf`, the identity for a host file) and then works on the text. Tests load
    the YAML plugin (`testBundledPlugin`), so the fixture hands out the injected file like the IDE.
  - Completion does not call `stopHere()` for keys, so the other front matter keys (the Markdown plugin's schema adds
    `title`, `layout`, ...) stay and ours rank first by priority. Values of a Marp key are ours alone. The confidence
    opens the popup for every key and for values of directives that have some, and leaves everything else to YAML.
  - Documentation: no special ordering. The platform merges the targets of all `DocumentationTargetProvider`s, and the
    YAML plugin has no target provider of its own (only a legacy PSI-fallback documentation provider), so this provider
    is empty except on Marp keys and the two do not compete.
  - Inspection: the Markdown `PsiFile` is visited like an element (only `MarkdownFile`: the HTML root of the same view
    provider is visited too and would report everything twice), and all problems are registered on the file with document
    offsets (file-relative equals document offset, as `LossyEncodingInspection` does). Unknown keys are the metadata of
    other tools (`title`, `author`, ...) and only a near miss of a directive gets a weak warning, and only when the
    suggested directive has at least 5 characters or the key differs from it in case only (`path` is one edit from
    `math` and `site` from `size`, both are ordinary metadata); a front matter that is not a mapping is left to the YAML
    plugin.
  - Rejected: a JSON schema for the front matter. The Markdown plugin's `FrontMatterHeaderJsonSchemaFileProvider`
    (`@ApiStatus.Internal`) maps every front matter to its generic schema, so a second Marp schema would compete with a
    provider that cannot be replaced, would need the JSON and YAML plugins as dependencies, could not express loose YAML
    (`paginate: true # c` is not `true`), `_theme`, "did you mean" or custom theme names, and would be a second source of
    truth next to the catalog. Not used either: `MarkdownFrontMatterHeader` and its content class
    (`@ApiStatus.Experimental`), anything in `org.intellij.plugins.markdown.frontmatter` (internal), the YAML PSI classes
    (no compile dependency on the YAML plugin), `InjectedLanguageManager.injectedToHost(..., boolean)`, and
    `LENIENT_INSPECTIONS`.
  - Out of scope: marp-cli metadata keys, TOML front matter (`+++`), highlighting in the front matter (YAML colors it),
    duplicate keys and YAML syntax errors (the YAML plugin reports them), completion popup docs. Unknown theme names in the
    front matter are reported by `MarpUnknownThemeInspection`, like in comments.

## Image syntax (Kotlin)

Package `cz.p3kj.marp.images` (#11): completion and hover docs for the keywords in the alt text of `![...](...)`. Limited
to Marp decks (`MarpDirectiveComments.isMarpDeck`), all `DumbAware`.

- `MarpImageKeywordCatalog` is the one source of facts, from the Marpit 3.2.3 files `lib/markdown/image/parse.js`,
  `lib/markdown/background_image/parse.js` and `advanced.js` (marp-core 4.4.0 adds no image keyword). Marpit splits the
  alt text at whitespace and matches every word on its own, so the order does not matter. Any image takes `w:`/`width:`,
  `h:`/`height:` and the ten CSS filters (with `:arg` and the defaults in the catalog); only an alt text with the word
  `bg` also takes `fit`, `contain`, `cover`, `auto`, `N%`, `left`/`right` (with `:N%`), `vertical` and `horizontal`
  (`backgroundOnly`). `resolve(word)` maps a word such as `left:40%` to its keyword. Documentation is in the bundle
  (`image.doc.<name>`).
- `MarpImageSyntax.altSpot(text, caret)` is pure and works on the text of the line, not on PSI: while `![b` is typed the
  Markdown tree has no image element (just `!`, `[` and text), and `![b]` alone may parse as a reference link. From the
  caret it scans back to a `[` preceded by `!` (not by `\!`) and stops with `null` at a line break, a `]` or a `[`
  without `!`. The alt text ends at the first `]` or the line end. Other escaped brackets and alt text over several
  lines are not handled.
- `spotAt(file, offset)` adds the file checks: `MarpFrontMatter.hostOf` for injected files, deck detection, the front
  matter (`MarpDetector.findFrontMatter`, reject before its `endOffset`), and a walk up from the `[` in the Markdown tree
  of the file (`node.findLeafElementAt`, not `PsiFile.findElementAt`, see the directive section) that rejects
  `CODE_FENCE`, `CODE_BLOCK`, `CODE_SPAN`, `HTML_BLOCK` and `HTML_TAG`.
- Completion is a `CompletionContributor` and a `CompletionConfidence` for `language="any"`, like the directive ones. It
  offers the keywords whose lookup string starts with the word before the caret (case-sensitive: the platform matcher
  also matches inside words, so `![p` would offer drop-shadow and opacity), without `bg` when the alt text has it and
  only the ones without `backgroundOnly` when it does not, minus the keywords already present (`w` and `width`, `h` and
  `height` count as the same, `MarpImageKeywordCatalog.effectOf`). Priority follows the catalog order. There is no
  insert handler: the lookup string of `w`/`h`/`width`/`height` ends in `:`, the others are bare. Percentages are
  documented but not offered. The contributor calls `stopHere()` only when the alt text holds nothing but keywords
  (`optionsLikely`), so Ctrl+Space in a description still gets the other completions.
- The confidence returns `NO` (open the popup) while `optionsLikely`, which is true for the first word, `YES` (skip) in
  an alt text with another word, and `UNSURE` outside alt text. No scheduling by typed characters: the popup opens as a
  letter is typed, and the platform's default of matching the case of the first letter keeps `![Diagram` quiet.
- Documentation is a `DocumentationTargetProvider` like the directive one; it resolves the whole word around the offset
  (the offset at the end of the word counts) and reuses `MarpDirectiveDocs`' section table (`docSection`).
- The `MARP_DECK` live template context is false inside alt text (`MarpTemplateContextType`), otherwise the `bg` template
  would expand on Tab in `![bg`.
- Out of scope: inspections for unknown keywords or bad values, completion of values, the color image `![bg](red)`.

## Export (Kotlin and webview)

Package `cz.p3kj.marp.export` (#12): File | Export | Marp Deck to HTML and Marp Deck to PDF (`Marp.ExportHtml`,
`Marp.ExportPdf`, in `FileExportGroup`) and the same two in the `Marp.Export` popup of the preview toolbar. PPTX and images
go through an external Marp CLI process and are a separate path, see "Marp CLI export" at the end of this section (#16).

- Both formats go through the LIVE preview page (`MarpPreviewFileEditor.panel`). It already has the current themes (trust
  rules applied), the effective `html` / `math` options and reaches local images through the resource handler, so there is
  no second or offscreen browser, no Node.js and no Kotlin-side Marp. The price: the preview has to be loaded. The actions
  are shown whenever the deck is open in the Marp editor and the IDE has JCEF (a panel exists). When
  `MarpPreviewPanel.isPageReady` is false the export shows the warning `export.notReady` and stops.
- `MarpExportAction` (`DumbAwareAction`, `BGT`) finds the preview from `PlatformCoreDataKeys.FILE_EDITOR` when that is the
  `MarpSplitEditor` (its `preview` property), otherwise from `FileEditorManager.getSelectedEditor(file)` of
  `CommonDataKeys.VIRTUAL_FILE` (with the caret in the text editor the data key is the inner text editor). The
  `Marp.Export` popup is a plain `<group popup="true">`: a `DefaultActionGroup` without an `update` override is
  dumb-aware in 2026.2, so it is not greyed out while indexing (tests assert `isDumbAware`).
- `MarpExporter.export` (EDT) opens the save dialog (`FileChooserFactory.createSaveFileDialog`, `FileSaverDescriptor` with
  one extension), starting in the folder of the deck with `<deck>.html` / `<deck>.pdf`: relative image paths and theme
  `url()`s of an HTML file resolve from where it is saved, so the deck's folder is the natural default. A missing extension
  is appended (`MarpExportFormat.withExtension`), and an overwrite of the changed name is asked about, because the dialog
  only confirmed the name the user typed. The work then runs in `MarpProjectScope` under `withBackgroundProgress`:
  `MarpPreviewFileEditor.renderNow()` first sends the current editor text and settings to the page (unsaved changes count,
  and the 150 ms throttle cannot leave the page behind), then the format specific part, then a VFS refresh of the file. The result is
  a balloon notification (group `Marp Export`) with an Open action (`BrowserUtil.browse(Path)`), or an error notification.
  `MarpExportException` messages are log text; the user sees `export.error.timeout` (`timedOut`),
  `export.error.pageGone` (`pageGone`: the preview was closed or reloaded meanwhile, or was not ready), `export.error.pdf` or
  `export.error.render`, and for other exceptions (disk errors) their message.
- HTML: `exportHtml` (a bridge command, see Commands above) renders the last `update` with a separate export Marp instance
  (`createExportMarp` in `webview/src/export-html.ts`) and returns a complete document; Kotlin only writes the string. The
  instance uses marp-core's defaults (container `div.marpit`, `inlineSVG`, minified CSS, and the default `script`: marp-core's
  inline helper script that fits headers and scales text), plus the same `html` / `math` mapping (`htmlOption`,
  `mathOption` in `marp-factory.ts`) and the same themes as the preview. It has no line-number or content-section plugins and
  no presenter notes, and a theme that marp-core rejects is skipped (the preview reports it). `title` is not a Marpit
  directive, so it is registered as a custom global directive that adds nothing to the markup, which keeps the value in
  Marpit's protected `lastGlobalDirectives` (read inside a try/catch like the theme probe). The document title is that
  `title:` or the Markdown file's name, and `lang:` becomes the `lang` attribute. `standaloneHtml` escapes the title and the
  language and turns `</style` in the CSS into `<\/style`. A small `@media screen` block stacks the slides on a grey page;
  printing the file from a browser uses marp-core's own print rules. URLs stay as written, like Marp CLI: relative images
  resolve against the saved file, Twemoji (jsDelivr), KaTeX CSS and fonts and Google Fonts stay remote. Nothing is inlined
  or copied.
- PDF: `MarpPreviewPanel.printToPdf` calls `CefBrowser.printToPDF` on the live preview browser (a public JCEF API, on
  `Dispatchers.UI`), after `flushRender` made the page render the current text and wait for its images and fonts.
  Right before the call `printToPdf` checks `bridge.isReady` again (a reload while switching threads would print the
  loading page) and throws `MarpExportException(pageGone = true)` otherwise. The print is registered in `MarpPageReplies`
  like a command (its callback calls `replies.complete`), so a reload, a lost page or closing the preview fails it at once.
  `MarpPdfSettings` sets every field of
  `CefPdfPrintSettings` (the constructor leaves `margin_type` and the strings null): background on, scale 1, no margins, no
  header or footer, `prefer_css_page_size`. marp-core's CSS contains `@page { size: <w>px <h>px; margin: 0 }`, which follows
  the `size` directive (a 4:3 deck gives 960px 720px), and `@media print` rules for one slide per page and exact colors,
  so every slide is one page of the deck's own size. `preview.css` adds `@media print` rules that remove the preview chrome
  (background, scrollbars, slide margins and shadows, the active-slide outline, presenter notes, the error banner and the empty
  hint). The PDF has no outline and no notes. The slide overview grid rules are inside `@media screen`, so a preview that
  is in the overview still prints one slide per page.
- Security: nothing in the preview page changes (CSP, navigation rules, allowed roots). The exported file lives outside the
  CSP, so it holds exactly what the preview renders under the same effective settings: in an untrusted project HTML is off and
  no custom themes are loaded, and with `html: all` in a trusted project the deck's own HTML and scripts stay in the file, like
  Marp CLI `--html`. The only script the export adds is marp-core's helper. Reading the deck's files (images) is limited by
  the same allowed-roots rule as the preview, because the images in a PDF come through the page. Present adds a script
  of its own to the HTML, see below.
- Timeouts (`MarpPreviewPanel`): `exportHtml` 30 s, `flushRender` 10 s, `printToPDF` 120 s. They throw
  `MarpExportException(timedOut = true)`, not `TimeoutCancellationException`, which is a `CancellationException` and would
  look like the user cancelling. `MarpPageReplies.failAll` (page reload, crash, dispose) and every not-ready path set
  `pageGone` instead.
- Not used: `BrowserUtil.browse(File)` (obsolete), the vararg `FileSaverDescriptor` constructor (deprecated),
  `JBCefBrowserBase.isCefBrowserCreated` (internal), `FileEditorManager.getSelectedEditorWithRemotes` and
  `getSelectedEditorFlow` (experimental), `CefBrowser.print()` (system print dialog), a second or offscreen browser.
- Out of scope: inlining or copying local images, the presenter template and notes in the
  exports, a PDF outline, export without a loaded preview.

### Marp CLI export (PPTX, PNG, JPEG)

`Marp.ExportPptx`, `Marp.ExportPng`, `Marp.ExportJpeg` (#16, `MarpCliExporter`) run the user's Marp CLI. They do not use the
preview page and are shown for every Marp editor; the CLI is not looked up in `update()` (a disabled item cannot say why), a
missing one is a warning with an Open Settings action on click.

- Trust: only trusted projects are exported. In an untrusted one a notification says so and nothing starts. The CLI is an
  external program reading the project, and on Windows `marp.cmd` runs through `cmd.exe`, which parses `%`, `&` and `^` in
  repository file names.
- Config bypass: a temporary `marp-config.json` is passed as `--config-file`, so the CLI never loads the project's `.marprc*`,
  `marp.config.*` or `package.json#marp` (no double themes, no repository JavaScript). It holds `themeSet` (absolute paths,
  URL themes as temporary files), `html`, `allowLocalFiles` and `options.math` (the only way to set it). The process runs in
  the temporary folder: every input is absolute and images resolve from the deck file.
- Process (`runMarpCli`): `KillableProcessHandler`, the last 64 KB of output kept, stdin closed at once, 5 minute timeout
  (`withTimeoutOrNull`, null exit code: a `TimeoutCancellationException` would be swallowed as a cancellation). `finally`
  kills the tree (the CLI starts a browser), also on cancel; the folder is deleted in a `NonCancellable` `finally`. Images
  are numbered by the CLI (`deck.001.png`), success needs exit code 0 and that first file. Failure notifications show the
  escaped output tail (notifications are HTML).
- Lookup order (#19, `MarpCliLocator.locate`): the configured `marpCliPath` (wrong means missing, no fallback), then
  `node_modules/.bin/marp` from the deck's folder up to the project base dir (`MarpCliProject`, skipped unless `trusted`,
  so no code path picks a repository binary in an untrusted project), then `marp` on the PATH.
- PATH lookup: `MarpAppSettings.marpCliPath` is IDE level (a path in `.idea/marp.xml` would let a cloned repository pick the
  binary). `MarpCliLocator` has its own search over `EnvironmentUtil.getValue("PATH")`: absolute entries only, executable
  bit outside Windows, `PATHEXT` on Windows (`marp` is `marp.cmd`), because `findExecutableInPathOnAnyOS` is deprecated for
  removal and `findFirst` is experimental. No `npx`. Not verified on Windows; tests use shell scripts (skipped there).

## Present (Kotlin and webview)

`Marp.Present` (#13), a button of the preview toolbar (`Marp.PreviewToolbar`, right before the `Marp.Export` popup, icon
`AllIcons.Actions.Execute`, Find Action synonyms, no default shortcut). It shows the deck in the SYSTEM browser, one
slide at a time, starting at the slide under the caret. It is the HTML export with a `present` argument, so there is no
second browser, no server, no new bridge command and nothing changes in the preview page's CSP, navigation rules or
allowed roots. Package `cz.p3kj.marp.export`, `MarpPresentAction` (`DumbAwareAction`, `BGT`, shown under the same
condition as the export actions) and `MarpPresenter`.

- Flow: `MarpPresenter.present` (EDT) needs the preview panel and, when `isPageReady` is false, shows the `export.notReady`
  warning. It then computes the start slide (below) and, in the project scope under `withBackgroundProgress`, calls
  `MarpPreviewFileEditor.renderNow()` (unsaved changes count), sends `exportHtml` with `MarpPresentOptions(baseHref, start)`
  (same command, timeout and errors as the export), writes the reply with `MarpPresentFiles.write` on `Dispatchers.IO`
  and opens the file with `BrowserUtil.browse(Path)` on `Dispatchers.EDT` (the thread of the export's Open action). There
  is no success notification. Failures (`MarpExportException`, disk errors) are logged and shown with the `Marp Export`
  notification group as `present.failed`, with the reason of `MarpExporter.reason`. `CancellationException` is rethrown.
- Start slide: `MarpPresenter.withStartSlide` reads the deck like `MarpSlideNavigation.withDeck` (non-blocking read action,
  `withDocumentsCommitted`, `finishOnUiThread`, so an uncommitted edit counts and the LIVE caret matches the deck) and
  calls back with `MarpDeck.slideIndexAt(caret offset)`, 0 for a file that is not a Marp deck. It is not `withDeck`
  itself because that skips the callback for a null deck. The start slide is EMBEDDED in the file, because a `#N`
  fragment is dropped when a `file:` URL is opened on Windows and macOS.
- Temp file (`MarpPresentFiles`, plain JDK): `Files.createTempFile("marp-present-", ".html")` (owner-only permissions on
  POSIX), `deleteOnExit()`, UTF-8. A new file for every Present, so a tab that is still open keeps working; they are
  deleted when the IDE exits (a crash leaves them in the temp folder). Not used: a file next to the deck (stray files in
  VCS, overwrites), `FileUtil` temp helpers, the built-in web server (`localhost:63342`, not a plugin API),
  `BrowserUtil.browse(File)` (obsolete), `browse(String)` with a fragment.
- Base: the document gets `<base href="file:///<deck folder>/">` (`MarpPresentFiles.baseHref`, `Path.toUri()` with a
  trailing `/` forced; folder from `VirtualFile.fileSystem.getNioPath`, no base when the deck is not on the local file
  system), so relative images and theme `url()`s resolve from the temp folder as they do from the deck. The base is in
  the presentation file only (`standaloneHtml` `baseHref`, emitted right after the charset, before the style), the plain
  export is byte-identical to before. It only changes where relative URLs resolve to where the export lands by default,
  and a `file:` page can already reference any file URL.
- Presentation page (`webview/src/present.ts`, `exportDocument(..., present)`): `PRESENT_CSS` replaces `EXPORT_CSS`:
  inside `@media screen` every `div.marpit > svg[data-marpit-svg]` is `position: fixed; inset: 0` at full size (the SVG
  viewBox letterboxes and centers it) and only the one with `marp-present-active` is displayed, the others are
  `display: none` (not `visibility`, a theme could override it). Printing keeps marp-core's print rules (one slide per
  page). marp-core's helper script stays in, its elements re-fit through ResizeObserver when a slide becomes visible.
- Script: `runPresentation(document, window, start)` is serialized into a `<script>` at the end of the body
  (`presentScript`, `String(runPresentation)`), so it must not reference anything outside its own body, and a test
  evaluates the serialized text on its own. It prefers a valid `#N` in the URL (a reload keeps the slide) and otherwise
  uses the embedded `start`, clamped to the slide count. The hash follows the slide through `history.replaceState` with
  an ABSOLUTE URL (a relative one would resolve against the base and leave the file), inside a try/catch.
  Keys: next = ArrowRight, ArrowDown, PageDown, Space, Enter, previous = ArrowLeft, ArrowUp, PageUp, Backspace,
  Shift+Space, Home, End, `f` / `F` toggle full screen (optional calls, rejected promises ignored, Esc is the browser's).
  Keys with Ctrl, Meta or Alt, already prevented ones and keys in `input`, `textarea`, `select` or contenteditable are
  left alone, and so is Enter on a focused link or button (it activates it). Clicks on links whose `href` starts with `#` are taken over: with the base, a plain `#3` would navigate to
  the deck's folder. The script finds the element (Marpit gives every section `id="N"`, headings have ids too) and shows
  the slide that holds it. There is no click-to-advance: the click that focuses the browser window would skip the start
  slide.
- Security and trust: same content rules as the HTML export (untrusted project: HTML off and no custom themes; `html: all`
  in a trusted project keeps the deck's scripts). The only script added is `runPresentation`, which does no network
  access and no `fetch`.
- Limits: browsers installed as Snap or Flatpak cannot read the temp folder (README, troubleshooting). Out of scope:
  presenter view and notes, timers, remote control, click, touch and swipe navigation, on-screen controls, transitions, a
  slide counter, live reload of the browser, a JCEF presentation window, deleting files before the IDE exits. Not used:
  a second or offscreen JCEF browser (its own resource handler, CSP, navigation policy, focus and per-OS full screen),
  `GraphicsDevice.setFullScreenWindow`.

## Threading and lifecycle rules

- No blocking work on the EDT; document text read in read actions; file I/O and HTTP on `Dispatchers.IO`.
- Services are light `@Service` classes with an injected `CoroutineScope`; editors and panels are `Disposable` and
  registered with `Disposer`; message bus connections are tied to a disposable or scope.
- `MarpPreviewPanel` starts as an empty placeholder and creates its `JBCefBrowser` in a coroutine: first
  `MarpJcefStartup.prepare()` reads `ProxySettings.getProxyConfiguration()` on `Dispatchers.IO` (once per IDE session),
  then the browser is created on `Dispatchers.UI` (the EDT without the write-intent lock, any modality) and added to
  the placeholder. Page reloads also run on `Dispatchers.UI`; only calls that touch the editor (`revealLine`,
  `didClick`) and `BrowserUtil.browse` (which may show a dialog) run on `Dispatchers.EDT`. Bridge calls made before that are
  only recorded and replayed on `ready`, as during a reload. Reason: the first browser of the session starts JCEF,
  and `JBCefApp`'s class initializer reads the proxy settings. If the platform has not read them yet (it does so on its
  first HTTP request, usually but not always before editors are restored at startup), creating the proxy settings
  service requests `ProxyMigrationService` from inside that class initializer, and the platform reports a SEVERE
  "`JBCefApp$Holder <clinit> requests ... ProxyMigrationService instance`" blaming the plugin that created the browser.
  If the browser cannot be created, the placeholder shows the "JCEF not available" text.
- Export commands wait for the page's `reply` in `MarpPageReplies` (`CompletableDeferred` per id). `onLoadStart`, `onPageGone`
  and the dispose hook fail every waiting command, so an export never waits for a page that is gone. `printToPDF` is called
  on `Dispatchers.UI` and its callback completes the same kind of deferred (`replies.complete`); nothing blocks the EDT.
- Everything is `DumbAware`. No components. Apart from the class described under Platform API note, no `@Internal` /
  deprecated / experimental APIs, so the plugin stays dynamic. `verifyPlugin` in CI checks this against the recommended
  IDEs and the next EAP, and fails on any finding (`failureLevel = ALL`). The settings page's list toolbar still gets
  `AnActionButton`s from `ToolbarDecorator.setAddAction` / `setEditAction` (the class is `@ApiStatus.Obsolete`, the
  methods are not); the add menu itself is a plain `DumbAwareAction` popup.
- Marp detection (`MarpDetector`) looks at the first 64 K characters only: the front matter has to end within them.
  The front matter regular expression of marp-vscode is matched by a linear scanner (a backtracking engine needs
  seconds for `---` followed by many blank lines, and detection runs in read actions); the editor banner only checks
  loaded documents, never the disk, and document edits past that offset do not trigger a new check.
- All user-visible strings in `messages/MarpBundle.properties`.

## Tests

- Kotlin (`src/test`): light platform tests based on `MarpLightTestCase` (editor provider, settings page and settings,
  theme service including trust, `.marprc` confinement and download limits), plus plain unit tests for logic that does
  not need the platform: `MarpDetector`, `MarpSlideSplitter` and `MarpHeadingDivider` (slide splitting, tricky cases by
  hand-built blocks; `MarpSlideParserTest` and `MarpStructureViewTest` cover the PSI adapter and the tree), the slide navigation
  (actions, Go to Slide with a replaced test input dialog, status bar widget, inlay hints called through a recording sink), slide reordering (`MarpSlideReorderTest` for the pure swap, `MarpSlideReorderingTest` for the actions, undo and caret, `MarpSlideStatementMoverTest` for Move Statement through the editor actions; the tight-deck cases use real parsed decks, including a `# A` directly above `---` and two `---` lines in a row, see "Slide model"), slide folding (regions from the builder, and the real fold model after the initial folding pass next to the Markdown regions), `MarpBridgeState` (state setters and commands), `MarpPageReplies`, `MarpPdfSettings`, `MarpExportFormat`, `MarpScrollEchoGuard`, `MarpLinkPolicy` (and request
  routing), `MarpResourcePaths`, `MarpJcefStartup`, the `.marprc` parser, `MarpThemePaths`, `MarpThemeFolder`,
  `MarpThemeWatch`, `MarpThemeNames` and the theme URL validation of the settings page. The directive features have plain
  unit tests for the catalog and the comment parser, and light platform tests for highlighting, completion (including
  the automatic popup with `CompletionAutoPopupTester`), documentation and the inspection. The image syntax has a
  plain unit test for the catalog and the alt text finder and light platform tests for completion, the automatic popup
  and documentation.
- Export: `MarpExportActionsTest` (both actions registered, in `FileExportGroup` and in the toolbar popup, hidden without a
  Marp editor, the preview found from a split editor). Headless tests have no JCEF page, so there is no end-to-end test of the
  save dialog, the HTML file or the PDF: see the manual checks in the pull request.
- Present: `MarpPresentActionTest` (action registered, toolbar position, hidden without a Marp editor, start slide from
  the caret through `MarpPresenter.withStartSlide`), `MarpPresentFilesTest` (base href, temp file), `MarpPresentOptionsTest`
  (JSON). There is no end-to-end test of opening the browser.
- Slide overview: `MarpPreviewToolbarActionsTest` (the toggle is registered in the toolbar group, per editor, hidden
  without a Marp editor) and `MarpBridgeStateTest` (`setOverview` replayed last). Headless tests have no JCEF page, so the
  flag in `MarpPreviewFileEditor` is what they check.
- Webview (`webview/test`, vitest with jsdom): the host bridge and message queue, marp-core plugins and render options,
  scroll sync, active slide, link and click handling, the unknown-theme warning and the string table, the standalone export
  document (`export-html.test.ts`), the `exportHtml` / `flushRender` commands with their replies (`preview.test.ts`) and the
  presentation runtime (`present.test.ts`: keys, hash, start slide, anchor links, full screen, and the serialized script),
  and the slide overview (`overview.test.ts` for the click target, the `slide overview` block of `preview.test.ts` for the
  class, clicks, inert links and suspended scroll sync; the grid layout itself is CSS and needs a manual check).
- `./gradlew check` runs all of them plus `tsc --noEmit` and the NOTICE freshness check. `verifyPlugin` covers binary
  compatibility with the recommended IDEs and the next EAP.

## Dev page

`npm run dev` in `webview/` runs `webview/dev/serve.mjs`: esbuild in watch mode plus a small server on
`http://127.0.0.1:5173/` with a mock IDE host (`window.__marpHost`). Query parameters: `deck` (absolute path of a
Markdown file), `themes` (comma-separated absolute CSS paths), `dark=1`, and the render options such as `html` and
`math`. The page gets the production CSP (with `'self'` for scripts and `connect-src 'self'` for the mock host). Set
`CSP=0` in the environment to disable it. `notes=1` turns presenter notes on, `overview=1` the slide overview. The
server only answers requests whose `Host` is `localhost` or `127.0.0.1` (DNS rebinding). Messages the page sends to the
host are collected in `window.__hostLog`.
