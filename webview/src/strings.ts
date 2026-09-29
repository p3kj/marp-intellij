import type { PreviewStrings } from './types'

/** English texts, used until Kotlin sends the IDE's localized ones with `setStrings`. */
export const defaultStrings: PreviewStrings = {
  dismiss: 'Dismiss',
  themeError: 'Theme {0}: {1}',
  renderError: 'Render failed: {0}',
  unknownTheme: "Theme '{0}' is not recognized. Add it in Settings | Tools | Marp or to themeSet in .marprc.yml.",
  emptyDeck: 'No slides yet. Add content after the front matter, separate slides with ---.',
}

/** Replaces `{0}`, `{1}`... with `args` (placeholders without an argument stay as they are). */
export function formatMessage(template: string, ...args: string[]): string {
  return template.replace(/\{(\d+)\}/g, (match, index: string) => args[Number(index)] ?? match)
}

/** Keeps the defaults for missing or non-string fields, so a partial or malformed argument cannot blank the texts. */
export function mergeStrings(next: Partial<Record<keyof PreviewStrings, unknown>> | null | undefined): PreviewStrings {
  const result = { ...defaultStrings }
  for (const key of Object.keys(defaultStrings) as (keyof PreviewStrings)[]) {
    const value = next?.[key]
    if (typeof value === 'string' && value.length > 0) result[key] = value
  }
  return result
}
