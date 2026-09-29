// Ported from microsoft/vscode/extensions/markdown-language-features/preview-src/scroll-sync.ts (MIT)
// Copyright (c) Microsoft Corporation. Licensed under the MIT License.
// Adapted: the layout is injected (`Bounds`), so the math is testable without a browser,
// and the document version cache is replaced by an explicit `collectCodeLines` call.
// Changed: `offsetForLine` is the exact inverse of `lineForViewportPosition` (VS Code maps editor lines onto the gaps
// between elements one way and onto element tops the other way), so a position the user scrolled to comes back to the
// same pixel when it is re-applied after a resize; and VS Code's `hi > 1` off-by-one in `getLineElementsAtPosition` is
// fixed.

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
    ...(opts.endLine !== undefined ? { endLine: opts.endLine } : {}),
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

/**
 * Entries around source `line`: `previous` is the last one at or before it (on an exact line the first of several with
 * that line, e.g. the slide wrapper before its first heading, so the whole slide shows), `next` the one after `previous`.
 * They are adjacent in `entries`, the same pairs `getLineElementsAtPosition` finds for positions between their tops.
 */
export function getElementsForSourceLine(entries: readonly CodeLine[], line: number): Neighbours {
  let index = -1
  for (const [i, entry] of entries.entries()) {
    if (entry.line > line) break
    if (entry.line !== line || entries[index]?.line !== line) index = i
  }
  const previous = entries[index]
  if (!previous) return {}
  const next = entries[index + 1]
  return next ? { previous, next } : { previous }
}

/** Entry pair around a viewport-relative `position` (pixels from viewport top), among the visible entries. */
export function getLineElementsAtPosition(entries: readonly CodeLine[], position: number): Neighbours {
  const lines = entries.filter((x) => x.isVisible())
  // First entry that ends below `position`. Strictly below: an element that ends exactly where the next one starts
  // hands over to that one, so an element top at the viewport top reports the element's own line.
  let lo = -1
  let hi = lines.length - 1
  while (lo + 1 < hi) {
    const mid = Math.floor((lo + hi) / 2)
    // lo < mid < hi <= lines.length - 1, so the entry exists.
    const b = lines[mid]!.bounds()
    if (b.top + b.height > position) hi = mid
    else lo = mid
  }
  const hiElement = lines[hi]
  if (!hiElement) return {}
  const hiBounds = hiElement.bounds()

  // In the gap above hiElement. The loop leaves lo = hi - 1; hi = 0 is the top-of-page sentinel, which has no previous.
  const before = lines[hi - 1]
  if (before && hiBounds.top > position) return { previous: before, next: hiElement }
  // Inside hiElement (VS Code has `hi > 1` here, which drops `next` inside the first real entry: a jump in the reported
  // line while scrolling through the first slide's top padding).
  const after = lines[hi + 1]
  if (hi >= 1 && after && hiBounds.top + hiBounds.height > position) return { previous: hiElement, next: after }
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
 * Absolute page offset (pixels from document top) at which fractional source `line` should be at the top of the
 * viewport, the inverse of `lineForViewportPosition`: linear between the tops of the entries before and after the line,
 * proportional to the content inside a fenced code block, one element height per line past the last entry. Returns
 * undefined when nothing maps.
 */
export function offsetForLine(entries: readonly CodeLine[], line: number, env: ScrollEnv): number | undefined {
  if (line <= 0) return 0

  // Visible entries only, like the inverse: a closed <details> has zero-size children that would pin the offset.
  const { previous, next } = getElementsForSourceLine(entries.filter((x) => x.isVisible()), line)
  if (!previous) return undefined
  const b = previous.bounds()
  let y: number

  if (previous.endLine !== undefined && previous.endLine > previous.line) {
    const content = contentBounds(previous)
    const contentEnd = content.top + content.height
    if (line <= previous.endLine) {
      y = content.top + content.height * ((line - previous.line) / (previous.endLine - previous.line))
    } else if (next) {
      const gapHeight = Math.max(0, next.bounds().top - contentEnd)
      y = contentEnd + gapHeight * ((line - previous.endLine) / (next.line - previous.endLine))
    } else {
      y = contentEnd + b.height * (line - previous.endLine)
    }
  } else if (next && next.line > previous.line) {
    y = b.top + (next.bounds().top - b.top) * ((line - previous.line) / (next.line - previous.line))
  } else if (next) {
    // `line` is exactly the line of `previous` and `next` (a run of entries on one line).
    y = b.top
  } else {
    y = b.top + b.height * (line - previous.line)
  }
  return Math.max(0, env.scrollY + y)
}

/** Fractional source line at the top of the viewport (position 0) shifted by `viewportOffset` pixels. */
export function lineForViewportPosition(entries: readonly CodeLine[], viewportOffset = 0): number | null {
  const { previous, next } = getLineElementsAtPosition(entries, viewportOffset)
  if (!previous) return null
  if (previous.line < 0) return 0

  const pb = previous.bounds()

  if (previous.endLine !== undefined && previous.endLine > previous.line) {
    const content = contentBounds(previous)
    const offsetFromContent = viewportOffset - content.top
    const contentEnd = content.top + content.height
    // The block's top padding still shows its first line (VS Code interpolates towards `next` here, which then jumps
    // back at the content top).
    if (offsetFromContent < 0) return previous.line
    if (offsetFromContent <= content.height) {
      const progress = content.height > 0 ? offsetFromContent / content.height : 0
      return previous.line + progress * (previous.endLine - previous.line)
    }
    if (next) {
      const gapHeight = next.bounds().top - contentEnd
      if (gapHeight <= 0) return previous.endLine
      return previous.endLine + ((viewportOffset - contentEnd) / gapHeight) * (next.line - previous.endLine)
    }
    return previous.endLine + (pb.height > 0 ? (viewportOffset - contentEnd) / pb.height : 0)
  }

  const offsetFromPrevious = viewportOffset - pb.top
  if (next) {
    const span = next.bounds().top - pb.top
    if (span > 0) return previous.line + (offsetFromPrevious / span) * (next.line - previous.line)
    return previous.line
  }
  const h = pb.height
  return previous.line + (h > 0 ? offsetFromPrevious / h : 0)
}
