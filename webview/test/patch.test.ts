// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { parseFragment, patchSlides } from '../src/patch'

const container = (inner: string) => `<div id="c"><section>${inner}<p id="kept">x</p></section></div>`

describe('parseFragment', () => {
  const blocked: [string, string, string][] = [
    ['iframe with srcdoc', '<iframe srcdoc="<a href=https://example.com target=_top>x</a>"></iframe>', 'iframe'],
    ['blank iframe', '<iframe></iframe><iframe src="about:blank"></iframe>', 'iframe'],
    ['frame (only parsed inside SVG)', '<svg><frame/></svg>', 'frame'],
    ['object', '<object data="x.pdf" type="application/pdf"></object>', 'object'],
    ['embed', '<embed src="x.swf">', 'embed'],
    ['portal', '<portal src="https://example.com"></portal>', 'portal'],
    ['base', '<base href="https://example.com/">', 'base'],
    ['HTML import', '<link rel="import" href="x.html"><link rel="Import" href="y.html">', 'link'],
    ['meta http-equiv', '<meta http-equiv="refresh" content="0;url=https://example.com">', 'meta'],
  ]

  for (const [name, html, selector] of blocked) {
    it(`drops ${name}`, () => {
      const el = parseFragment(document, container(html))
      expect(el?.querySelectorAll(selector)).toHaveLength(0)
      expect(el?.querySelector('#kept')?.textContent).toBe('x')
    })
  }

  it('keeps stylesheets and other links, and meta without http-equiv', () => {
    const el = parseFragment(
      document,
      container('<link rel="stylesheet" href="https://fonts.example/x.css"><link rel="preconnect" href="https://fonts.example"><meta charset="utf-8">'),
    )
    expect(el?.querySelectorAll('link')).toHaveLength(2)
    expect(el?.querySelectorAll('meta')).toHaveLength(1)
  })

  it('removes autofocus so re-inserted slides do not take keyboard focus', () => {
    const el = parseFragment(document, container('<input autofocus><button autofocus="">b</button><select autofocus></select>'))
    expect(el?.querySelectorAll('[autofocus]')).toHaveLength(0)
    expect(el?.querySelectorAll('input, button, select')).toHaveLength(3)
  })

  it('returns null for empty markup', () => {
    expect(parseFragment(document, '')).toBeNull()
  })
})

describe('patchSlides', () => {
  const deck = (...slides: string[]) => parseFragment(document, `<div id="c">${slides.map((s) => `<div>${s}</div>`).join('')}</div>`)
  const children = (el: Element) => Array.from(el.children)

  it('replaces only the children whose markup changed, appends and removes the rest', () => {
    const first = deck('a', 'b', 'c')
    if (!first) throw new Error('no deck')
    document.body.replaceChildren(first)
    let previous = children(first).map((c) => c.outerHTML)
    const [a, b, c] = children(first)

    const second = deck('a', 'B', 'c', 'd')
    if (!second) throw new Error('no deck')
    previous = patchSlides(first, second, previous)
    const after = children(first)
    expect(after.map((e) => e.textContent)).toEqual(['a', 'B', 'c', 'd'])
    expect(after[0]).toBe(a)
    expect(after[1]).not.toBe(b)
    expect(after[2]).toBe(c)

    const third = deck('a')
    if (!third) throw new Error('no deck')
    patchSlides(first, third, previous)
    expect(children(first).map((e) => e.textContent)).toEqual(['a'])
    expect(first.firstElementChild).toBe(a)
  })

  it('compares against the pristine markup, not the live DOM', () => {
    const first = deck('a', 'b')
    if (!first) throw new Error('no deck')
    const previous = children(first).map((c) => c.outerHTML)
    const [a] = children(first)
    a?.classList.add('marp-active-slide')
    const next = deck('a', 'b')
    if (!next) throw new Error('no deck')
    patchSlides(first, next, previous)
    expect(first.firstElementChild).toBe(a)
  })
})
