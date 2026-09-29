// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { findSlideIndex, markActiveSlide } from '../src/active-slide'

const ranges = [
  { start: 0, end: 9 },
  { start: 10, end: 20 },
  { start: 23, end: 30 },
]

describe('findSlideIndex', () => {
  it('finds the containing slide', () => {
    expect(findSlideIndex(ranges, 0)).toBe(0)
    expect(findSlideIndex(ranges, 15)).toBe(1)
    expect(findSlideIndex(ranges, 30)).toBe(2)
  })
  it('puts gap and out-of-range lines on the previous slide', () => {
    expect(findSlideIndex(ranges, 21)).toBe(1)
    expect(findSlideIndex(ranges, 99)).toBe(2)
  })
  it('handles empty input and lines before the first slide', () => {
    expect(findSlideIndex([], 3)).toBe(-1)
    expect(findSlideIndex([{ start: 5, end: 8 }], 1)).toBe(0)
  })
})

describe('markActiveSlide', () => {
  it('moves the class between slide wrappers', () => {
    document.body.innerHTML = ranges
      .map((r) => `<div data-marp-slide-wrapper><section data-marp-content-start-line="${r.start}" data-marp-content-end-line="${r.end}"></section></div>`)
      .join('')
    markActiveSlide(document, 12)
    markActiveSlide(document, 25)
    const active = [...document.querySelectorAll('[data-marp-slide-wrapper]')].map((e) => e.classList.contains('marp-active-slide'))
    expect(active).toEqual([false, false, true])
  })
})
