// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { createMarp } from '../src/marp-factory'
import { dataEndLine, dataStartLine } from '../src/content-section'

const render = (markdown: string) => {
  const { marp } = createMarp({ html: 'default', math: 'off' }, [])
  const { html } = marp.render(markdown)
  return new DOMParser().parseFromString(`<body>${html}</body>`, 'text/html').body
}

const deck = [
  '---', // 0
  'marp: true', // 1
  '---', // 2
  '', // 3
  '# Title', // 4
  '', // 5
  'Paragraph', // 6
  '', // 7
  '---', // 8
  '', // 9
  '<!-- _class: lead -->', // 10
  '## Second', // 11
  '', // 12
  '- a', // 13
  '- b', // 14
  '', // 15
  '```js', // 16
  'x', // 17
  'y', // 18
  '```', // 19
  '', // 20
  '---', // 21
  '', // 22
  'Third', // 23
].join('\n')

describe('line-number plugin', () => {
  const body = render(deck)

  it('puts data-line and code-line on the slide wrapper, not on the section', () => {
    const wrappers = [...body.querySelectorAll('[data-marp-slide-wrapper]')]
    expect(wrappers).toHaveLength(3)
    for (const w of wrappers) {
      expect(w.classList.contains('code-line')).toBe(true)
      expect(w.hasAttribute('data-line')).toBe(true)
    }
    for (const s of body.querySelectorAll('section')) {
      expect(s.classList.contains('code-line')).toBe(false)
      expect(s.hasAttribute('data-line')).toBe(false)
    }
  })

  it('maps block elements to their source line', () => {
    const line = (sel: string) => Number(body.querySelector(sel)!.getAttribute('data-line'))
    expect(line('h1')).toBe(4)
    expect(line('h2')).toBe(11)
    expect(line('li')).toBe(13)
    expect(line('pre code')).toBe(16)
    expect(body.querySelectorAll('li')[1].getAttribute('data-line')).toBe('14')
  })

  it('does not annotate inline content', () => {
    const p = render('Hello **bold**\n')
    expect(p.querySelector('strong')!.hasAttribute('data-line')).toBe(false)
  })
})

describe('content-section plugin', () => {
  const sections = [...render(deck).querySelectorAll('section')]
  const range = (s: Element) => [Number(s.getAttribute(dataStartLine)), Number(s.getAttribute(dataEndLine))]

  it('emits start and end line for every slide', () => {
    expect(sections).toHaveLength(3)
    for (const s of sections) {
      expect(s.hasAttribute(dataStartLine)).toBe(true)
      expect(s.hasAttribute(dataEndLine)).toBe(true)
    }
  })

  it('slides are contiguous and ordered', () => {
    const r = sections.map(range)
    expect(r[0][0]).toBe(0)
    expect(r[0][1]).toBeGreaterThanOrEqual(6)
    expect(r[1][0]).toBeLessThanOrEqual(11)
    expect(r[1][0]).toBeGreaterThan(r[0][1] - 1)
    expect(r[1][1]).toBeGreaterThanOrEqual(19)
    expect(r[2][0]).toBeGreaterThan(r[1][1])
    expect(r[2][1]).toBeGreaterThanOrEqual(23)
  })

  it('directive comments belong to their slide', () => {
    const [start, end] = range(sections[1])
    expect(10).toBeGreaterThanOrEqual(start)
    expect(10).toBeLessThanOrEqual(end)
  })

  it('handles a single slide without separators', () => {
    const [s] = [...render('# One\n\ntext\n').querySelectorAll('section')]
    // the end is the exclusive end of the last block (marp-vscode behavior)
    expect(range(s)).toEqual([0, 3])
  })
})

describe('container', () => {
  it('does not share its id with a heading slug', () => {
    const body = render('# Marp Preview\n\n## marp preview\n')
    const ids = [...body.querySelectorAll('[id]')].map((el) => el.id)
    expect(ids.filter((id) => id === ids[0])).toHaveLength(1)
    expect(new Set(ids).size).toBe(ids.length)
  })
})
