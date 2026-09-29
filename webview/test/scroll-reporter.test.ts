import { describe, expect, it } from 'vitest'
import { createScrollReporter } from '../src/scroll-reporter'

function reporter() {
  const revealed: number[] = []
  return { revealed, scroll: createScrollReporter((line) => revealed.push(line)) }
}

describe('scroll reporter', () => {
  it('reports a user scroll back to a line it reported before the editor moved the preview', () => {
    const { revealed, scroll } = reporter()
    // 1. The user scrolls the preview to the top.
    scroll.scrolled(0, () => 0)
    expect(revealed).toEqual([0])
    // 2. The user scrolls the editor down; the preview follows, and its scroll event is the echo of that.
    scroll.editorScrolled(40)
    scroll.scrolledProgrammatically(800)
    scroll.scrolled(800, () => 40)
    expect(revealed).toEqual([0])
    // 3. The user scrolls the preview back to the top: the editor has to follow again.
    scroll.scrolled(0, () => 0)
    expect(revealed).toEqual([0, 0])
  })

  it('does not post the same line twice for consecutive user scroll frames', () => {
    const { revealed, scroll } = reporter()
    scroll.scrolled(100, () => 5)
    scroll.scrolled(101, () => 5)
    scroll.scrolled(140, () => 7)
    expect(revealed).toEqual([5, 7])
  })

  it('ignores the echo within 1.5 px of a programmatic scroll, without measuring', () => {
    const { revealed, scroll } = reporter()
    let measured = 0
    const lineAt = () => {
      measured++
      return 12
    }
    scroll.scrolledProgrammatically(300)
    scroll.scrolled(301.2, lineAt)
    expect(measured).toBe(0)
    // Further away it is the user again, and the echo guard is used up.
    scroll.scrolled(302, lineAt)
    scroll.scrolled(300, lineAt)
    expect(measured).toBe(2)
    expect(revealed).toEqual([12])
  })

  it('keeps the anchor: editor lines from scrollToLine, preview lines from user scrolls', () => {
    const { scroll } = reporter()
    expect(scroll.anchor).toBeUndefined()
    scroll.editorScrolled(9.5)
    expect(scroll.anchor).toEqual({ line: 9.5, fromEditor: true })
    scroll.scrolledProgrammatically(200)
    scroll.scrolled(200, () => 9.5)
    expect(scroll.anchor).toEqual({ line: 9.5, fromEditor: true })
    scroll.scrolled(260, () => 11)
    expect(scroll.anchor).toEqual({ line: 11, fromEditor: false })
    // A repeated line is not posted but still moves the anchor to the user's position.
    scroll.editorScrolled(3)
    scroll.scrolled(261, () => 11)
    expect(scroll.anchor).toEqual({ line: 11, fromEditor: false })
  })

  it('posts nothing when no line maps', () => {
    const { revealed, scroll } = reporter()
    scroll.scrolled(50, () => null)
    expect(revealed).toEqual([])
    expect(scroll.anchor).toBeUndefined()
  })
})
