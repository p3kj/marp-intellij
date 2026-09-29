// Ported from microsoft/vscode/extensions/markdown-language-features/preview-src/scroll-sync.ts (MIT)
// Copyright (c) Microsoft Corporation. Licensed under the MIT License.
// Adapted: the layout is injected (`Bounds`), so the math is testable without a browser,
// and the document version cache is replaced by an explicit `collectCodeLines` call.

import { codeLineClass, dataLine } from './line-number'

export interface Bounds {
  top: number
  height: number
}

/** One element that maps to a source line. `bounds()` returns viewport-relative geometry. */
export interface CodeLine {
  readonly line: number
  /** Last source line of a multi-line fenced code block. */
  readonly endLine?: number
  readonly element: HTMLElement
  readonly isCodeBlock: boolean
  bounds(): Bounds
  isVisible(): boolean
}

export interface ScrollEnv {
  scrollY: number
}

interface Neighbours {
  previous?: CodeLine
  next?: CodeLine
}

const boundsOf = (el: Element): Bounds => el.getBoundingClientRect()

function isElementVisible(el: HTMLElement): boolean {
  for (let p = el.parentElement; p; p = p.parentElement) {
    if (p.tagName === 'DETAILS' && !(p as HTMLDetailsElement).open) return false
  }
  const style = window.getComputedStyle(el)
  if (style.display === 'none' || style.visibility === 'hidden') return false
  const b = el.getBoundingClientRect()
  return b.height !== 0 && b.width !== 0
}

/** Bounds of an element, cut off at its first nested code-line child (nested ones are handled separately). */
function elementBounds(entry: CodeLine): Bounds {
  const { element, isCodeBlock } = entry
  const own = boundsOf(element)
  if (isCodeBlock) return own
  const child = element.querySelector(`.${codeLineClass}`)
  if (child) {
    return { top: own.top, height: Math.max(1, boundsOf(child).top - own.top) }
  }
  return own
}

function createEntry(element: HTMLElement, line: number, opts: { codeBlock?: boolean; endLine?: number } = {}): CodeLine {
  const entry: CodeLine = {
    line,
    endLine: opts.endLine,
    element,
    isCodeBlock: !!opts.codeBlock,
    bounds: () => elementBounds(entry),
    isVisible: () => isElementVisible(element),
  }
  return entry
}

/**
 * Collects `.code-line[data-line]` elements in document order. The first entry is a
 * sentinel for the top of the page (line -1), as in VS Code.
 */
export function collectCodeLines(root: ParentNode, body: HTMLElement): CodeLine[] {
  const entries: CodeLine[] = [createEntry(body, -1)]
  for (const element of root.querySelectorAll<HTMLElement>(`.${codeLineClass}`)) {
    const line = Number(element.getAttribute(dataLine))
    if (Number.isNaN(line) || !element.hasAttribute(dataLine)) continue

    if (element.tagName === 'CODE' && element.parentElement?.tagName === 'PRE') {
      // `code-line` sits on <code> for fences, geometry is the parent <pre>.
      const lineCount = ((element.textContent ?? '').match(/\n/g) ?? []).length + 1
      const pre = element.parentElement
      const entry = createEntry(pre, line, { codeBlock: true, endLine: line + lineCount - 1 })
      entries.push(entry)
    } else if (element.tagName === 'PRE') {
      // handled through the <code> child
    } else if (element.tagName === 'UL' || element.tagName === 'OL') {
      // the first <li> has the same line and is preferred
    } else {
      entries.push(createEntry(element, line))
    }
  }
  return entries
}

/** Entries that map to `targetLine`: exact match, or the ones surrounding it. */
export function getElementsForSourceLine(entries: readonly CodeLine[], targetLine: number): Neighbours {
  const lineNumber = Math.floor(targetLine)
  let previous: CodeLine | undefined = entries[0]
  for (const entry of entries) {
    if (entry.line === lineNumber) return { previous: entry }
    if (entry.line > lineNumber) return { previous, next: entry }
    previous = entry
  }
  return { previous }
}

/** Entry pair around a viewport-relative `position` (pixels from viewport top). */
export function getLineElementsAtPosition(entries: readonly CodeLine[], position: number): Neighbours {
  const lines = entries.filter((x) => x.isVisible())
  if (!lines.length) return {}
  let lo = -1
  let hi = lines.length - 1
  while (lo + 1 < hi) {
    const mid = Math.floor((lo + hi) / 2)
    const b = lines[mid].bounds()
    if (b.top + b.height >= position) hi = mid
    else lo = mid
  }
  const hiElement = lines[hi]
  const hiBounds = hiElement.bounds()

  if (hi >= 1 && hiBounds.top > position) {
    return { previous: lines[lo], next: hiElement }
  }
  if (hi > 1 && hi < lines.length && hiBounds.top + hiBounds.height > position) {
    return { previous: hiElement, next: lines[hi + 1] }
  }
  return { previous: hiElement }
}

function contentBounds(entry: CodeLine): Bounds & { paddingTop: number; paddingBottom: number } {
  const b = entry.bounds()
  if (entry.isCodeBlock) {
    const cs = window.getComputedStyle(entry.element)
    const paddingTop = parseFloat(cs.paddingTop) || 0
    const paddingBottom = parseFloat(cs.paddingBottom) || 0
    return { top: b.top + paddingTop, height: b.height - paddingTop - paddingBottom, paddingTop, paddingBottom }
  }
  return { top: b.top, height: b.height, paddingTop: 0, paddingBottom: 0 }
}

/**
 * Absolute page offset (pixels from document top) at which fractional source `line`
 * should be at the top of the viewport. Returns undefined when nothing maps.
 */
export function offsetForLine(entries: readonly CodeLine[], line: number, env: ScrollEnv): number | undefined {
  if (line <= 0) return 0

  const { previous, next } = getElementsForSourceLine(entries, line)
  if (!previous) return undefined

  const rect = previous.bounds()
  const previousTop = rect.top
  let scrollTo: number

  const between = (from: number, prevEnd: number, nextLine: number) => {
    const progress = (line - from) / (nextLine - from)
    const nextTop = env.scrollY + next!.element.getBoundingClientRect().top
    return prevEnd + progress * (nextTop - prevEnd)
  }
  const previousEnd = env.scrollY + previousTop + rect.height

  if (previous.endLine !== undefined && previous.endLine > previous.line) {
    if (line < previous.endLine) {
      const content = contentBounds(previous)
      const progress = (line - previous.line) / (previous.endLine - previous.line)
      scrollTo = env.scrollY + content.top + content.height * progress
    } else if (next && next.line !== previous.line) {
      scrollTo = between(previous.endLine, previousEnd, next.line)
    } else {
      scrollTo = previousEnd
    }
  } else if (next && next.line !== previous.line) {
    scrollTo = between(previous.line, previousEnd, next.line)
  } else {
    scrollTo = env.scrollY + previousTop + rect.height * (line - Math.floor(line))
  }
  return Math.max(1, scrollTo)
}

/** Fractional source line at the top of the viewport (position 0) shifted by `viewportOffset` pixels. */
export function lineForViewportPosition(entries: readonly CodeLine[], viewportOffset = 0): number | null {
  const { previous, next } = getLineElementsAtPosition(entries, viewportOffset)
  if (!previous) return null
  if (previous.line < 0) return 0

  const pb = previous.bounds()
  const offsetFromPrevious = viewportOffset - pb.top

  if (previous.endLine !== undefined && previous.endLine > previous.line) {
    const content = contentBounds(previous)
    const offsetFromContent = viewportOffset - content.top
    if (offsetFromContent >= 0 && offsetFromContent <= content.height) {
      return previous.line + (offsetFromContent / content.height) * (previous.endLine - previous.line)
    }
    if (next && offsetFromContent > content.height) {
      const gapHeight = next.bounds().top - (content.top + content.height)
      if (gapHeight > 0) {
        const progress = (offsetFromContent - content.height) / gapHeight
        return previous.endLine + progress * (next.line - previous.endLine)
      }
    }
  }

  if (next) {
    const span = next.bounds().top - pb.top
    if (span > 0) return previous.line + (offsetFromPrevious / span) * (next.line - previous.line)
    return previous.line
  }
  const h = pb.height
  return previous.line + (h > 0 ? offsetFromPrevious / h : 0)
}
