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
| `webview/` | npm project: preview page, marp-core bundle (esbuild), vitest tests |
| `cz.p3kj.marp.MarpDetector` | front-matter `marp: true` detection, and where the front matter starts and ends |
| `cz.p3kj.marp.slides` | slide model: Markdown PSI -> blocks (`MarpSlideParser`), Marp's split rules (`MarpSlideSplitter`, `MarpHeadingDivider`), `MarpDeck` / `MarpSlide` |
| `cz.p3kj.marp.structure` | slide outline for the Structure tool window and the File Structure popup |
| `cz.p3kj.marp.directives` | directives in comments and the front matter: catalog of the Marp directives, comment and front matter parser, highlighting and color page (comments), completion, documentation, inspection |
| `cz.p3kj.marp.editor` | file editor provider, split editor, preview file editor, preview toolbar actions (group `Marp.PreviewToolbar`) |
| `cz.p3kj.marp.preview` | JCEF panel, JS bridge, resource request handler |
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

Every method is a state setter. `MarpBridgeState` keeps the latest argument per method; calls made while the page is
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
| `{"type":"ready"}` | bridge is installed; Kotlin sends `setStrings`, `setIdeTheme`, `setThemes`, `update`, `scrollToLine`, `setActiveLine` |
| `{"type":"revealLine","line":n}` | the user scrolled the preview; scroll the editor so fractional line `n` is at the top |
| `{"type":"didClick","line":n}` | double-click in a slide; move the caret to line `n` and focus the editor |
| `{"type":"openLink","href":"..."}` | a link was clicked (the page always prevents navigation). `https://marp.localhost/doc/...` inside the allowed roots -> open that file in the IDE; `http(s)`/`mailto` -> `BrowserUtil.browse`; anything else ignored (see Page security) |
| `{"type":"error","message":"..."}` | render/theme error, already shown in the preview; Kotlin logs it |

## Kotlin contracts

- `MarpSettings` (project service, `.idea/marp.xml`): `themes`, `useMarprcThemeSet`, `html`, `math`;
  `update { }` publishes `MarpSettingsListener.TOPIC` when the block changed anything.
- `MarpAppSettings` (application service, `options/marp.xml`, settings category Tools): `scrollSync` and
  `presenterNotes`, personal preferences that must not travel with `.idea/marp.xml`; `update { }` publishes `MarpAppSettingsListener.TOPIC` on the
  application bus. Settings | Tools | Marp (`MarpConfigurable`) edits both.
- `MarpThemeService` (project service): `suspend fun loadThemes(): MarpThemeSet` (cached, never on EDT);
  publishes `MarpThemeListener.TOPIC` when watched theme files, folders or `.marprc` change, or theme settings change.
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
starts), the headings of each slide, and `slideIndexAt(offset)`. Features that need slides use it: the Structure view
today, slide navigation and folding later.

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
  quiet. Unknown theme names are not checked, the preview page warns about them.
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
    injects YAML into it. Completion, completion confidence and documentation are then called with the injected YAML
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
    duplicate keys and YAML syntax errors (the YAML plugin reports them), unknown theme names, completion popup docs.

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
  hand-built blocks; `MarpSlideParserTest` and `MarpStructureViewTest` cover the PSI adapter and the tree), `MarpBridgeState`, `MarpScrollEchoGuard`, `MarpLinkPolicy` (and request
  routing), `MarpResourcePaths`, `MarpJcefStartup`, the `.marprc` parser, `MarpThemePaths`, `MarpThemeFolder`,
  `MarpThemeWatch`, `MarpThemeNames` and the theme URL validation of the settings page. The directive features have plain
  unit tests for the catalog and the comment parser, and light platform tests for highlighting, completion (including
  the automatic popup with `CompletionAutoPopupTester`), documentation and the inspection.
- Webview (`webview/test`, vitest with jsdom): the host bridge and message queue, marp-core plugins and render options,
  scroll sync, active slide, link and click handling, the unknown-theme warning and the string table.
- `./gradlew check` runs all of them plus `tsc --noEmit` and the NOTICE freshness check. `verifyPlugin` covers binary
  compatibility with the recommended IDEs and the next EAP.

## Dev page

`npm run dev` in `webview/` runs `webview/dev/serve.mjs`: esbuild in watch mode plus a small server on
`http://127.0.0.1:5173/` with a mock IDE host (`window.__marpHost`). Query parameters: `deck` (absolute path of a
Markdown file), `themes` (comma-separated absolute CSS paths), `dark=1`, and the render options such as `html` and
`math`. The page gets the production CSP (with `'self'` for scripts and `connect-src 'self'` for the mock host). Set
`CSP=0` in the environment to disable it. `notes=1` turns presenter notes on. The server only answers requests whose
`Host` is `localhost` or `127.0.0.1` (DNS rebinding). Messages the page sends to the host are collected in `window.__hostLog`.
