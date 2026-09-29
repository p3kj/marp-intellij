/**
 * Updates `live` (the slide container) to match `next` by replacing only the slides whose
 * markup changed, so untouched slides keep their DOM (no image or font flicker, no
 * re-upgrade of custom elements). `previous` holds the pristine markup of the last render,
 * because the live DOM is mutated by marp-core's custom elements and the active-slide marker.
 */
export function patchSlides(live: Element, next: Element, previous: readonly string[]): string[] {
  const nextChildren = Array.from(next.children)
  const nextHtml = nextChildren.map((c) => c.outerHTML)

  nextChildren.forEach((child, i) => {
    const current = live.children[i]
    if (!current) live.append(child)
    else if (previous[i] !== nextHtml[i]) current.replaceWith(child)
  })
  while (live.children.length > nextChildren.length) live.lastElementChild!.remove()
  return nextHtml
}

/**
 * Parses rendered deck HTML. `<meta http-equiv>` is dropped: with `html: all` a `<meta http-equiv="refresh">` would
 * navigate the preview page away, which the CSP cannot prevent (and CEF does not report about:blank navigations).
 */
export function parseFragment(doc: Document, html: string): Element | null {
  const template = doc.createElement('template')
  template.innerHTML = html
  for (const meta of template.content.querySelectorAll('meta[http-equiv]')) meta.remove()
  return template.content.firstElementChild
}
