export type HtmlMode = 'off' | 'default' | 'all'
export type MathMode = 'mathjax' | 'katex' | 'off'

export interface RenderOptions {
  html: HtmlMode
  math: MathMode
  /** Presenter notes (a slide's HTML comments that are not directives) under each slide. Absent means off. */
  notes?: boolean
}

export interface ThemeInput {
  source: string
  css: string
}

/** User-visible texts of the page, from the IDE's resource bundle. `{0}`, `{1}` are placeholders. */
export interface PreviewStrings {
  /** Tooltip and accessible name of the error banner's close button. */
  dismiss: string
  /** A custom theme failed to load: `{0}` theme source, `{1}` error message. */
  themeError: string
  /** marp-core threw while rendering: `{0}` error message. */
  renderError: string
  /** A `theme:` directive names a theme that is neither built in nor registered: `{0}` the name. */
  unknownTheme: string
  /** Hint under the preview when the deck has no content yet (an empty file, or front matter only). */
  emptyDeck: string
}

export interface MarpBridge {
  setStrings(arg: PreviewStrings): void
  setThemes(arg: { themes: ThemeInput[]; errors: string[] }): void
  update(arg: { markdown: string; baseHref: string; options: RenderOptions }): void
  scrollToLine(line: number): void
  setActiveLine(line: number): void
  setIdeTheme(arg: { dark: boolean; background: string; foreground: string }): void
  /**
   * Command, not a state setter: renders the last `update` with an export Marp instance and replies with the complete
   * standalone HTML document (`html`), or with an `error`. `title` is the fallback when the deck has no `title:`.
   */
  exportHtml(arg: { id: number; title: string }): void
  /** Command: renders a pending update right now, then replies once the frame is done and the fonts and images have loaded (at most 5 s), `error` when the render failed. */
  flushRender(arg: { id: number }): void
}

export type HostMessage =
  | { type: 'ready' }
  | { type: 'revealLine'; line: number }
  | { type: 'didClick'; line: number }
  | { type: 'openLink'; href: string }
  | { type: 'error'; message: string }
  /** The one answer to a command (`exportHtml`, `flushRender`) with the same `id`. */
  | { type: 'reply'; id: number; html?: string; error?: string }
