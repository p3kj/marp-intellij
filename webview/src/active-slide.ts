import { dataEndLine, dataStartLine } from './content-section'

export interface SlideRange {
  start: number
  end: number
}

/**
 * Index of the slide that contains `line`. Lines in gaps (the `---` separator, trailing
 * blank lines) belong to the last slide starting before them; lines before the first slide
 * (front matter) belong to the first one.
 */
export function findSlideIndex(ranges: readonly SlideRange[], line: number): number {
  if (!ranges.length) return -1
  const inside = ranges.findIndex((r) => line >= r.start && line <= r.end)
  if (inside >= 0) return inside
  let last = 0
  ranges.forEach((r, i) => {
    if (r.start <= line) last = i
  })
  return last
}

export const activeSlideClass = 'marp-active-slide'

export function readSlideRanges(root: ParentNode): { sections: HTMLElement[]; ranges: SlideRange[] } {
  const sections: HTMLElement[] = []
  const ranges: SlideRange[] = []
  for (const s of root.querySelectorAll<HTMLElement>(`section[${dataStartLine}]`)) {
    const start = Number(s.getAttribute(dataStartLine))
    const end = Number(s.getAttribute(dataEndLine) ?? start)
    sections.push(s)
    ranges.push({ start, end })
  }
  return { sections, ranges }
}

/** Marks the wrapper of the slide containing `line`; returns it. */
export function markActiveSlide(root: ParentNode, line: number): HTMLElement | undefined {
  for (const el of root.querySelectorAll(`.${activeSlideClass}`)) el.classList.remove(activeSlideClass)
  const { sections, ranges } = readSlideRanges(root)
  const index = findSlideIndex(ranges, line)
  const wrapper = sections[index]?.closest<HTMLElement>('[data-marp-slide-wrapper]')
  wrapper?.classList.add(activeSlideClass)
  return wrapper ?? undefined
}
