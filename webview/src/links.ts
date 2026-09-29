const XLINK = 'http://www.w3.org/1999/xlink'

export interface Link {
  /** The attribute as written, e.g. `#slide-2` or `other.md`. */
  raw: string
  /** Absolute URL, resolved against the document base. */
  href: string
}

/**
 * The link a click lands on: HTML `<a href>` / `<area href>` or SVG `<a href>` / `<a xlink:href>` (inline SVG slides).
 * Every one of them has to be intercepted: letting the browser follow one would navigate the preview page away.
 */
export function findLink(target: EventTarget | null, baseURI: string): Link | null {
  const el = (target as Element | null)?.closest?.('a, area')
  if (!el) return null
  const raw = el.getAttribute('href') ?? el.getAttributeNS(XLINK, 'href')
  if (raw === null) return null
  try {
    return { raw, href: new URL(raw, baseURI).href }
  } catch {
    return { raw, href: raw }
  }
}
