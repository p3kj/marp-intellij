// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { isEmptyDeck } from '../src/empty-deck'
import { createMarp } from '../src/marp-factory'
import { parseFragment } from '../src/patch'

const { marp } = createMarp({ html: 'default', math: 'off' }, [])
const empty = (markdown: string) => isEmptyDeck(parseFragment(document, marp.render(markdown).html))

describe('isEmptyDeck', () => {
  it('is empty without content: nothing, blank lines, front matter, directives, notes', () => {
    expect(isEmptyDeck(null)).toBe(true)
    expect(empty('')).toBe(true)
    expect(empty('\n\n')).toBe(true)
    expect(empty('---\nmarp: true\npaginate: true\n---\n')).toBe(true)
    expect(empty('---\nmarp: true\n---\n\n<!-- _class: lead -->\n<!-- a note -->\n')).toBe(true)
  })

  it('is not empty with any content, a background image, a header or a second slide', () => {
    expect(empty('# a')).toBe(false)
    expect(empty('text')).toBe(false)
    expect(empty('![bg](x.png)')).toBe(false)
    expect(empty('<!-- header: x -->')).toBe(false)
    expect(empty('---\nmarp: true\n---\n\n---\n')).toBe(false)
  })
})
