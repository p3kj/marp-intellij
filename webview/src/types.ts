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

export interface MarpBridge {
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
