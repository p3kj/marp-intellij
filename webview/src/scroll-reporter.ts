/**
 * Source line the preview is aligned to: the last `scrollToLine` (`fromEditor`) or where the user scrolled the preview.
 * Re-applied when the viewport is resized, and after a render when it came from the editor, because pixel offsets
 * change with the layout while the source line keeps the preview in step with the editor.
 */
export interface ScrollAnchor {
  line: number
  fromEditor: boolean
}

/**
 * A scroll event this close to where the page scrolled itself is that scroll's echo. `scrollY` is read back right after
 * `scrollTo`, so it already has the value the event reports, also at fractional zoom.
 */
const echoTolerance = 1.5

/**
 * Decides which scroll positions become `revealLine` messages: the page's own scrolls (editor sync, resize, re-render)
 * must not echo back to the editor, and a user scroll that stays on the last reported line is not posted again.
 */
export function createScrollReporter(reveal: (line: number) => void) {
  let programmaticY: number | undefined
  let lastRevealed: number | undefined
  let anchor: ScrollAnchor | undefined

  return {
    get anchor(): ScrollAnchor | undefined {
      return anchor
    },

    /** Kotlin asked the preview to show `line` (the editor scrolled). */
    editorScrolled(line: number): void {
      anchor = { line, fromEditor: true }
    },

    /**
     * The page scrolled itself; `y` is `scrollY` read back afterwards. Its scroll event is not a user scroll. The
     * editor did not follow this move, so the last reported line is forgotten: a user scroll back to exactly that line
     * (the page top, a heading) has to be reported again.
     */
    scrolledProgrammatically(y: number): void {
      programmaticY = y
      lastRevealed = undefined
    },

    /** A scroll frame at `y`. `lineAt` reads the line at the viewport top; it is only called for user scrolls. */
    scrolled(y: number, lineAt: () => number | null): void {
      if (programmaticY !== undefined) {
        if (Math.abs(y - programmaticY) < echoTolerance) return
        programmaticY = undefined
      }
      const line = lineAt()
      if (line === null) return
      anchor = { line, fromEditor: false }
      if (line === lastRevealed) return
      lastRevealed = line
      reveal(line)
    },
  }
}
