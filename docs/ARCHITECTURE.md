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
| `https://marp.localhost/doc/<absolute path>` | local file. Path uses `/` separators, each segment percent-encoded; Windows drive paths look like `/doc/C:/Users/...`. Only served when the canonical file is inside the project base dir, a project content root, or the directory of the Markdown file being previewed; otherwise 404. |

The document's base href is `https://marp.localhost/doc/<markdown file dir>/`, so relative images, `![bg](...)` and
links resolve to local files.

## JS bridge (Kotlin -> JS)

The page defines `window.marpBridge`. Kotlin calls it with `executeJavaScript` and JSON-encoded arguments, only after
the page sent `ready`. Line numbers are 0-based editor lines; fractional values mean "part way into that line".

```ts
interface MarpBridge {
  /** Replace custom themes. errors: Kotlin-side problems (missing file, download failed) shown in the preview. */
  setThemes(arg: { themes: { source: string; css: string }[]; errors: string[] }): void
  /** Render. Called on load and on every (debounced) document change. */
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

Rendering follows marp-vscode's preview options: `container: {tag:'div', id:'marp-preview'}`,
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
| `{"type":"ready"}` | bridge is installed; Kotlin sends `setIdeTheme`, `setThemes`, `update`, `scrollToLine`, `setActiveLine` |
| `{"type":"revealLine","line":n}` | the user scrolled the preview; scroll the editor so fractional line `n` is at the top |
| `{"type":"didClick","line":n}` | double-click in a slide; move the caret to line `n` and focus the editor |
| `{"type":"openLink","href":"..."}` | a link was clicked (the page always prevents navigation). `https://marp.localhost/doc/...` -> open that file in the IDE; `http(s)`/`mailto` -> `BrowserUtil.browse` |
| `{"type":"error","message":"..."}` | render/theme error, already shown in the preview; Kotlin logs it |

## Kotlin contracts

- `MarpSettings` (project service, `.idea/marp.xml`): `themes`, `useMarprcThemeSet`, `html`, `math`, `scrollSync`;
  `update { }` publishes `MarpSettingsListener.TOPIC`.
- `MarpThemeService` (project service): `suspend fun loadThemes(): MarpThemeSet` (cached, never on EDT);
  publishes `MarpThemeListener.TOPIC` when watched theme files, folders or `.marprc` change, or theme settings change.
- Theme sources: settings `themes` entries (file / folder = all `*.css` directly inside / `http(s)` URL; relative
  to the project dir) plus, when enabled, `themeSet` from `.marprc.yml` / `.marprc.yaml` / `.marprc.json` in the
  project root (string or list; relative to the `.marprc` file). Remote URLs: `HttpRequests`, 5 s timeout, cached for
  the session until settings change.
- Theme CSS is sent as text; marp-core's `themeSet.add(css)` picks it up by its `@theme` name. Relative `url()`
  inside theme CSS resolve against the document base (same as marp-vscode).

## Threading and lifecycle rules

- No blocking work on the EDT; document text read in read actions; file I/O and HTTP on `Dispatchers.IO`.
- Services are light `@Service` classes with an injected `CoroutineScope`; editors and panels are `Disposable` and
  registered with `Disposer`; message bus connections are tied to a disposable or scope.
- Everything is `DumbAware`. No components, no `@Internal` / deprecated / experimental APIs, so the plugin stays
  dynamic and passes the Plugin Verifier clean.
- All user-visible strings in `messages/MarpBundle.properties`.
