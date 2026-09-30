import { browser } from '@marp-team/marp-core/browser'
import { markActiveSlide } from './active-slide'
import { isEmptyDeck } from './empty-deck'
import { createErrorBanner } from './error-banner'
import { exportDocument } from './export-html'
import { createHostChannel } from './host'
import { findLink } from './links'
import { createMarp, marpKey, type MarpBuild } from './marp-factory'
import { insertNotes } from './notes'
import { parseFragment, patchSlides } from './patch'
import { createScrollReporter } from './scroll-reporter'
import { collectCodeLines, lineForViewportPosition, offsetForLine, type CodeLine } from './scroll-sync'
import { assetsSettled } from './settle'
import { defaultStrings, formatMessage, mergeStrings } from './strings'
import type { MarpBridge, PreviewStrings, RenderOptions, ThemeInput } from './types'

type UpdateArg = Parameters<MarpBridge['update']>[0]

let strings: PreviewStrings = defaultStrings

const host = createHostChannel(window)
const banner = createErrorBanner(document, strings.dismiss)
const marpBrowser = browser()

const root = document.getElementById('marp-root') as HTMLElement
const baseEl = (document.querySelector('base') ?? document.head.appendChild(document.createElement('base'))) as HTMLBaseElement
const styleEl = document.head.appendChild(document.createElement('style'))
styleEl.id = 'marp-theme-style'
document.body.prepend(banner.element)
const emptyHint = document.createElement('p')
emptyHint.id = 'marp-empty-hint'
emptyHint.hidden = true
emptyHint.textContent = strings.emptyDeck
root.after(emptyHint)

let themes: ThemeInput[] = []
let kotlinErrors: string[] = []
/** Message of the last render exception, formatted with `strings.renderError` when shown. */
let renderError: string | undefined
let build: (MarpBuild & { key: string }) | undefined
let lastUpdate: UpdateArg | undefined
let lastCss = ''
let slideHtml: string[] = []
let shownErrors = new Set<string>()

/** How long `flushRender` waits for fonts and images at most. */
const ASSET_WAIT_MS = 5000

let renderPending = false
let pendingScrollLine: number | undefined
let activeLine: number | undefined
let entries: CodeLine[] | undefined
const scroll = createScrollReporter((line) => host.post({ type: 'revealLine', line }))

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
  const all = [
    ...kotlinErrors,
    ...(build?.errors ?? []).map((e) => formatMessage(strings.themeError, e.source, e.message)),
    ...(renderError === undefined ? (build?.unknownThemes() ?? []).map((name) => formatMessage(strings.unknownTheme, name)) : []),
    ...(renderError !== undefined ? [formatMessage(strings.renderError, renderError)] : []),
  ]
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
    const { html, css, comments } = ensureMarp(arg.options).marp.render(arg.markdown)
    inject(html, css, arg.options.notes === true ? comments : undefined)
    renderError = undefined
  } catch (e) {
    renderError = e instanceof Error ? e.message : String(e)
  }
  showErrors()

  entries = undefined
  if (activeLine !== undefined) markActiveSlide(root, activeLine)
  // A `scrollToLine` that arrived before this render was measured against the old markup, and the new markup can move
  // slides (one added above the viewport), so re-align with the editor instead of keeping the pixel offset.
  const anchor = scroll.anchor
  const line = pendingScrollLine ?? (anchor?.fromEditor ? anchor.line : undefined)
  pendingScrollLine = undefined
  if (line !== undefined) applyScroll(line)
}

/** `notes`: the render's comments per slide when presenter notes are on. */
function inject(html: string, css: string, notes: readonly (readonly string[])[] | undefined): void {
  if (css !== lastCss) {
    styleEl.textContent = css
    lastCss = css
  }

  const scrollX = window.scrollX
  const scrollY = window.scrollY
  const next = parseFragment(document, html)
  emptyHint.hidden = !isEmptyDeck(next)
  if (next && notes) insertNotes(next, notes)
  const live = root.firstElementChild
  if (next && live && live.tagName === next.tagName && live.id === next.id) {
    slideHtml = patchSlides(live, next, slideHtml)
  } else {
    // Read before inserting, like patchSlides: once in the document, custom elements may rewrite their markup.
    slideHtml = next ? Array.from(next.children, (c) => c.outerHTML) : []
    root.replaceChildren(...(next ? [next] : []))
  }

  // Custom elements upgrade on insert; update() also covers fitting headers and auto-scaling.
  marpBrowser.update()

  if (window.scrollY !== scrollY) {
    window.scrollTo(scrollX, scrollY)
    scroll.scrolledProgrammatically(window.scrollY)
  }
}

function applyScroll(line: number): void {
  entries ??= collectCodeLines(document, document.body)
  const y = offsetForLine(entries, line, { scrollY: window.scrollY })
  if (y === undefined) return
  window.scrollTo(window.scrollX, y)
  scroll.scrolledProgrammatically(window.scrollY)
}

const bridge: MarpBridge = {
  setStrings(next) {
    strings = mergeStrings(next)
    banner.setDismissLabel(strings.dismiss)
    emptyHint.textContent = strings.emptyDeck
    showErrors()
  },

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
    scroll.editorScrolled(line)
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

  exportHtml({ id, title, present }) {
    const arg = lastUpdate
    if (!arg) {
      host.post({ type: 'reply', id, error: 'There is nothing to export yet' })
      return
    }
    try {
      host.post({ type: 'reply', id, html: exportDocument(arg.markdown, arg.options, themes, title, present) })
    } catch (e) {
      host.post({ type: 'reply', id, error: e instanceof Error ? e.message : String(e) })
    }
  },

  flushRender({ id }) {
    if (renderPending) render()
    // The frame lets the browser lay out what render() inserted, so that fonts are requested and images start loading.
    // Printing does not wait for either, so the reply waits for them (capped, Kotlin's timeout is 10 s). The render that
    // is still queued from scheduleRender runs later with the same argument, and patchSlides makes that a no-op.
    nextFrame(() => {
      void assetsSettled(document, root, ASSET_WAIT_MS).then(() => {
        if (!lastUpdate) host.post({ type: 'reply', id, error: 'There is nothing to print yet' })
        else if (renderError !== undefined) host.post({ type: 'reply', id, error: renderError })
        else host.post({ type: 'reply', id })
      })
    })
  },
}
window.marpBridge = bridge

// User scroll -> revealLine (throttled to one per frame; scroll-reporter.ts drops the page's own scrolls).
let scrollQueued = false
window.addEventListener(
  'scroll',
  () => {
    if (scrollQueued) return
    scrollQueued = true
    nextFrame(() => {
      scrollQueued = false
      scroll.scrolled(window.scrollY, () => {
        entries ??= collectCodeLines(document, document.body)
        return lineForViewportPosition(entries, 0)
      })
    })
  },
  { passive: true },
)

// Resizing the preview (splitter, editor split, tool windows) rescales the slides: keep showing the same source line.
// Without this, the pixel offset would point at other slides, and a scroll clamped by a shorter page would be reported
// as a user scroll and move the editor. applyScroll marks the resulting scroll events as programmatic.
window.addEventListener('resize', () => {
  const anchor = scroll.anchor
  if (!anchor) return
  if (renderPending) pendingScrollLine ??= anchor.line
  else applyScroll(anchor.line)
})

function onLinkClick(e: MouseEvent): void {
  const link = findLink(e.target, document.baseURI)
  if (!link) return
  e.preventDefault()
  if (e.type === 'auxclick') return

  if (link.raw.startsWith('#')) {
    const id = decodeURIComponent(link.raw.slice(1))
    const target = document.getElementById(id) ?? document.getElementsByName(id)[0]
    target?.scrollIntoView()
    return
  }
  host.post({ type: 'openLink', href: link.href })
}
document.addEventListener('click', onLinkClick)
document.addEventListener('auxclick', onLinkClick)

// A file or link dropped on the preview would navigate the page to it. Kotlin cancels that navigation, but for an
// http(s) link dropped by the user it opens the system browser instead; the preview is no drop target at all.
for (const type of ['dragover', 'drop']) document.addEventListener(type, (e) => e.preventDefault())

document.addEventListener('dblclick', (e) => {
  const target = e.target as Element | null
  const el = target?.closest?.('[data-line]') ?? target?.closest?.('section[data-marp-content-start-line]')
  if (!el) return
  const line = Number(el.getAttribute('data-line') ?? el.getAttribute('data-marp-content-start-line'))
  if (!Number.isNaN(line)) host.post({ type: 'didClick', line })
})

host.post({ type: 'ready' })
