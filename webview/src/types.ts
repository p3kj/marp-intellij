export type HtmlMode = 'off' | 'default' | 'all'
export type MathMode = 'mathjax' | 'katex' | 'off'

export interface RenderOptions {
  html: HtmlMode
  math: MathMode
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
}

export interface MarpBridge {
  setStrings(arg: PreviewStrings): void
  setThemes(arg: { themes: ThemeInput[]; errors: string[] }): void
  update(arg: { markdown: string; baseHref: string; options: RenderOptions }): void
  scrollToLine(line: number): void
  setActiveLine(line: number): void
  setIdeTheme(arg: { dark: boolean; background: string; foreground: string }): void
}

export type HostMessage =
  | { type: 'ready' }
  | { type: 'revealLine'; line: number }
  | { type: 'didClick'; line: number }
  | { type: 'openLink'; href: string }
  | { type: 'error'; message: string }
