import { Marp } from '@marp-team/marp-core'
import { htmlOption, mathOption } from './marp-factory'
import type { RenderOptions, ThemeInput } from './types'

/**
 * Only used when the exported file is opened on a screen: a grey page with the slides stacked and centered. Printing it
 * from a browser uses marp-core's own `@media print` rules (one slide per page).
 */
export const EXPORT_CSS =
  '@media screen { html { background: #e8e8e8 } body { margin: 0; padding: 16px 0 } ' +
  'div.marpit > svg[data-marpit-svg] { display: block; width: calc(100% - 32px); max-width: 1280px; height: auto; ' +
  'margin: 0 auto 16px; box-shadow: 0 2px 8px rgba(0, 0, 0, .3) } }'

/**
 * A Marp instance for exports: marp-core's defaults (container `div.marpit`, inline SVG, minified CSS and the inline
 * helper script that fits headers and scales text), with the `html` and `math` options and the themes of the preview.
 * No line-number or content-section plugins, no presenter notes. A theme marp-core rejects is skipped: the preview
 * already shows that error.
 */
export function createExportMarp(options: RenderOptions, themes: ThemeInput[]): Marp {
  const marp = new Marp({ ...htmlOption(options.html), math: mathOption(options.math) })
  // `title` is not a Marpit directive, so Marpit forgets it. Registering it keeps the value in `lastGlobalDirectives`
  // and leaves the markup alone (the directive adds no attribute to the slides).
  marp.customDirectives.global['title'] = (value) => (typeof value === 'string' ? { title: value } : {})
  for (const theme of themes) {
    try {
      marp.themeSet.add(theme.css)
    } catch {
      // Reported by the preview.
    }
  }
  return marp
}

const ESCAPES: Record<string, string> = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }
const escapeHtml = (text: string): string => text.replace(/[&<>"']/g, (c) => ESCAPES[c] ?? c)

export interface StandaloneInput {
  title: string
  lang?: string | undefined
  /** marp-core's CSS. */
  css: string
  /** marp-core's HTML. */
  html: string
}

/** A complete HTML document around a rendered deck. */
export function standaloneHtml({ title, lang, css, html }: StandaloneInput): string {
  // `</style` would end the element early. A backslash before the slash is a valid escape in CSS strings and harmless elsewhere.
  const safeCss = `${css}\n${EXPORT_CSS}`.replace(/<\/(style)/gi, '<\\/$1')
  return (
    '<!DOCTYPE html>' +
    `<html${lang ? ` lang="${escapeHtml(lang)}"` : ''}><head><meta charset="utf-8">` +
    '<meta name="viewport" content="width=device-width, initial-scale=1">' +
    `<title>${escapeHtml(title)}</title><style>${safeCss}</style></head><body>${html}</body></html>\n`
  )
}

/** The last non-empty string of a global directive Marpit recorded while rendering (`lastGlobalDirectives` is protected). */
function globalString(marp: Marp, name: string): string | undefined {
  try {
    const value = (marp as unknown as { lastGlobalDirectives?: Record<string, unknown> }).lastGlobalDirectives?.[name]
    return typeof value === 'string' && value.trim() !== '' ? value.trim() : undefined
  } catch {
    return undefined
  }
}

/**
 * Renders `markdown` with an export Marp instance into a standalone HTML document. The title is the `title:` directive
 * or, without one, `fallbackTitle`; `lang:` becomes the `lang` attribute. Throws what marp-core throws.
 */
export function exportDocument(markdown: string, options: RenderOptions, themes: ThemeInput[], fallbackTitle: string): string {
  const marp = createExportMarp(options, themes)
  const { html, css } = marp.render(markdown)
  return standaloneHtml({
    title: globalString(marp, 'title') ?? fallbackTitle,
    lang: globalString(marp, 'lang'),
    css,
    html,
  })
}
