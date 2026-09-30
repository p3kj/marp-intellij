// The presentation variant of the HTML export: CSS and the script that is written into the file. This module imports
// nothing on purpose, `runPresentation` is serialized into the exported document.

/** Marker class of the one visible slide. */
const ACTIVE_CLASS = 'marp-present-active'

/**
 * Screen CSS of a presentation, used instead of `EXPORT_CSS`. Every slide (an inline SVG with a viewBox) fills the
 * window and the SVG letterboxes and centers it, only the active one is displayed. Inside `@media screen`, so printing
 * keeps marp-core's own print rules (one slide per page). `display: none` rather than `visibility`, a theme could
 * override that.
 */
export const PRESENT_CSS =
  '@media screen { html, body { margin: 0; height: 100%; overflow: hidden; background: #000 } ' +
  'div.marpit > svg[data-marpit-svg] { position: fixed; inset: 0; width: 100%; height: 100%; margin: 0; ' +
  'box-shadow: none; display: none } ' +
  `div.marpit > svg[data-marpit-svg].${ACTIVE_CLASS} { display: block } }`

/**
 * The runtime of a presentation. It is written into the exported file as `String(runPresentation)`, so it MUST NOT
 * reference anything outside its own body: no imports, no module constants (not even `ACTIVE_CLASS`), no enums, no
 * helper functions. Shows one slide at a time (`svg[data-marpit-svg]` of `div.marpit`), starting at the `#N` of the URL
 * when that is a valid 1-based slide number and otherwise at `start` (0-based, clamped). The hash follows the slide, so
 * a reload stays where it was. Keys: next (ArrowRight, ArrowDown, PageDown, Space, Enter), previous (ArrowLeft,
 * ArrowUp, PageUp, Backspace, Shift+Space), Home, End and F for full screen. Links to `#...` inside the deck show the
 * slide that holds the target. Returns a function that removes the listeners (tests only), a no-op without slides.
 */
export function runPresentation(doc: Document, win: Window, start: number): () => void {
  const slides = Array.from(doc.querySelectorAll('div.marpit > svg[data-marpit-svg]'))
  if (slides.length === 0) return () => {}

  let current = -1
  const clamp = (index: number): number => Math.min(Math.max(index, 0), slides.length - 1)

  const show = (index: number): void => {
    const next = clamp(index)
    if (next === current) return
    current = next
    slides.forEach((slide, i) => slide.classList.toggle('marp-present-active', i === next))
    try {
      const hash = '#' + (next + 1)
      // An absolute URL: a relative one would resolve against the base URL of the file and leave it.
      if (win.location.hash !== hash) win.history.replaceState(null, '', win.location.href.split('#')[0] + hash)
    } catch (e) {
      // The hash is only a convenience for reloads.
    }
  }

  const slideFromHash = (): number => {
    const match = /^#(\d+)$/.exec(win.location.hash)
    const number = match ? Number(match[1]) : 0
    return number >= 1 && number <= slides.length ? number - 1 : -1
  }

  const toggleFullscreen = (): void => {
    try {
      const request = doc.fullscreenElement
        ? doc.exitFullscreen && doc.exitFullscreen()
        : doc.documentElement.requestFullscreen && doc.documentElement.requestFullscreen()
      if (request && request.catch) request.catch(() => {})
    } catch (e) {
      // Full screen can be refused, the presentation works without it.
    }
  }

  const onKeyDown = (event: KeyboardEvent): void => {
    if (event.defaultPrevented || event.ctrlKey || event.metaKey || event.altKey) return
    const target = event.target as Element | null
    if (target && target.closest && target.closest('input, textarea, select, [contenteditable]:not([contenteditable="false"])')) return
    let handled = true
    switch (event.key) {
      case 'ArrowRight':
      case 'ArrowDown':
      case 'PageDown':
      case 'Enter':
        show(current + 1)
        break
      case ' ':
        show(current + (event.shiftKey ? -1 : 1))
        break
      case 'ArrowLeft':
      case 'ArrowUp':
      case 'PageUp':
      case 'Backspace':
        show(current - 1)
        break
      case 'Home':
        show(0)
        break
      case 'End':
        show(slides.length - 1)
        break
      case 'f':
      case 'F':
        toggleFullscreen()
        break
      default:
        handled = false
    }
    if (handled) event.preventDefault()
  }

  const onHashChange = (): void => {
    const index = slideFromHash()
    if (index >= 0) show(index)
  }

  // Marpit gives every section `id="N"`, and headings have ids too. With the base URL of the file a plain `#3` would
  // navigate to the deck's folder, so the click is taken over.
  const onClick = (event: MouseEvent): void => {
    const target = event.target as Element | null
    const link = target && target.closest ? target.closest('a') : null
    if (!link) return
    const href = link.getAttribute('href') || link.getAttribute('xlink:href') || ''
    if (href.charAt(0) !== '#') return
    event.preventDefault()
    let id = href.slice(1)
    try {
      id = decodeURIComponent(id)
    } catch (e) {
      // Not percent-encoded text: use it as written.
    }
    const element = id ? doc.getElementById(id) : null
    if (!element) return
    const index = slides.findIndex((slide) => slide.contains(element))
    if (index >= 0) show(index)
  }

  doc.addEventListener('keydown', onKeyDown)
  doc.addEventListener('click', onClick)
  win.addEventListener('hashchange', onHashChange)

  const first = slideFromHash()
  show(first >= 0 ? first : Number.isFinite(start) ? Math.floor(start) : 0)

  return () => {
    doc.removeEventListener('keydown', onKeyDown)
    doc.removeEventListener('click', onClick)
    win.removeEventListener('hashchange', onHashChange)
  }
}

/** The `<script>` text for a presentation that starts at the 0-based slide `start` (negative or not a number: the first). */
export function presentScript(start: number): string {
  const first = Number.isFinite(start) ? Math.max(0, Math.floor(start)) : 0
  return `(${String(runPresentation)})(document, window, ${first});`
}
