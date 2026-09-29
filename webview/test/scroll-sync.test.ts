// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { getLineElementsAtPosition, lineForViewportPosition, offsetForLine, type CodeLine } from '../src/scroll-sync'

interface Spec {
  line: number
  top: number
  height: number
  endLine?: number
  /** A fenced code block with this vertical padding (px) around its content. */
  codePadding?: number
}

/** Fake layout: each entry occupies [top, top + height) in page coordinates; viewport top = scrollY. */
function layout(spec: Spec[], scrollY: number): CodeLine[] {
  return spec.map((s) => {
    const element = document.createElement('div')
    const rect = () => ({ top: s.top - scrollY, height: s.height, width: 100 }) as DOMRect
    element.getBoundingClientRect = rect
    if (s.codePadding !== undefined) {
      element.style.paddingTop = `${s.codePadding}px`
      element.style.paddingBottom = `${s.codePadding}px`
    }
    return {
      line: s.line,
      ...(s.endLine !== undefined ? { endLine: s.endLine } : {}),
      element,
      isCodeBlock: s.codePadding !== undefined,
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

  it('interpolates fractional lines between the element tops', () => {
    // 10.5 is 5% of the way from line 10 (top 130) to line 20 (top 250)
    expect(offsetForLine(layout(spec, 0), 10.5, { scrollY: 0 })).toBe(136)
  })

  it('interpolates lines without an element between the element tops', () => {
    expect(offsetForLine(layout(spec, 0), 15, { scrollY: 0 })).toBe(190)
  })

  it('gives the same answer regardless of the current scroll position', () => {
    expect(offsetForLine(layout(spec, 77), 15, { scrollY: 77 })).toBe(190)
  })

  it('continues one element height per line past the last element', () => {
    expect(offsetForLine(layout(spec, 0), 20.5, { scrollY: 0 })).toBe(300)
    expect(offsetForLine(layout(spec, 0), 22, { scrollY: 0 })).toBe(450)
  })

  it('lands on 0, not 1, for an element at the very top of the page', () => {
    const top = [
      { line: -1, top: 0, height: 0 },
      { line: 3, top: 0, height: 50 },
      { line: 9, top: 50, height: 50 },
    ]
    expect(offsetForLine(layout(top, 0), 3, { scrollY: 0 })).toBe(0)
  })

  it('goes to the first of several entries on one line (the slide wrapper, not its heading)', () => {
    const shared = [
      { line: -1, top: 0, height: 10 },
      { line: 5, top: 10, height: 40 }, // wrapper, cut at the heading
      { line: 5, top: 50, height: 30 }, // heading on the same line
      { line: 7, top: 100, height: 20 },
    ]
    expect(offsetForLine(layout(shared, 0), 5, { scrollY: 0 })).toBe(10)
    expect(offsetForLine(layout(shared, 0), 6, { scrollY: 0 })).toBe(75)
  })
})

describe('getLineElementsAtPosition', () => {
  it('inside the first real entry, pairs it with the next one (VS Code returns no next there)', () => {
    const first = [
      { line: -1, top: 0, height: 10 },
      { line: 0, top: 10, height: 30 }, // slide wrapper, cut at its heading
      { line: 4, top: 40, height: 20 }, // heading
    ]
    const entries = layout(first, 0)
    const at = getLineElementsAtPosition(entries, 25)
    expect(at.previous).toBe(entries[1])
    expect(at.next).toBe(entries[2])
    // half way through the wrapper is half way to the heading's line, not 0.5
    expect(lineForViewportPosition(entries, 25)).toBeCloseTo(2, 5)
  })

  it('inside the last entry, has no next (no read past the end)', () => {
    const entries = layout(spec, 0)
    const at = getLineElementsAtPosition(entries, 300)
    expect(at).toStrictEqual({ previous: entries[3] })
    expect('next' in at).toBe(false)
  })

  it('hands over at an exact boundary to the element that starts there', () => {
    const touching = [
      { line: -1, top: 0, height: 10 },
      { line: 0, top: 10, height: 30 },
      { line: 4, top: 40, height: 20 },
    ]
    const entries = layout(touching, 0)
    expect(getLineElementsAtPosition(entries, 40).previous).toBe(entries[2])
  })
})

describe('lineForViewportPosition', () => {
  it('returns 0 at the very top', () => {
    expect(lineForViewportPosition(layout(spec, 0), 0)).toBe(0)
  })

  it('reports part way into an element', () => {
    // scrollY 180 is the middle of block 10 (130..230)
    expect(lineForViewportPosition(layout(spec, 180), 0)).toBeCloseTo(10 + (50 / (250 - 130)) * 10, 5)
  })
})

describe('round trip', () => {
  // Two slides: wrappers cut at their first heading, blocks with gaps, a fenced code block with padding.
  const deck: Spec[] = [
    { line: -1, top: 0, height: 10 },
    { line: 0, top: 10, height: 30 }, // wrapper 1, ends where its heading starts
    { line: 4, top: 40, height: 20 }, // # heading
    { line: 6, top: 80, height: 20 }, // paragraph
    { line: 8, endLine: 11, top: 120, height: 60, codePadding: 8 }, // fence, content 128..172
    { line: 14, top: 200, height: 20 }, // paragraph
    { line: 16, top: 260, height: 40 }, // wrapper 2
    { line: 18, top: 300, height: 30 }, // ## heading, last entry
  ]

  it('maps a line to a position that maps back to the same line', () => {
    const lines = [0, 0.5, 2, 4, 4.5, 5, 6, 7.25, 8, 9, 10.5, 11, 12, 13.5, 14, 15, 16, 17, 18, 18.5, 20]
    for (const line of lines) {
      const y = offsetForLine(layout(deck, 0), line, { scrollY: 0 })
      expect(y, `line ${line}`).toBeDefined()
      expect(lineForViewportPosition(layout(deck, y ?? 0), 0), `line ${line} at ${y}`).toBeCloseTo(line, 6)
    }
  })

  it('maps a position to a line that maps back to the same position', () => {
    // Skips the two ranges that show one line throughout, so any position in them maps to their start: the page top
    // above the first wrapper (line 0) and the fence's top padding (line 8).
    for (let y = 11; y <= 330; y += 7) {
      if (y >= 120 && y < 128) continue
      const line = lineForViewportPosition(layout(deck, y), 0)
      expect(line, `y ${y}`).not.toBeNull()
      expect(offsetForLine(layout(deck, 0), line ?? 0, { scrollY: 0 }), `y ${y} line ${line}`).toBeCloseTo(y, 6)
    }
  })
})

describe('fenced code blocks', () => {
  const codeSpec = [
    { line: -1, top: 0, height: 10 },
    { line: 4, top: 20, height: 100, endLine: 9 },
    { line: 12, top: 140, height: 50 },
  ]

  it('maps lines inside a fence proportionally to its height', () => {
    const y = offsetForLine(layout(codeSpec, 0), 6.5, { scrollY: 0 })
    // progress (6.5-4)/(9-4) = 0.5 of a 100px block starting at 20
    expect(y).toBe(70)
    expect(lineForViewportPosition(layout(codeSpec, 70), 0)).toBeCloseTo(6.5, 5)
  })

  it('reports the first line in the top padding instead of running ahead', () => {
    const padded = [
      { line: -1, top: 0, height: 10 },
      { line: 4, top: 20, height: 100, endLine: 9, codePadding: 10 },
      { line: 12, top: 140, height: 50 },
    ]
    expect(lineForViewportPosition(layout(padded, 25), 0)).toBe(4)
  })
})
