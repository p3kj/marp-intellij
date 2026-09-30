// @vitest-environment jsdom
import { beforeAll, describe, expect, it } from 'vitest'
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
