import { dataStartLine } from './content-section'

/** Class on `#marp-root` while the slide overview (thumbnail grid) is on, see `setOverview` in preview.ts. */
export const overviewClass = 'marp-overview'

/**
 * Content start line of the slide that `target` is in (the slide's `data-marp-content-start-line`, which is where the
 * active-slide ranges start, so the highlight lands on the same slide), `undefined` outside a slide: the gap between
 * thumbnails, a missing target or a wrapper without a content section.
 */
export function slideLineAt(target: EventTarget | null): number | undefined {
  const wrapper = target instanceof Element ? target.closest('[data-marp-slide-wrapper]') : null
  const value = wrapper?.querySelector(`section[${dataStartLine}]`)?.getAttribute(dataStartLine)
  if (value === null || value === undefined) return undefined
  const line = Number(value)
  return Number.isNaN(line) ? undefined : line
}
