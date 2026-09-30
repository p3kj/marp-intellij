// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { Marp } from '@marp-team/marp-core'
import { dropSlot, indicatorFor, moveTarget, usesHeadingDivider, type Box } from '../src/slide-drag'

/** 3 columns x 2 rows of 100x60 thumbnails with 24px gaps, in reading order. Column middles are 50, 174 and 298. */
const grid: Box[] = [0, 1, 2, 3, 4, 5].map((i) => {
  const left = (i % 3) * 124
  const top = Math.floor(i / 3) * 84
  return { left, top, right: left + 100, bottom: top + 60 }
})

describe('dropSlot', () => {
  it('is the slot before a box when the pointer is in its left half', () => {
    expect(dropSlot(grid, 10, 30)).toBe(0)
    expect(dropSlot(grid, 130, 30)).toBe(1)
    expect(dropSlot(grid, 250, 30)).toBe(2)
    expect(dropSlot(grid, 10, 100)).toBe(3)
    expect(dropSlot(grid, 250, 100)).toBe(5)
  })

  it('is the slot after a box when the pointer is in its right half', () => {
    expect(dropSlot(grid, 90, 30)).toBe(1)
    expect(dropSlot(grid, 214, 30)).toBe(2)
    expect(dropSlot(grid, 130, 100)).toBe(4)
    expect(dropSlot(grid, 214, 100)).toBe(5)
  })

  it('is between two boxes for the gap between them', () => {
    expect(dropSlot(grid, 112, 30)).toBe(1)
    expect(dropSlot(grid, 236, 100)).toBe(5)
  })

  it('is the start of the next row for the gap between rows', () => {
    expect(dropSlot(grid, 10, 72)).toBe(3)
    expect(dropSlot(grid, 300, 72)).toBe(3)
  })

  it('is after the last box of a row when the pointer is to the right of the row', () => {
    expect(dropSlot(grid, 340, 30)).toBe(3)
    expect(dropSlot(grid, 900, 30)).toBe(3)
  })

  it('is the start above everything and the end below everything or right of the last row', () => {
    expect(dropSlot(grid, 200, -10)).toBe(0)
    expect(dropSlot(grid, 200, 500)).toBe(6)
    expect(dropSlot(grid, 900, 100)).toBe(6)
  })

  it('handles no boxes and one box', () => {
    expect(dropSlot([], 5, 5)).toBe(0)
    expect(dropSlot([grid[0]!], 10, 10)).toBe(0)
    expect(dropSlot([grid[0]!], 90, 10)).toBe(1)
  })
})

describe('moveTarget', () => {
  it('is nothing for the two slots around the slide itself', () => {
    expect(moveTarget(2, 2)).toBeUndefined()
    expect(moveTarget(2, 3)).toBeUndefined()
    expect(moveTarget(0, 0)).toBeUndefined()
    expect(moveTarget(0, 1)).toBeUndefined()
  })

  it('is the slot itself moving up and one less moving down', () => {
    expect(moveTarget(3, 0)).toBe(0)
    expect(moveTarget(3, 2)).toBe(2)
    expect(moveTarget(0, 2)).toBe(1)
    expect(moveTarget(0, 6)).toBe(5)
    expect(moveTarget(4, 6)).toBe(5)
  })
})

describe('indicatorFor', () => {
  it('goes before the box at the slot, or after the previous one when the pointer is in its row', () => {
    expect(indicatorFor(grid, 0, 30)).toEqual({ index: 0, side: 'before' })
    expect(indicatorFor(grid, 1, 30)).toEqual({ index: 0, side: 'after' })
    expect(indicatorFor(grid, 4, 100)).toEqual({ index: 3, side: 'after' })
  })

  it('goes before the first box of a row unless the pointer is at the end of the row above', () => {
    // Right of the first row: after its last box.
    expect(indicatorFor(grid, 3, 30)).toEqual({ index: 2, side: 'after' })
    // In the gap between rows, or in the second row: before the first box of that row.
    expect(indicatorFor(grid, 3, 72)).toEqual({ index: 3, side: 'before' })
    expect(indicatorFor(grid, 3, 100)).toEqual({ index: 3, side: 'before' })
  })

  it('goes after the last box at the end of the deck', () => {
    expect(indicatorFor(grid, 6, 500)).toEqual({ index: 5, side: 'after' })
    expect(indicatorFor(grid, 6, 100)).toEqual({ index: 5, side: 'after' })
  })
})

describe('usesHeadingDivider', () => {
  it('reads the directive Marpit recorded, a number or a non-empty list', () => {
    expect(usesHeadingDivider({ lastGlobalDirectives: { headingDivider: 2 } })).toBe(true)
    expect(usesHeadingDivider({ lastGlobalDirectives: { headingDivider: [1, 3] } })).toBe(true)
  })

  it('is false without a divider', () => {
    expect(usesHeadingDivider({ lastGlobalDirectives: {} })).toBe(false)
    expect(usesHeadingDivider({ lastGlobalDirectives: { headingDivider: false } })).toBe(false)
    expect(usesHeadingDivider({ lastGlobalDirectives: { headingDivider: [] } })).toBe(false)
    expect(usesHeadingDivider({ lastGlobalDirectives: { headingDivider: 'x' } })).toBe(false)
    expect(usesHeadingDivider({})).toBe(false)
    expect(usesHeadingDivider(undefined)).toBe(false)
    expect(usesHeadingDivider(null)).toBe(false)
  })

  it('takes the option of Marpit when the deck has no directive, and the directive over it', () => {
    expect(usesHeadingDivider({ options: { headingDivider: 1 } })).toBe(true)
    expect(usesHeadingDivider({ lastGlobalDirectives: {}, options: { headingDivider: 1 } })).toBe(true)
    expect(usesHeadingDivider({ lastGlobalDirectives: { headingDivider: false }, options: { headingDivider: 1 } })).toBe(false)
  })

  it('survives an object that throws', () => {
    const marp = {
      get lastGlobalDirectives(): never {
        throw new Error('boom')
      },
    }
    expect(usesHeadingDivider(marp)).toBe(false)
  })

  it('agrees with a real render', () => {
    const render = (markdown: string) => {
      const marp = new Marp({ inlineSVG: { backdropSelector: false }, script: false })
      marp.render(markdown)
      return usesHeadingDivider(marp)
    }
    expect(render('---\nmarp: true\nheadingDivider: 2\n---\n\n# A\n\n## B\n')).toBe(true)
    expect(render('---\nheadingDivider: [1, 2]\n---\n\n# A\n')).toBe(true)
    expect(render('---\nmarp: true\n---\n\n# A\n\n---\n\n# B\n')).toBe(false)
    expect(render('<!-- headingDivider: 1 -->\n\n# A\n')).toBe(true)
    // The last render decides: a Marp instance is reused for the next update.
    const marp = new Marp({ inlineSVG: { backdropSelector: false }, script: false })
    marp.render('---\nheadingDivider: 2\n---\n\n# A\n')
    expect(usesHeadingDivider(marp)).toBe(true)
    marp.render('# A\n')
    expect(usesHeadingDivider(marp)).toBe(false)
  })
})
