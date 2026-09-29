/**
 * Updates `live` (the slide container) to match `next` by replacing only the children whose markup changed, so
 * untouched slides keep their DOM (no image or font flicker, no re-upgrade of custom elements). The children are the
 * slide wrappers and, with presenter notes on, the notes card after each of them (see notes.ts), so editing a note
 * replaces only its card. `previous` holds the pristine markup of the last render, because the live DOM is mutated by
 * marp-core's custom elements and the active-slide marker.
 */
export function patchSlides(live: Element, next: Element, previous: readonly string[]): string[] {
  const nextChildren = Array.from(next.children)
  const nextHtml = nextChildren.map((c) => c.outerHTML)

  nextChildren.forEach((child, i) => {
    const current = live.children[i]
    if (!current) live.append(child)
    else if (previous[i] !== nextHtml[i]) current.replaceWith(child)
  })
  for (const extra of Array.from(live.children).slice(nextChildren.length)) extra.remove()
  return nextHtml
}

/**
 * Elements removed from deck HTML before it reaches the page, whatever the HTML setting (marp-core's default allowlist
 * never lets them through anyway, so this only matters for "All"):
 * - `meta[http-equiv]`: a `refresh` would navigate the preview page away, which the CSP cannot prevent (and CEF does
 *   not report about:blank navigations).
 * - frames, `object`, `embed`, `portal`: `frame-src 'none'` still renders about:blank and `srcdoc` frames, and their
 *   documents are outside the page's click interception, so a link in one would navigate the main frame. None of them
 *   can load anything under this CSP, so nothing useful is lost.
 * - `base`: the page's own `<base>` stays the first one in tree order and thus the one that counts.
 * - `link[rel~=import]`: HTML imports are long gone from Chromium; listed so they can never load a document.
 */
const blockedElements = 'meta[http-equiv], iframe, frame, object, embed, portal, base, link[rel~=import i]'

/**
 * Parses rendered deck HTML into an inert `<template>` and scrubs it there, before anything is inserted. Also drops
 * `autofocus`: slides are re-inserted on every edit, and each insert would move keyboard focus into the preview.
 */
export function parseFragment(doc: Document, html: string): Element | null {
  const template = doc.createElement('template')
  template.innerHTML = html
  for (const el of template.content.querySelectorAll(blockedElements)) el.remove()
  for (const el of template.content.querySelectorAll('[autofocus]')) el.removeAttribute('autofocus')
  return template.content.firstElementChild
}
