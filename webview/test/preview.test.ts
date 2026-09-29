// @vitest-environment jsdom
import { beforeAll, describe, expect, it } from 'vitest'
import type { MarpBridge, RenderOptions } from '../src/types'

let bridge: MarpBridge

/** The page renders in the next animation frame; a frame callback registered after it runs after the render. */
const frame = () => new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))
const render = async (markdown: string, options: Partial<RenderOptions> = {}) => {
  bridge.update({ markdown, baseHref: 'https://marp.localhost/doc/', options: { html: 'default', math: 'off', ...options } })
  await frame()
}
const hint = () => document.getElementById('marp-empty-hint')
const cards = () => Array.from(document.querySelectorAll<HTMLElement>('#marp-root .marp-notes'))
const slides = () => Array.from(document.querySelectorAll('#marp-root [data-marp-slide-wrapper]'))

beforeAll(async () => {
  document.body.innerHTML = '<div id="marp-root"></div>'
  ;(window as any).__marpHost = { post() {} }
  ;(window as any).matchMedia ??= () => ({ matches: false, addEventListener() {}, removeEventListener() {} })
  // jsdom has no canvas; marp-core's browser() probes one (jsdom reports Apple as navigator.vendor). Quiets its warning.
  ;(HTMLCanvasElement.prototype as any).getContext = () => null
  await import('../src/preview')
  bridge = window.marpBridge
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
