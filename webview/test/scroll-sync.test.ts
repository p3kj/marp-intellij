// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { lineForViewportPosition, offsetForLine, type CodeLine } from '../src/scroll-sync'

/** Fake layout: each entry occupies [top, top + height) in page coordinates; viewport top = scrollY. */
function layout(spec: { line: number; top: number; height: number; endLine?: number }[], scrollY: number): CodeLine[] {
  return spec.map((s) => {
    const element = document.createElement('div')
    const rect = () => ({ top: s.top - scrollY, height: s.height, width: 100 }) as DOMRect
    element.getBoundingClientRect = rect
    return {
      line: s.line,
      endLine: s.endLine,
      element,
      isCodeBlock: false,
      bounds: () => ({ top: s.top - scrollY, height: s.height }),
      isVisible: () => true,
    }
  })
}

// sentinel (-1) plus three blocks with gaps of 20px
const spec = [
  { line: -1, top: 0, height: 10 },
  { line: 0, top: 10, height: 100 }, // slide wrapper 0..110
  { line: 10, top: 130, height: 100 }, // 130..230
  { line: 20, top: 250, height: 100 }, // 250..350
]

describe('offsetForLine', () => {
  it('scrolls to the top for line <= 0', () => {
    expect(offsetForLine(layout(spec, 0), 0, { scrollY: 0 })).toBe(0)
  })

  it('exact line lands on the element top', () => {
    expect(offsetForLine(layout(spec, 0), 10, { scrollY: 0 })).toBe(130)
  })

  it('interpolates fractionally inside a block', () => {
    expect(offsetForLine(layout(spec, 0), 10.5, { scrollY: 0 })).toBe(180)
  })

  it('interpolates between elements over the gap', () => {
    // line 15 is not an element line: between block(10) and block(20) -> halfway through the gap after 230
    expect(offsetForLine(layout(spec, 0), 15, { scrollY: 0 })).toBe(230 + 10)
  })

  it('gives the same answer regardless of the current scroll position', () => {
    expect(offsetForLine(layout(spec, 77), 15, { scrollY: 77 })).toBe(240)
  })

  it('past the last element stays inside it', () => {
    expect(offsetForLine(layout(spec, 0), 25, { scrollY: 0 })).toBe(250 + 100 * 0)
  })
})

describe('lineForViewportPosition', () => {
  it('returns 0 at the very top', () => {
    expect(lineForViewportPosition(layout(spec, 0), 0)).toBe(0)
  })

  it('round-trips lines that have an element, and is monotonic in between', () => {
    for (const line of [10, 20]) {
      const y = offsetForLine(layout(spec, 0), line, { scrollY: 0 })!
      expect(lineForViewportPosition(layout(spec, y), 0)).toBeCloseTo(line, 5)
    }
    // VS Code's forward and inverse interpolation differ slightly between elements: only require order.
    let last = -Infinity
    for (const line of [10, 12, 15, 18, 20]) {
      const y = offsetForLine(layout(spec, 0), line, { scrollY: 0 })!
      const back = lineForViewportPosition(layout(spec, y), 0)!
      expect(back).toBeGreaterThan(last)
      last = back
    }
  })

  it('reports part way into an element', () => {
    // scrollY 180 is the middle of block 10 (130..230)
    expect(lineForViewportPosition(layout(spec, 180), 0)).toBeCloseTo(10 + (50 / (250 - 130)) * 10, 5)
  })
})

describe('fenced code blocks', () => {
  const codeSpec = [
    { line: -1, top: 0, height: 10 },
    { line: 4, top: 20, height: 100, endLine: 9 },
    { line: 12, top: 140, height: 50 },
  ]

  it('maps lines inside a fence proportionally to its height', () => {
    const y = offsetForLine(layout(codeSpec, 0), 6.5, { scrollY: 0 })!
    // progress (6.5-4)/(9-4) = 0.5 of a 100px block starting at 20
    expect(y).toBe(70)
    expect(lineForViewportPosition(layout(codeSpec, y), 0)).toBeCloseTo(6.5, 5)
  })
})
