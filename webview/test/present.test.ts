// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createExportMarp } from '../src/export-html'
import { PRESENT_CSS, presentScript, runPresentation } from '../src/present'

const deck = '---\nmarp: true\n---\n\n# One\n\n[three](#3)\n\n---\n\n## Two\n\n---\n\n## Three\n'
const ACTIVE = 'marp-present-active'

const slideElements = (): Element[] => Array.from(document.querySelectorAll('div.marpit > svg[data-marpit-svg]'))
const active = (): number[] => slideElements().flatMap((slide, i) => (slide.classList.contains(ACTIVE) ? [i + 1] : []))

let stop: () => void = () => {}

function start(index: number): void {
  stop = runPresentation(document, window, index)
}

function key(keyName: string, init: KeyboardEventInit = {}, target: EventTarget = document.body): KeyboardEvent {
  const event = new KeyboardEvent('keydown', { key: keyName, bubbles: true, cancelable: true, ...init })
  target.dispatchEvent(event)
  return event
}

beforeEach(() => {
  document.body.innerHTML = createExportMarp({ html: 'default', math: 'off' }, []).render(deck).html
  history.replaceState(null, '', location.pathname)
})

afterEach(() => {
  stop()
  stop = () => {}
  vi.restoreAllMocks()
  Reflect.deleteProperty(document.documentElement, 'requestFullscreen')
  Reflect.deleteProperty(document, 'fullscreenElement')
  Reflect.deleteProperty(document, 'exitFullscreen')
  history.replaceState(null, '', location.pathname)
})

describe('runPresentation', () => {
  it('shows only the start slide and puts its number into the hash', () => {
    expect(slideElements()).toHaveLength(3)
    start(1)
    expect(active()).toEqual([2])
    expect(location.hash).toBe('#2')
  })

  it('goes forward with the next keys and stops at the last slide', () => {
    for (const next of ['ArrowRight', 'ArrowDown', 'PageDown', ' ', 'Enter']) {
      start(0)
      const event = key(next)
      expect(active(), next).toEqual([2])
      expect(event.defaultPrevented, next).toBe(true)
      key(next)
      key(next)
      expect(active(), next).toEqual([3])
      expect(location.hash, next).toBe('#3')
      stop()
      history.replaceState(null, '', location.pathname)
    }
  })

  it('goes back with the previous keys and stops at the first slide', () => {
    const previous: [string, KeyboardEventInit][] = [
      ['ArrowLeft', {}],
      ['ArrowUp', {}],
      ['PageUp', {}],
      ['Backspace', {}],
      [' ', { shiftKey: true }],
    ]
    for (const [name, init] of previous) {
      start(2)
      const event = key(name, init)
      expect(active(), name).toEqual([2])
      expect(event.defaultPrevented, name).toBe(true)
      key(name, init)
      key(name, init)
      expect(active(), name).toEqual([1])
      expect(location.hash, name).toBe('#1')
      stop()
      history.replaceState(null, '', location.pathname)
    }
  })

  it('jumps with Home and End', () => {
    start(1)
    key('End')
    expect(active()).toEqual([3])
    key('Home')
    expect(active()).toEqual([1])
  })

  it('leaves keys with Ctrl, Meta or Alt, prevented keys and typing in fields alone', () => {
    start(0)
    expect(key('ArrowRight', { ctrlKey: true }).defaultPrevented).toBe(false)
    key('ArrowRight', { metaKey: true })
    key('ArrowRight', { altKey: true })
    const input = document.body.appendChild(document.createElement('input'))
    expect(key('ArrowRight', {}, input).defaultPrevented).toBe(false)
    key(' ', {}, input)
    const editable = document.body.appendChild(document.createElement('div'))
    editable.setAttribute('contenteditable', 'true')
    key('ArrowRight', {}, editable)
    const blocked = (event: KeyboardEvent) => event.preventDefault()
    document.addEventListener('keydown', blocked, { once: true, capture: true })
    key('ArrowRight')
    expect(active()).toEqual([1])
    key('ArrowRight')
    expect(active()).toEqual([2])
  })

  it('leaves Enter to a focused link or button, but still handles the other keys there', () => {
    start(0)
    const link = document.body.appendChild(document.createElement('a'))
    link.setAttribute('href', 'https://example.com/')
    const button = document.body.appendChild(document.createElement('button'))
    const inner = button.appendChild(document.createElement('span'))
    for (const target of [link, button, inner]) {
      expect(key('Enter', {}, target).defaultPrevented).toBe(false)
      expect(active()).toEqual([1])
    }
    expect(key('ArrowRight', {}, link).defaultPrevented).toBe(true)
    expect(active()).toEqual([2])
    expect(key('Enter').defaultPrevented).toBe(true)
    expect(active()).toEqual([3])
  })

  it('ignores keys it does not know and does not prevent them', () => {
    start(0)
    const event = key('a')
    expect(event.defaultPrevented).toBe(false)
    expect(active()).toEqual([1])
  })

  it('prefers a valid hash to the start slide and falls back for an invalid one', () => {
    history.replaceState(null, '', '#1')
    start(2)
    expect(active()).toEqual([1])
    stop()

    for (const hash of ['#9', '#x', '#0', '#2x']) {
      history.replaceState(null, '', hash)
      start(2)
      expect(active(), hash).toEqual([3])
      stop()
    }
  })

  it('clamps the start slide', () => {
    const expectStart = (index: number, slide: number) => {
      history.replaceState(null, '', location.pathname)
      start(index)
      expect(active(), String(index)).toEqual([slide])
      stop()
    }
    expectStart(99, 3)
    expectStart(-4, 1)
    expectStart(Number.NaN, 1)
  })

  it('follows the hash', () => {
    start(0)
    history.replaceState(null, '', '#3')
    window.dispatchEvent(new Event('hashchange'))
    expect(active()).toEqual([3])
    history.replaceState(null, '', '#8')
    window.dispatchEvent(new Event('hashchange'))
    expect(active()).toEqual([3])
  })

  it('shows the slide that a click on a link to an id in the deck points to', () => {
    start(0)
    const link = document.querySelector<HTMLAnchorElement>('a[href="#3"]')
    expect(link).not.toBeNull()
    const event = new MouseEvent('click', { bubbles: true, cancelable: true })
    link?.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(true)
    expect(active()).toEqual([3])
    expect(location.hash).toBe('#3')
  })

  it('handles links with a percent-encoded id, unknown ids and a lone hash without navigating', () => {
    start(2)
    const anchor = (href: string) => {
      const a = document.createElement('a')
      a.setAttribute('href', href)
      document.querySelector('section')?.appendChild(a)
      return a
    }
    const click = (a: HTMLAnchorElement) => {
      const event = new MouseEvent('click', { bubbles: true, cancelable: true })
      a.dispatchEvent(event)
      return event
    }
    expect(click(anchor('#one')).defaultPrevented).toBe(true)
    expect(active()).toEqual([1])
    expect(click(anchor('#nothing-here')).defaultPrevented).toBe(true)
    expect(click(anchor('#')).defaultPrevented).toBe(true)
    expect(click(anchor('#%E0%A4%A')).defaultPrevented).toBe(true)
    expect(active()).toEqual([1])
  })

  it('does not touch links that leave the deck', () => {
    start(0)
    const a = document.createElement('a')
    a.setAttribute('href', 'https://example.com/')
    document.body.appendChild(a)
    const event = new MouseEvent('click', { bubbles: true, cancelable: true })
    // Seen after the presentation's own listener, and stops jsdom from trying to navigate.
    let seenPrevented: boolean | undefined
    window.addEventListener('click', (e) => { seenPrevented = e.defaultPrevented; e.preventDefault() }, { once: true })
    a.dispatchEvent(event)
    expect(seenPrevented).toBe(false)
  })

  it('toggles full screen with F', () => {
    const request = vi.fn(() => Promise.resolve())
    const exit = vi.fn(() => Promise.reject(new Error('refused')))
    Object.defineProperty(document.documentElement, 'requestFullscreen', { value: request, configurable: true })
    Object.defineProperty(document, 'exitFullscreen', { value: exit, configurable: true })
    start(0)
    expect(key('f').defaultPrevented).toBe(true)
    expect(request).toHaveBeenCalledTimes(1)
    Object.defineProperty(document, 'fullscreenElement', { value: document.documentElement, configurable: true })
    key('F')
    expect(exit).toHaveBeenCalledTimes(1)
    expect(request).toHaveBeenCalledTimes(1)
  })

  it('survives a browser without the full screen API', () => {
    start(0)
    expect(() => key('f')).not.toThrow()
  })

  it('returns a no-op and does nothing without slides', () => {
    document.body.innerHTML = '<p>no deck</p>'
    const cleanup = runPresentation(document, window, 0)
    expect(key('ArrowRight').defaultPrevented).toBe(false)
    expect(location.hash).toBe('')
    expect(() => cleanup()).not.toThrow()
  })

  it('stops listening after the cleanup', () => {
    start(0)
    stop()
    key('ArrowRight')
    expect(active()).toEqual([1])
  })

  it('marks slides with the class that PRESENT_CSS displays', () => {
    expect(PRESENT_CSS).toContain(`.${ACTIVE} { display: block }`)
  })
})

describe('PRESENT_CSS', () => {
  it('sits inside a screen media query, so printing keeps the marp-core rules', () => {
    expect(PRESENT_CSS.startsWith('@media screen {')).toBe(true)
    expect(PRESENT_CSS.trimEnd().endsWith('}')).toBe(true)
    // Balanced braces, the last one closes the media query.
    const open = PRESENT_CSS.split('{').length - 1
    const close = PRESENT_CSS.split('}').length - 1
    expect(open).toBe(close)
    expect(PRESENT_CSS).toContain('display: none')
    expect(PRESENT_CSS).toContain('position: fixed')
  })
})

describe('presentScript', () => {
  it('is self-contained: evaluated on its own it starts the presentation', () => {
    stop = new Function(`return ${presentScript(1)}`)() as () => void
    expect(active()).toEqual([2])
    expect(key('ArrowRight').defaultPrevented).toBe(true)
    expect(active()).toEqual([3])
  })

  it('cannot end or confuse the script element it is written into', () => {
    const script = presentScript(0)
    expect(script).not.toMatch(/<\/script/i)
    expect(script).not.toContain('<!--')
    expect(script).not.toMatch(/<script/i)
  })

  it('embeds a sane start slide', () => {
    expect(presentScript(4).endsWith('(document, window, 4);')).toBe(true)
    expect(presentScript(2.9).endsWith(', 2);')).toBe(true)
    expect(presentScript(-3).endsWith(', 0);')).toBe(true)
    expect(presentScript(Number.NaN).endsWith(', 0);')).toBe(true)
    expect(presentScript(Number.POSITIVE_INFINITY).endsWith(', 0);')).toBe(true)
  })
})
