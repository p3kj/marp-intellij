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
  /**
   * Names given to `theme:` directives during the last render that no registered theme has. Marpit drops such a
   * directive silently (falls back to the default theme), so the raw values are recorded while it parses them.
   * Values are the YAML-decoded ones, so quotes are already gone; theme names are case-sensitive.
   */
  unknownThemes(): string[]
}

/** Marpit's (internal) core rule that reads global directives, `theme` among them. */
const globalDirectivesRule = 'marpit_directives_global_parse'
let probeWarned = false

/** Same options as marp-vscode's preview, see docs/ARCHITECTURE.md. */
export function createMarp(options: RenderOptions, themes: ThemeInput[]): MarpBuild {
  const marp = new Marp({
    // Underscores keep the id apart from heading slugs (`# Marp Preview` gets id="marp-preview"),
    // like marp-vscode's `__marp-vscode`.
    container: { tag: 'div', id: '__marp-preview' },
    slideContainer: { tag: 'div', 'data-marp-slide-wrapper': '' },
    // Leaving `html` out keeps marp-core's default allowlist.
    ...(options.html === 'off' ? { html: false } : options.html === 'all' ? { html: true } : {}),
    inlineSVG: { backdropSelector: false },
    math: options.math === 'off' ? false : options.math,
    minifyCSS: false,
    script: false,
  })
  marp.use(lineNumber).use(contentSection)

  // Marpit asks `themeSet.has(value)` for every `theme` directive while parsing global directives (and again for
  // `@import` in the theme CSS, which must not count), so record the lookups only between these two rules.
  let probing = true
  let recording = false
  let unknown = new Set<string>()
  const has = marp.themeSet.has.bind(marp.themeSet)
  marp.themeSet.has = (name: string) => {
    const found = has(name)
    if (probing && recording && !found) unknown.add(String(name))
    return found
  }
  marp.use((md: any) => {
    // The rule name is Marpit's, not public API: should an update rename it, `ruler.before` throws "Parser rule not
    // found". Losing the unknown-theme warning is fine, failing every render is not.
    try {
      md.core.ruler.before(globalDirectivesRule, 'marp_intellij_theme_probe_start', () => {
        recording = true
        unknown = new Set()
      })
      md.core.ruler.after(globalDirectivesRule, 'marp_intellij_theme_probe_end', () => {
        recording = false
      })
    } catch (e) {
      probing = false
      if (!probeWarned) {
        probeWarned = true
        console.warn(`Marp Preview: unknown-theme warnings are off, ${globalDirectivesRule} not found:`, e)
      }
    }
  })

  const errors: ThemeError[] = []
  for (const theme of themes) {
    try {
      marp.themeSet.add(theme.css)
    } catch (e) {
      errors.push({ source: theme.source, message: e instanceof Error ? e.message : String(e) })
    }
  }
  return { marp, errors, unknownThemes: () => [...unknown] }
}

/**
 * Marp is rebuilt only when this key changes. Only the options that configure the Marp instance count: `notes` just
 * changes what the page inserts next to the rendered slides.
 */
export function marpKey(options: RenderOptions, themes: ThemeInput[]): string {
  return JSON.stringify([options.html, options.math, themes])
}
