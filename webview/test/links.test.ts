// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { findLink } from '../src/links'
import { parseFragment } from '../src/patch'

const BASE = 'https://marp.localhost/doc/home/me/deck/'

function fragment(html: string): HTMLElement {
  const div = document.createElement('div')
  div.innerHTML = html
  document.body.replaceChildren(div)
  return div
}

describe('findLink', () => {
  it('finds HTML anchors from nested targets and resolves them against the base', () => {
    const root = fragment('<a href="other.md"><strong id="t">x</strong></a>')
    expect(findLink(root.querySelector('#t'), BASE)).toEqual({ raw: 'other.md', href: `${BASE}other.md` })
  })

  it('finds image map areas and SVG links', () => {
    const root = fragment(
      '<map name="m"><area id="area" href="about:blank"></map>' +
        '<svg><a id="svg2" href="https://example.com/"><text id="t2">a</text></a>' +
        '<a id="svg1" xlink:href="#slide-2"><text id="t1">b</text></a></svg>',
    )
    expect(findLink(root.querySelector('#area'), BASE)?.href).toBe('about:blank')
    expect(findLink(root.querySelector('#t2'), BASE)?.href).toBe('https://example.com/')
    expect(findLink(root.querySelector('#t1'), BASE)).toEqual({ raw: '#slide-2', href: `${BASE}#slide-2` })
  })

  it('ignores anchors without a link and other elements', () => {
    const root = fragment('<a id="named" name="x">x</a><p id="p">p</p>')
    expect(findLink(root.querySelector('#named'), BASE)).toBeNull()
    expect(findLink(root.querySelector('#p'), BASE)).toBeNull()
    expect(findLink(null, BASE)).toBeNull()
  })
})

describe('parseFragment', () => {
  it('drops meta http-equiv so a deck cannot navigate the preview away', () => {
    const el = parseFragment(document, '<div id="c"><section><META HTTP-EQUIV="refresh" content="0;url=about:blank"><meta charset="utf-8"><p>x</p></section></div>')!
    expect(el.querySelectorAll('meta[http-equiv]')).toHaveLength(0)
    expect(el.querySelectorAll('meta')).toHaveLength(1)
    expect(el.querySelector('p')?.textContent).toBe('x')
  })
})
