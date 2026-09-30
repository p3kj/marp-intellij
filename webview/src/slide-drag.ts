import { slideLineAt } from './overview'

/** Classes of the slide drag in the overview, styled in preview.css. */
export const dragClass = 'marp-slide-drag'
export const draggingClass = 'marp-dragging'
export const dropBeforeClass = 'marp-drop-before'
export const dropAfterClass = 'marp-drop-after'

/** How far (px) the pointer must move after the press before it is a drag and not a click. */
export const dragThreshold = 5

const wrapperSelector = '[data-marp-slide-wrapper]'

/** A thumbnail's rectangle in client coordinates (what `getBoundingClientRect` returns). */
export interface Box {
  left: number
  top: number
  right: number
  bottom: number
}

/**
 * The insertion slot (0..n) for a pointer at `x`, `y` over `boxes`, the thumbnails in reading order (row by row, as the
 * grid lays them out): the slot before the first box that is below the pointer, or in the pointer's row and to the right
 * of it, so the left half of a box means "before it". A pointer in a gap between rows goes to the start of the next row,
 * to the right of a row's last box goes after that box, and below everything goes to the end (`n`).
 */
export function dropSlot(boxes: readonly Box[], x: number, y: number): number {
  const index = boxes.findIndex((b) => y < b.top || (y <= b.bottom && x < (b.left + b.right) / 2))
  return index < 0 ? boxes.length : index
}

/**
 * The index slide `from` has after it is dropped in `slot`, `undefined` when that changes nothing (the slots right before
 * and right after the slide are its own place).
 */
export function moveTarget(from: number, slot: number): number | undefined {
  if (slot === from || slot === from + 1) return undefined
  return slot > from ? slot - 1 : slot
}

/**
 * Which thumbnail shows the insertion mark for `slot`, and on which side. The mark goes after the previous thumbnail when
 * the pointer is in its row (at the end of a row, so it is not drawn at the start of the next row), else before the
 * thumbnail at `slot`; the end of the deck has no thumbnail to go before, so it goes after the last one.
 */
export function indicatorFor(boxes: readonly Box[], slot: number, y: number): { index: number; side: 'before' | 'after' } {
  const previous = boxes[slot - 1]
  if (previous && ((y >= previous.top && y <= previous.bottom) || slot >= boxes.length)) return { index: slot - 1, side: 'after' }
  return { index: slot, side: 'before' }
}

/**
 * Whether the deck the last render was of uses `headingDivider` (a number or a non-empty list of heading levels, from a
 * directive or Marpit's option). Marpit records the directive in `lastGlobalDirectives`, which is protected, so it is read
 * through a cast like the title in export-html.ts. Reordering is not offered then: the host refuses it too.
 */
export function usesHeadingDivider(marp: unknown): boolean {
  try {
    const { lastGlobalDirectives, options } = marp as {
      lastGlobalDirectives?: Record<string, unknown>
      options?: { headingDivider?: unknown }
    }
    const value =
      lastGlobalDirectives && Object.prototype.hasOwnProperty.call(lastGlobalDirectives, 'headingDivider')
        ? lastGlobalDirectives.headingDivider
        : options?.headingDivider
    return typeof value === 'number' ? Number.isInteger(value) && value >= 1 && value <= 6 : Array.isArray(value) && value.length > 0
  } catch {
    return false
  }
}

export interface SlideMove {
  /** 0-based indices of the thumbnails: the dragged one and the place it has after the move. */
  from: number
  to: number
  /** Content start line of the dragged slide. */
  line: number
  /** Number of thumbnails on the page. */
  count: number
}

export interface SlideDragOptions {
  /** The element that holds the slides (`#marp-root`); it gets `dragClass` while a drag is on. */
  root: HTMLElement
  /** Whether a press starts a drag: the overview is on and the deck can be reordered. */
  enabled(): boolean
  /** A thumbnail was dropped on another place. */
  post(move: SlideMove): void
}

interface Press {
  startX: number
  startY: number
  x: number
  y: number
  dragging: boolean
  from: number
  line: number
  count: number
  dragged: HTMLElement
}

/**
 * Drag and drop of the thumbnails in the slide overview, from plain mouse events. HTML5 drag and drop is not usable: the
 * preview browser renders off-screen and its drag handler refuses every native drag. Press on a thumbnail, move more
 * than `dragThreshold`, and an insertion mark follows the pointer; releasing posts the move, Escape (or losing the focus)
 * cancels. Mouse moves are not checked for the button state (`buttons`): off-screen rendering may not set it, and a drag
 * that never starts is worse than one that ends late. The release reaches the page even outside it, and the next press
 * starts afresh anyway. The click that ends a drag is swallowed, so the drop does not also move the caret to a slide.
 * Wheel scrolling during a drag works, the mark follows; there is no auto-scroll at the edges.
 */
export function installSlideDrag(doc: Document, { root, enabled, post }: SlideDragOptions): { cancel(): void } {
  const win = doc.defaultView ?? window
  let press: Press | undefined
  /** The next click is the one that ends a drag. */
  let swallowClick = false
  /** Escape ended a drag with the button still down: the click comes with the release, later. */
  let escaped = false

  /** Swallows the click that follows this event. Cleared after it: a drag without a click must not eat a later one. */
  function swallowNextClick(): void {
    swallowClick = true
    win.setTimeout(() => {
      swallowClick = false
    }, 0)
  }

  const wrappers = (): HTMLElement[] => Array.from(root.querySelectorAll<HTMLElement>(wrapperSelector))

  function clearMarks(): void {
    for (const el of root.querySelectorAll(`.${dropBeforeClass}, .${dropAfterClass}`)) {
      el.classList.remove(dropBeforeClass, dropAfterClass)
    }
  }

  /** Ends the press, drag or not, and removes every mark of it. */
  function stop(): void {
    press?.dragged.classList.remove(draggingClass)
    root.classList.remove(dragClass)
    clearMarks()
    press = undefined
  }

  /**
   * Puts the insertion mark for the pointer's place and returns the index the dragged slide would get, `undefined` when
   * that is its own place. Ends the drag (also `undefined`) when the thumbnails changed under it: a render replaced them.
   */
  function track(current: Press): number | undefined {
    const all = wrappers()
    if (all.length !== current.count || all[current.from] !== current.dragged) {
      stop()
      return undefined
    }
    const boxes = all.map((w) => w.getBoundingClientRect())
    const slot = dropSlot(boxes, current.x, current.y)
    const to = moveTarget(current.from, slot)
    clearMarks()
    if (to !== undefined) {
      const { index, side } = indicatorFor(boxes, slot, current.y)
      all[index]?.classList.add(side === 'before' ? dropBeforeClass : dropAfterClass)
    }
    return to
  }

  doc.addEventListener('mousedown', (e) => {
    swallowClick = false
    escaped = false
    stop()
    if (e.button !== 0 || !enabled()) return
    const dragged = e.target instanceof Element ? e.target.closest<HTMLElement>(wrapperSelector) : null
    if (!dragged || !root.contains(dragged)) return
    const all = wrappers()
    const from = all.indexOf(dragged)
    const line = slideLineAt(dragged)
    if (from < 0 || line === undefined) return
    press = { startX: e.clientX, startY: e.clientY, x: e.clientX, y: e.clientY, dragging: false, from, line, count: all.length, dragged }
  })

  doc.addEventListener('mousemove', (e) => {
    if (!press) return
    press.x = e.clientX
    press.y = e.clientY
    if (!press.dragging) {
      if (Math.hypot(press.x - press.startX, press.y - press.startY) < dragThreshold) return
      press.dragging = true
      root.classList.add(dragClass)
      press.dragged.classList.add(draggingClass)
    }
    track(press)
  })

  doc.addEventListener('mouseup', (e) => {
    const current = press
    if (!current) {
      if (escaped) swallowNextClick()
      escaped = false
      return
    }
    if (!current.dragging) {
      stop()
      return
    }
    current.x = e.clientX
    current.y = e.clientY
    const to = track(current)
    stop()
    swallowNextClick()
    if (to !== undefined) post({ from: current.from, to, line: current.line, count: current.count })
  })

  doc.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape' || !press) return
    e.preventDefault()
    escaped = press.dragging
    stop()
  })

  win.addEventListener('scroll', () => {
    if (press?.dragging) track(press)
  }, { passive: true })

  win.addEventListener('blur', stop)

  // The mouse-up that drops a thumbnail is followed by a click; without this it would move the caret to a slide.
  win.addEventListener(
    'click',
    (e) => {
      if (!swallowClick) return
      swallowClick = false
      e.stopImmediatePropagation()
      e.preventDefault()
    },
    true,
  )

  return { cancel: stop }
}
