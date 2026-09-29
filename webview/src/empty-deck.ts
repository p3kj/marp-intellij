/**
 * Whether a render has nothing to show. Marpit always renders at least one slide, so an empty file, or one with only
 * front matter or directives, gives a single slide whose sections are empty; a background image (`![bg](...)`) or
 * header/footer counts as content.
 */
export function isEmptyDeck(container: Element | null): boolean {
  if (!container) return true
  const wrappers = container.querySelectorAll('[data-marp-slide-wrapper]')
  if (wrappers.length > 1) return false
  return Array.from(container.querySelectorAll('section')).every(
    (s) => s.childElementCount === 0 && (s.textContent ?? '').trim() === '',
  )
}
