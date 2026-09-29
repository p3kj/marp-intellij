# Architecture

Marp Preview renders Marp decks with [marp-core](https://github.com/marp-team/marp-core) inside JCEF (the IDE's
Chromium). The plugin owns its split editor; it does not plug into the bundled Markdown preview (that has no stable
renderer API).

```
Markdown file with `marp: true`
  -> MarpSplitEditorProvider (HIDE_OTHER_EDITORS)
     -> TextEditorWithPreview(text editor, MarpPreviewFileEditor)
        -> MarpPreviewPanel (JBCefBrowser)
           loads https://marp.localhost/app/index.html
           Kotlin -> JS: window.marpBridge.*(json)
           JS -> Kotlin: one JBCefJSQuery, JSON messages
```

## Source layout

| Path | Owner / purpose |
|---|---|
| `webview/` | npm project: preview page, marp-core bundle (esbuild), vitest tests |
| `cz.p3kj.marp.MarpDetector` | front-matter `marp: true` detection |
| `cz.p3kj.marp.editor` | file editor provider, split editor, preview file editor |
| `cz.p3kj.marp.preview` | JCEF panel, JS bridge, resource request handler |
| `cz.p3kj.marp.sync` | editor <-> preview scroll sync, caret -> active slide |
| `cz.p3kj.marp.notifications` | "Marp deck detected, reopen with preview" banner |
| `cz.p3kj.marp.settings` | project settings (`.idea/marp.xml`), settings page |
| `cz.p3kj.marp.themes` | theme resolution, reading, URL cache, VFS watching |

## Build wiring

`buildWebview` (Gradle `Exec`) runs `npm ci` + `node build.mjs --outdir=build/generated/webview/webview`. That
directory is a resources source dir, so the jar contains `/webview/index.html`, `/webview/marp-preview.js`,
`/webview/marp-preview.css`. `testWebview` (vitest) runs as part of `check`.

## URLs served inside JCEF

A `CefRequestHandler` on the preview browser answers every request to host `marp.localhost`; everything else goes to
the network as usual (remote images, Google Fonts `@import`, CDN fonts).

| URL | Served from |
|---|---|
| `https://marp.localhost/app/<name>` | plugin classpath `/webview/<name>` |
| `https://marp.localhost/doc/<absolute path>` | local file. Path uses `/` separators, each segment percent-encoded; Windows drive paths look like `/doc/C:/Users/...`, UNC paths keep an empty first segment: `\\server\share\x` is `/doc//server/share/x` (only valid on Windows). Only served when the canonical file is inside the project base dir, a project content root, or the directory of the Markdown file being previewed (recomputed on every render; a content root change re-renders); otherwise 404. |

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

  Only the bundled `/app/` script runs; inline `<script>`, event handler attributes, `javascript:` URLs, frames,
  plugins, forms and `fetch` are blocked. Theme CSS (injected as a `<style>` text), marp-core's inline styles, KaTeX /
  Google Fonts `@import`s, remote and local images, fonts and media keep working. Kotlin's `executeJavaScript` and the
  `JBCefJSQuery` function are not subject to the page CSP.
- The main frame only ever shows `https://marp.localhost/app/...` (initial load and reloads). `onBeforeBrowse` cancels
  every other main-frame navigation (`about:blank`, `data:`, `file:`, `<meta http-equiv=refresh>` targets, links that
  escape the page's click handler); user-initiated ones to `http(s)` / `mailto` URLs go to the `openLink` rules below.
  JCEF's error page is disabled (it would be such a navigation).
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
listed under `ready` below. When the renderer process dies the page is reloaded, at most 3 times in a row (a page that
stayed up for 10 s starts a new row).

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
  }): void
  /** Replace custom themes. errors: Kotlin-side problems (missing file, download failed) shown in the preview. */
  setThemes(arg: { themes: { source: string; css: string }[]; errors: string[] }): void
  /** Render. Called on load and on document changes (throttled, at most every 150 ms while typing). */
  update(arg: {
    markdown: string
    baseHref: string            // https://marp.localhost/doc/<dir>/
    options: { html: 'off' | 'default' | 'all'; math: 'mathjax' | 'katex' | 'off' }
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
`math`. Front-matter `math:` wins over the setting, as in marp-core. After each render the page calls `browser()`
update from `@marp-team/marp-core/browser` (fitting headers, auto-scaling).

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

- `MarpSettings` (project service, `.idea/marp.xml`): `themes`, `useMarprcThemeSet`, `html`, `math`, `scrollSync`;
  `update { }` publishes `MarpSettingsListener.TOPIC` when the block changed anything.
- `MarpThemeService` (project service): `suspend fun loadThemes(): MarpThemeSet` (cached, never on EDT);
  publishes `MarpThemeListener.TOPIC` when watched theme files, folders or `.marprc` change, or theme settings change.
- Theme sources: settings `themes` entries (file / folder = all `**/*.css` inside, recursive and skipping `node_modules`
  like marp-cli / `http(s)` URL; relative to the project dir) plus, when enabled, `themeSet` from `.marprc.yml` / `.marprc.yaml` / `.marprc.json` in the
  project root (string or list; relative to the `.marprc` file). Remote URLs: `HttpRequests`, 5 s connect + 5 s read
  timeout, all URLs of one load downloaded in parallel (theme order stays the entry order); successful downloads are
  cached until settings change, failed ones for 60 s (or until settings change).
- Untrusted projects (`TrustedProjects.isProjectTrusted` is false), same as marp-vscode's restricted mode: `html` is
  always `off`; only local file / folder entries from the settings are loaded, URL entries and the `.marprc` `themeSet`
  are skipped and one line in `errors` says so. `MarpThemeService` invalidates and publishes `MarpThemeListener.TOPIC`
  on `TrustedProjectsListener` trust changes; open previews re-request themes and re-render.
- Theme CSS is sent as text; marp-core's `themeSet.add(css)` picks it up by its `@theme` name. Relative `url()`
  inside theme CSS resolve against the document base (same as marp-vscode).

## Threading and lifecycle rules

- No blocking work on the EDT; document text read in read actions; file I/O and HTTP on `Dispatchers.IO`.
- Services are light `@Service` classes with an injected `CoroutineScope`; editors and panels are `Disposable` and
  registered with `Disposer`; message bus connections are tied to a disposable or scope.
- Everything is `DumbAware`. No components, no `@Internal` / deprecated / experimental APIs, so the plugin stays
  dynamic and passes the Plugin Verifier clean.
- All user-visible strings in `messages/MarpBundle.properties`.
