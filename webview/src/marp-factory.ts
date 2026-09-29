import { Marp } from '@marp-team/marp-core'
import contentSection from './content-section'
import lineNumber from './line-number'
import type { RenderOptions, ThemeInput } from './types'

/** A custom theme marp-core rejected; formatted for the banner with the current strings. */
export interface ThemeError {
  source: string
  message: string
}

export interface MarpBuild {
  marp: Marp
  errors: ThemeError[]
}

/** Same options as marp-vscode's preview, see docs/ARCHITECTURE.md. */
export function createMarp(options: RenderOptions, themes: ThemeInput[]): MarpBuild {
  const marp = new Marp({
    // Underscores keep the id apart from heading slugs (`# Marp Preview` gets id="marp-preview"),
    // like marp-vscode's `__marp-vscode`.
    container: { tag: 'div', id: '__marp-preview' },
    slideContainer: { tag: 'div', 'data-marp-slide-wrapper': '' },
    html: options.html === 'off' ? false : options.html === 'all' ? true : undefined,
    inlineSVG: { backdropSelector: false },
    math: options.math === 'off' ? false : options.math,
    minifyCSS: false,
    script: false,
  })
  marp.use(lineNumber).use(contentSection)

  const errors: ThemeError[] = []
  for (const theme of themes) {
    try {
      marp.themeSet.add(theme.css)
    } catch (e) {
      errors.push({ source: theme.source, message: e instanceof Error ? e.message : String(e) })
    }
  }
  return { marp, errors }
}

/** Marp is rebuilt only when this key changes. */
export function marpKey(options: RenderOptions, themes: ThemeInput[]): string {
  return JSON.stringify([options.html, options.math, themes])
}
