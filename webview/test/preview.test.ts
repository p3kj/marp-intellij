// @vitest-environment jsdom
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import type { MarpBridge, RenderOptions } from '../src/types'

let bridge: MarpBridge
/** Everything the page posted to the host, parsed. */
const posted: any[] = []

/** The page renders in the next animation frame; a frame callback registered after it runs after the render. */
const frame = () => new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))
const render = async (markdown: string, options: Partial<RenderOptions> = {}) => {
  bridge.update({ markdown, baseHref: 'https://marp.localhost/doc/', options: { html: 'default', math: 'off', ...options } })
  await frame()
}
const hint = () => document.getElementById('marp-empty-hint')
const cards = () => Array.from(document.querySelectorAll<HTMLElement>('#marp-root .marp-notes'))
const replies = (id: number) => posted.filter((m) => m.type === 'reply' && m.id === id)
const slides = () => Array.from(document.querySelectorAll('#marp-root [data-marp-slide-wrapper]'))

beforeAll(async () => {
  document.body.innerHTML = '<div id="marp-root"></div>'
  ;(window as any).__marpHost = { post: (msg: string) => posted.push(JSON.parse(msg)) }
  ;(window as any).matchMedia ??= () => ({ matches: false, addEventListener() {}, removeEventListener() {} })
  // jsdom has no canvas; marp-core's browser() probes one (jsdom reports Apple as navigator.vendor). Quiets its warning.
  ;(HTMLCanvasElement.prototype as any).getContext = () => null
  await import('../src/preview')
  bridge = window.marpBridge
})

// Must stay first: it needs a page that has not rendered anything yet.
describe('export commands before the first update', () => {
  it('reply with an error, exactly once each', async () => {
    bridge.exportHtml({ id: 1, title: 'x' })
    bridge.flushRender({ id: 2 })
    await frame()
    await new Promise((resolve) => setTimeout(resolve, 150))
    expect(replies(1)).toHaveLength(1)
    expect(replies(1)[0].error).toBeTruthy()
    expect(replies(1)[0].html).toBeUndefined()
    expect(replies(2)).toHaveLength(1)
    expect(replies(2)[0].error).toBeTruthy()
  })
})

describe('drag and drop', () => {
  it('cancels dragover and drop, so a dropped file or link never navigates the page', () => {
    for (const type of ['dragover', 'drop']) {
      const e = new Event(type, { bubbles: true, cancelable: true })
      document.querySelector('#marp-root')?.dispatchEvent(e)
      expect(e.defaultPrevented, type).toBe(true)
    }
  })
})

describe('empty deck hint', () => {
  it('shows for a deck without content and hides once there is some', async () => {
    await render('---\nmarp: true\n---\n')
    expect(hint()?.hidden).toBe(false)
    expect(hint()?.textContent).toBe('No slides yet. Add content after the front matter, separate slides with ---.')
    await render('---\nmarp: true\n---\n\n# Title\n')
    expect(hint()?.hidden).toBe(true)
  })

  it('takes its text from setStrings', () => {
    bridge.setStrings({ dismiss: 'd', themeError: 't', renderError: 'r', unknownTheme: 'u', emptyDeck: 'Zatím prázdné.' })
    expect(hint()?.textContent).toBe('Zatím prázdné.')
  })
})

describe('presenter notes', () => {
  const deck = '# One\n\n<!-- note one -->\n\n---\n\n# Two\n'

  it('are off unless options.notes is true', async () => {
    await render(deck)
    expect(cards()).toHaveLength(0)
    await render(deck, { notes: false })
    expect(cards()).toHaveLength(0)
  })

  it('show under their slide when on, without touching unchanged slides', async () => {
    await render(deck, { notes: true })
    expect(cards().map((c) => [c.hidden, c.textContent])).toEqual([
      [false, 'note one'],
      [true, ''],
    ])
    const before = slides()
    expect(before).toHaveLength(2)

    await render(deck.replace('note one', 'note one, edited'), { notes: true })
    expect(cards()[0]?.textContent).toBe('note one, edited')
    // The same node objects (toEqual would compare DOM nodes structurally).
    expect(slides().every((slide, i) => slide === before[i])).toBe(true)

    await render(deck, { notes: false })
    expect(cards()).toHaveLength(0)
    expect(slides()).toHaveLength(2)
  })
})

describe('export commands', () => {
  const themes = [{ source: 'x.css', css: '/* @theme cmdtheme */\nsection { background: #654321; }' }]

  it('exportHtml answers with the standalone document of the last update, its themes and the fallback title', async () => {
    bridge.setThemes({ themes, errors: [] })
    await render('---\ntheme: cmdtheme\n---\n\n# Export me\n\n---\n\n# Second\n')
    bridge.exportHtml({ id: 10, title: 'deck-file' })
    const [reply] = replies(10)
    expect(replies(10)).toHaveLength(1)
    expect(reply.error).toBeUndefined()
    expect(reply.html).toContain('<!DOCTYPE html>')
    expect(reply.html).toContain('<title>deck-file</title>')
    expect(reply.html).toContain('Export me')
    expect(reply.html).toContain('Second')
    expect(reply.html).toContain('#654321')
    // The export is a separate render: the preview's own markup stays what it was.
    expect(reply.html).not.toContain('data-marp-slide-wrapper')
    expect(slides()).toHaveLength(2)
  })

  it('exportHtml with present answers with the presentation of the same deck', async () => {
    bridge.setThemes({ themes, errors: [] })
    await render('---\ntheme: cmdtheme\n---\n\n# Present me\n\n---\n\n# Second\n')
    bridge.exportHtml({ id: 13, title: 'deck-file', present: { baseHref: 'file:///work/talk/', start: 1 } })
    const [reply] = replies(13)
    expect(replies(13)).toHaveLength(1)
    expect(reply.error).toBeUndefined()
    expect(reply.html).toContain('<meta charset="utf-8"><base href="file:///work/talk/">')
    expect(reply.html).toContain('<title>deck-file</title>')
    expect(reply.html).toContain('Present me')
    expect(reply.html).toContain('#654321')
    expect(reply.html).toContain('marp-present-active')
    expect(reply.html).toMatch(/\(document, window, 1\);<\/script><\/body>/)
    // Without present the same command still answers with the plain export.
    bridge.exportHtml({ id: 14, title: 'deck-file' })
    expect(replies(14)[0].html).not.toContain('<base href')
    expect(replies(14)[0].html).not.toContain('marp-present-active')
  })

  it('exportHtml uses the render options of the last update', async () => {
    await render('# T\n\n<b class="raw">raw</b>\n', { html: 'all' })
    bridge.exportHtml({ id: 11, title: 't' })
    expect(replies(11)[0].html).toContain('<b class="raw">raw</b>')
    await render('# T\n\n<b class="raw">raw</b>\n', { html: 'off' })
    bridge.exportHtml({ id: 12, title: 't' })
    expect(replies(12)[0].html).not.toContain('<b class="raw">')
  })

  it('flushRender renders the pending update at once and replies after the frame', async () => {
    bridge.update({ markdown: '# Pending text\n', baseHref: 'https://marp.localhost/doc/', options: { html: 'default', math: 'off' } })
    expect(document.getElementById('marp-root')?.textContent).not.toContain('Pending text')
    bridge.flushRender({ id: 20 })
    expect(document.getElementById('marp-root')?.textContent).toContain('Pending text')
    expect(replies(20)).toHaveLength(0)
    await frame()
    expect(replies(20)).toEqual([{ type: 'reply', id: 20 }])
    // The render that scheduleRender queued still runs, and changes nothing.
    await frame()
    expect(slides()).toHaveLength(1)
    expect(replies(20)).toHaveLength(1)
  })

  it('flushRender waits for images that are still loading, then replies', async () => {
    await render('# Img\n\n![](https://marp.localhost/doc/pic.png)\n')
    const img = document.querySelector<HTMLImageElement>('#marp-root img[src$="pic.png"]')
    expect(img).not.toBeNull()
    Object.defineProperty(img, 'complete', { value: false, configurable: true })

    bridge.flushRender({ id: 22 })
    await frame()
    await new Promise((resolve) => setTimeout(resolve, 150))
    expect(replies(22)).toHaveLength(0)

    img?.dispatchEvent(new Event('load'))
    await new Promise((resolve) => setTimeout(resolve, 10))
    expect(replies(22)).toEqual([{ type: 'reply', id: 22 }])
  })

  it('flushRender with nothing pending still replies once', async () => {
    await render('# Idle\n')
    bridge.flushRender({ id: 21 })
    await frame()
    expect(replies(21)).toEqual([{ type: 'reply', id: 21 }])
  })

  it('a render exception gives an error reply to both commands', async () => {
    bridge.update({ markdown: 42 as unknown as string, baseHref: 'https://marp.localhost/doc/', options: { html: 'default', math: 'off' } })
    bridge.exportHtml({ id: 30, title: 't' })
    expect(replies(30)[0].html).toBeUndefined()
    expect(replies(30)[0].error).toBeTruthy()
    bridge.flushRender({ id: 31 })
    await frame()
    expect(replies(31)).toHaveLength(1)
    expect(replies(31)[0].error).toBeTruthy()
    // Recovers with the next good update.
    await render('# Fine again\n')
    bridge.flushRender({ id: 32 })
    await frame()
    expect(replies(32)).toEqual([{ type: 'reply', id: 32 }])
  })
})

describe('slide overview', () => {
  const deck = '---\nmarp: true\n---\n\n# First\n\n[a link](https://example.com/)\n\n---\n\n# Second\n\n---\n\n# Third\n'
  const root = () => document.getElementById('marp-root') as HTMLElement
  const startLines = () =>
    Array.from(document.querySelectorAll('#marp-root section[data-marp-content-start-line]'), (s) =>
      Number(s.getAttribute('data-marp-content-start-line')),
    )
  const click = (target: Element, type = 'click') => {
    const e = new MouseEvent(type, { bubbles: true, cancelable: true })
    target.dispatchEvent(e)
    return e
  }
  const didClicks = () => posted.filter((m) => m.type === 'didClick')
  const scrollIntoView = vi.fn()
  const scrollTo = vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
  const original = Element.prototype.scrollIntoView

  beforeAll(() => {
    // jsdom has no scrollIntoView.
    Element.prototype.scrollIntoView = scrollIntoView
  })

  afterEach(() => {
    bridge.setOverview(false)
    scrollIntoView.mockClear()
    scrollTo.mockClear()
    posted.length = 0
  })

  afterAll(() => {
    Element.prototype.scrollIntoView = original
    scrollTo.mockRestore()
  })

  it('setOverview puts the class on #marp-root and takes it off again', async () => {
    await render(deck)
    expect(root().classList.contains('marp-overview')).toBe(false)
    bridge.setOverview(true)
    expect(root().classList.contains('marp-overview')).toBe(true)
    bridge.setOverview(true)
    expect(root().classList.contains('marp-overview')).toBe(true)
    bridge.setOverview(false)
    expect(root().classList.contains('marp-overview')).toBe(false)
  })

  it('a click on a slide posts didClick with the content start line of that slide', async () => {
    await render(deck)
    const lines = startLines()
    expect(lines).toHaveLength(3)
    bridge.setOverview(true)
    const headings = Array.from(document.querySelectorAll('#marp-root h1'))
    expect(headings).toHaveLength(3)
    const e = click(headings[1]!)
    expect(e.defaultPrevented).toBe(true)
    expect(didClicks()).toEqual([{ type: 'didClick', line: lines[1] }])
    click(slides()[2]!)
    expect(didClicks().at(-1)).toEqual({ type: 'didClick', line: lines[2] })
  })

  it('a click in the gap between thumbnails posts nothing', async () => {
    await render(deck)
    bridge.setOverview(true)
    click(document.getElementById('__marp-preview')!)
    expect(didClicks()).toEqual([])
  })

  it('links inside a slide stay inert: no openLink, the click is prevented', async () => {
    await render(deck)
    bridge.setOverview(true)
    const link = document.querySelector('#marp-root a[href="https://example.com/"]')
    expect(link).not.toBeNull()
    const e = click(link!)
    expect(e.defaultPrevented).toBe(true)
    expect(posted.filter((m) => m.type === 'openLink')).toEqual([])
    // A click on a link is a click on its slide.
    expect(didClicks()).toEqual([{ type: 'didClick', line: startLines()[0] }])
    // A middle click does nothing at all.
    posted.length = 0
    const aux = click(link!, 'auxclick')
    expect(aux.defaultPrevented).toBe(true)
    expect(posted).toEqual([])
  })

  it('a double-click adds nothing to the two clicks in the grid', async () => {
    await render(deck)
    bridge.setOverview(true)
    click(document.querySelectorAll('#marp-root h1')[0]!, 'dblclick')
    expect(didClicks()).toEqual([])
  })

  it('outside the grid a single click posts no didClick and links still open', async () => {
    await render(deck)
    click(document.querySelectorAll('#marp-root h1')[1]!)
    expect(didClicks()).toEqual([])
    click(document.querySelector('#marp-root a[href="https://example.com/"]')!)
    expect(posted.filter((m) => m.type === 'openLink')).toEqual([{ type: 'openLink', href: 'https://example.com/' }])
  })

  it('scrollToLine does not scroll the page in the grid, and leaving it shows the last line again', async () => {
    await render(deck)
    // jsdom has no layout: give every element a box, taller the further down the document it is, so that a line maps.
    const rect = vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (this: Element) {
      const top = 50 * Array.from(document.querySelectorAll('*')).indexOf(this)
      return { top, bottom: top + 40, left: 0, right: 100, width: 100, height: 40, x: 0, y: top, toJSON() {} }
    })
    try {
      bridge.scrollToLine(10)
      expect(scrollTo).toHaveBeenCalledTimes(1)
      scrollTo.mockClear()

      bridge.setOverview(true)
      bridge.scrollToLine(12)
      expect(scrollTo).not.toHaveBeenCalled()

      // The anchor followed the editor meanwhile, so the page shows line 12 once the grid is off.
      bridge.setOverview(false)
      expect(scrollTo).toHaveBeenCalledTimes(1)
    } finally {
      rect.mockRestore()
    }
  })

  it('setActiveLine scrolls the active thumbnail into view in the grid only', async () => {
    await render(deck)
    const lines = startLines()
    bridge.setActiveLine(lines[1]!)
    expect(scrollIntoView).not.toHaveBeenCalled()

    bridge.setOverview(true)
    // Entering the grid shows the highlighted slide.
    expect(scrollIntoView).toHaveBeenCalledTimes(1)
    expect(scrollIntoView.mock.contexts[0]).toBe(slides()[1])
    expect(scrollIntoView).toHaveBeenLastCalledWith({ block: 'nearest' })

    bridge.setActiveLine(lines[2]!)
    expect(slides()[2]!.classList.contains('marp-active-slide')).toBe(true)
    expect(scrollIntoView).toHaveBeenCalledTimes(2)
    expect(scrollIntoView.mock.contexts[1]).toBe(slides()[2])
  })

  it('a render in the grid does not scroll, so typing never jumps', async () => {
    await render(deck)
    bridge.setActiveLine(startLines()[0]!)
    bridge.setOverview(true)
    scrollIntoView.mockClear()
    await render(deck.replace('# Second', '# Second, edited'))
    expect(scrollIntoView).not.toHaveBeenCalled()
  })
})
