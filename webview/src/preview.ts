import { browser } from '@marp-team/marp-core/browser'
import { markActiveSlide } from './active-slide'
import { createErrorBanner } from './error-banner'
import { createHostChannel } from './host'
import { createMarp, marpKey, type MarpBuild } from './marp-factory'
import { parseFragment, patchSlides } from './patch'
import { collectCodeLines, lineForViewportPosition, offsetForLine, type CodeLine } from './scroll-sync'
import type { MarpBridge, RenderOptions, ThemeInput } from './types'

type UpdateArg = Parameters<MarpBridge['update']>[0]

const host = createHostChannel(window)
const banner = createErrorBanner(document)
const marpBrowser = browser()

const root = document.getElementById('marp-root') as HTMLElement
const baseEl = (document.querySelector('base') ?? document.head.appendChild(document.createElement('base'))) as HTMLBaseElement
const styleEl = document.head.appendChild(document.createElement('style'))
styleEl.id = 'marp-theme-style'
document.body.prepend(banner.element)

let themes: ThemeInput[] = []
let kotlinErrors: string[] = []
let renderError: string | undefined
let build: (MarpBuild & { key: string }) | undefined
let lastUpdate: UpdateArg | undefined
let lastCss = ''
let slideHtml: string[] = []
let shownErrors = new Set<string>()

let renderPending = false
let pendingScrollLine: number | undefined
let activeLine: number | undefined
let entries: CodeLine[] | undefined
let programmaticY: number | undefined
let lastRevealed: number | undefined
/**
 * Source line the preview is aligned to: the last `scrollToLine` (`fromEditor`) or where the user scrolled the preview.
 * Re-applied when the viewport is resized, and after a render when it came from the editor, because pixel offsets
 * change with the layout while the source line keeps the preview in step with the editor.
 */
let anchor: { line: number; fromEditor: boolean } | undefined

/** rAF, with a timer fallback in case the browser pauses animation frames (hidden tab, offscreen). */
function nextFrame(fn: () => void): void {
  let done = false
  const run = () => {
    if (done) return
    done = true
    fn()
  }
  requestAnimationFrame(run)
  window.setTimeout(run, 100)
}

function showErrors(): void {
  const all = [...kotlinErrors, ...(build?.errors ?? []), ...(renderError ? [renderError] : [])]
  for (const message of all) {
    if (!shownErrors.has(message)) host.post({ type: 'error', message })
  }
  shownErrors = new Set(all)
  banner.set(all)
}

function scheduleRender(): void {
  if (renderPending) return
  renderPending = true
  nextFrame(render)
}

function ensureMarp(options: RenderOptions): MarpBuild {
  const key = marpKey(options, themes)
  if (!build || build.key !== key) build = { key, ...createMarp(options, themes) }
  return build
}

function render(): void {
  renderPending = false
  const arg = lastUpdate
  if (!arg) return

  if (baseEl.getAttribute('href') !== arg.baseHref) baseEl.setAttribute('href', arg.baseHref)

  try {
    const { html, css } = ensureMarp(arg.options).marp.render(arg.markdown)
    inject(html, css)
    renderError = undefined
  } catch (e) {
    renderError = `Render failed: ${e instanceof Error ? e.message : String(e)}`
  }
  showErrors()

  entries = undefined
  if (activeLine !== undefined) markActiveSlide(root, activeLine)
  // A `scrollToLine` that arrived before this render was measured against the old markup, and the new markup can move
  // slides (one added above the viewport), so re-align with the editor instead of keeping the pixel offset.
  const line = pendingScrollLine ?? (anchor?.fromEditor ? anchor.line : undefined)
  pendingScrollLine = undefined
  if (line !== undefined) applyScroll(line)
}

function inject(html: string, css: string): void {
  if (css !== lastCss) {
    styleEl.textContent = css
    lastCss = css
  }

  const scrollX = window.scrollX
  const scrollY = window.scrollY
  const next = parseFragment(document, html)
  const live = root.firstElementChild
  if (next && live && live.tagName === next.tagName && live.id === next.id) {
    slideHtml = patchSlides(live, next, slideHtml)
  } else {
    root.replaceChildren(...(next ? [next] : []))
    slideHtml = next ? Array.from(next.children).map((c) => c.outerHTML) : []
  }

  // Custom elements upgrade on insert; update() also covers fitting headers and auto-scaling.
  marpBrowser.update()

  if (window.scrollY !== scrollY) {
    window.scrollTo(scrollX, scrollY)
    programmaticY = window.scrollY
  }
}

function applyScroll(line: number): void {
  entries ??= collectCodeLines(document, document.body)
  const y = offsetForLine(entries, line, { scrollY: window.scrollY })
  if (y === undefined) return
  window.scrollTo(window.scrollX, y)
  programmaticY = window.scrollY
}

const bridge: MarpBridge = {
  setThemes({ themes: next, errors }) {
    themes = next
    kotlinErrors = errors
    if (lastUpdate) scheduleRender()
    else showErrors()
  },

  update(arg) {
    lastUpdate = arg
    scheduleRender()
  },

  scrollToLine(line) {
    anchor = { line, fromEditor: true }
    if (renderPending || !lastUpdate) pendingScrollLine = line
    else applyScroll(line)
  },

  setActiveLine(line) {
    activeLine = line
    if (!renderPending) markActiveSlide(root, line)
  },

  setIdeTheme({ dark, background, foreground }) {
    const html = document.documentElement
    html.classList.toggle('ide-dark', dark)
    html.classList.toggle('ide-light', !dark)
    html.style.setProperty('--ide-bg', background)
    html.style.setProperty('--ide-fg', foreground)
  },
}
window.marpBridge = bridge

// User scroll -> revealLine (throttled to one per frame, programmatic scrolls are ignored).
let scrollQueued = false
window.addEventListener(
  'scroll',
  () => {
    if (scrollQueued) return
    scrollQueued = true
    nextFrame(() => {
      scrollQueued = false
      if (programmaticY !== undefined) {
        if (Math.abs(window.scrollY - programmaticY) < 1.5) return
        programmaticY = undefined
      }
      entries ??= collectCodeLines(document, document.body)
      const line = lineForViewportPosition(entries, 0)
      if (line === null) return
      anchor = { line, fromEditor: false }
      if (line === lastRevealed) return
      lastRevealed = line
      host.post({ type: 'revealLine', line })
    })
  },
  { passive: true },
)

// Resizing the preview (splitter, editor split, tool windows) rescales the slides: keep showing the same source line.
// Without this, the pixel offset would point at other slides, and a scroll clamped by a shorter page would be reported
// as a user scroll and move the editor. The resulting scroll events match `programmaticY` and are not reported.
window.addEventListener('resize', () => {
  if (!anchor) return
  if (renderPending) pendingScrollLine ??= anchor.line
  else applyScroll(anchor.line)
})

function onLinkClick(e: MouseEvent): void {
  const a = (e.target as Element | null)?.closest?.('a[href]') as HTMLAnchorElement | null
  if (!a) return
  e.preventDefault()
  if (e.type === 'auxclick') return

  const raw = a.getAttribute('href') ?? ''
  if (raw.startsWith('#')) {
    const id = decodeURIComponent(raw.slice(1))
    const target = document.getElementById(id) ?? document.getElementsByName(id)[0]
    target?.scrollIntoView()
    return
  }
  host.post({ type: 'openLink', href: a.href })
}
document.addEventListener('click', onLinkClick)
document.addEventListener('auxclick', onLinkClick)

document.addEventListener('dblclick', (e) => {
  const target = e.target as Element | null
  const el = target?.closest?.('[data-line]') ?? target?.closest?.('section[data-marp-content-start-line]')
  if (!el) return
  const line = Number(el.getAttribute('data-line') ?? el.getAttribute('data-marp-content-start-line'))
  if (!Number.isNaN(line)) host.post({ type: 'didClick', line })
})

host.post({ type: 'ready' })
