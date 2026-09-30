// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { overviewClass, slideLineAt } from '../src/overview'

const SVG = 'http://www.w3.org/2000/svg'

/** wrapper > svg > foreignObject > section > h1, like the preview's markup, one per start line. */
function deck(...lines: number[]): HTMLElement {
  const gap = document.createElement('div')
  gap.id = '__marp-preview'
  for (const line of lines) {
    const wrapper = document.createElement('div')
    wrapper.setAttribute('data-marp-slide-wrapper', '')
    const svg = document.createElementNS(SVG, 'svg')
    const foreign = document.createElementNS(SVG, 'foreignObject')
    const section = document.createElement('section')
    section.setAttribute('data-marp-content-start-line', String(line))
    const h1 = document.createElement('h1')
    h1.textContent = `Slide at ${line}`
    section.append(h1)
    foreign.append(section)
    svg.append(foreign)
    wrapper.append(svg)
    gap.append(wrapper)
  }
  document.body.replaceChildren(gap)
  return gap
}

describe('overviewClass', () => {
  it('is the class the stylesheet keys on', () => {
    expect(overviewClass).toBe('marp-overview')
  })
})

describe('slideLineAt', () => {
  it('reads the content start line of the slide a click landed in', () => {
    const preview = deck(2, 10, 18)
    const wrappers = Array.from(preview.children)
    expect(slideLineAt(wrappers[1]!.querySelector('h1'))).toBe(10)
    expect(slideLineAt(wrappers[2]!.querySelector('section'))).toBe(18)
    expect(slideLineAt(wrappers[0]!.querySelector('svg'))).toBe(2)
  })

  it('works for the wrapper itself (the svg ignores pointer events in the grid)', () => {
    const preview = deck(0, 7)
    expect(slideLineAt(preview.children[1]!)).toBe(7)
  })

  it('is undefined in the gap between thumbnails and for no target', () => {
    const preview = deck(0, 7)
    expect(slideLineAt(preview)).toBeUndefined()
    expect(slideLineAt(document.body)).toBeUndefined()
    expect(slideLineAt(null)).toBeUndefined()
    expect(slideLineAt(window)).toBeUndefined()
  })

  it('is undefined for a wrapper without a content section or with a broken line', () => {
    const preview = deck(3)
    const wrapper = preview.children[0]!
    wrapper.querySelector('section')!.setAttribute('data-marp-content-start-line', 'x')
    expect(slideLineAt(wrapper)).toBeUndefined()
    wrapper.querySelector('section')!.removeAttribute('data-marp-content-start-line')
    expect(slideLineAt(wrapper)).toBeUndefined()
  })
})
