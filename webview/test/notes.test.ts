// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { markActiveSlide } from '../src/active-slide'
import { createMarp } from '../src/marp-factory'
import { dataNotesFor, insertNotes, notesClass } from '../src/notes'
import { parseFragment, patchSlides } from '../src/patch'
import { collectCodeLines } from '../src/scroll-sync'

const deck = [
  '---',
  'marp: true',
  '---',
  '',
  '# One',
  '',
  '<!-- first note -->',
  '<!-- _class: lead -->',
  '<!--',
  'second note',
  'on two lines',
  '-->',
  '',
  '---',
  '',
  '# Two',
  '',
  '---',
  '',
  '# Three',
  '',
  '<!-- <b>not</b> markup & more -->',
].join('\n')

const { marp } = createMarp({ html: 'default', math: 'off' }, [])

/** Same node objects (toEqual would only compare DOM nodes structurally). */
const same = (a: readonly Element[], b: readonly Element[]) => a.length === b.length && a.every((node, i) => node === b[i])

function renderWithNotes(markdown: string): Element {
  const { html, comments } = marp.render(markdown)
  const container = parseFragment(document, html)
  if (!container) throw new Error('nothing rendered')
  insertNotes(container, comments)
  return container
}

describe('presenter notes', () => {
  it('puts a card after every slide wrapper, with the non-directive comments as text', () => {
    const container = renderWithNotes(deck)
    const kinds = Array.from(container.children, (c) => (c.hasAttribute('data-marp-slide-wrapper') ? 'slide' : c.className))
    expect(kinds).toEqual(['slide', notesClass, 'slide', notesClass, 'slide', notesClass])

    const cards = Array.from(container.querySelectorAll<HTMLElement>(`.${notesClass}`))
    expect(cards.map((c) => c.getAttribute(dataNotesFor))).toEqual(['1', '2', '3'])
    expect(cards.map((c) => c.tagName)).toEqual(['ASIDE', 'ASIDE', 'ASIDE'])
    expect(Array.from(cards[0]?.querySelectorAll('p') ?? [], (p) => p.textContent)).toEqual(['first note', 'second note\non two lines'])
  })

  it('hides the card of a slide without notes', () => {
    const cards = Array.from(renderWithNotes(deck).querySelectorAll<HTMLElement>(`.${notesClass}`))
    expect(cards.map((c) => c.hidden)).toEqual([false, true, false])
  })

  it('never parses a comment as markup', () => {
    const card = renderWithNotes(deck).querySelector(`[${dataNotesFor}="3"]`)
    expect(card?.querySelector('b')).toBeNull()
    expect(card?.textContent).toBe('<b>not</b> markup & more')
  })

  it('leaves the slides alone when only a note changes', () => {
    const live = renderWithNotes(deck)
    document.body.replaceChildren(live)
    const previous = Array.from(live.children, (c) => c.outerHTML)
    const slidesBefore = Array.from(live.querySelectorAll('[data-marp-slide-wrapper]'))
    const cardsBefore = Array.from(live.querySelectorAll(`.${notesClass}`))

    patchSlides(live, renderWithNotes(deck.replace('first note', 'first note, edited')), previous)

    expect(same(Array.from(live.querySelectorAll('[data-marp-slide-wrapper]')), slidesBefore)).toBe(true)
    const cardsAfter = Array.from(live.querySelectorAll(`.${notesClass}`))
    expect(cardsAfter[0]).not.toBe(cardsBefore[0])
    expect(cardsAfter[0]?.textContent).toContain('edited')
    expect(same(cardsAfter.slice(1), cardsBefore.slice(1))).toBe(true)
  })

  it('is ignored by scroll sync and the active-slide marker', () => {
    const container = renderWithNotes(deck)
    document.body.replaceChildren(container)
    const entries = collectCodeLines(document, document.body)
    expect(entries.some((e) => e.element.closest(`.${notesClass}`))).toBe(false)
    expect(entries.filter((e) => e.element.hasAttribute('data-marp-slide-wrapper'))).toHaveLength(3)

    const active = markActiveSlide(document, 15)
    expect(active?.hasAttribute('data-marp-slide-wrapper')).toBe(true)
    expect(active?.nextElementSibling?.getAttribute(dataNotesFor)).toBe('2')
  })

  it('tolerates fewer comment arrays than slides', () => {
    const { html } = marp.render(deck)
    const container = parseFragment(document, html)
    if (!container) throw new Error('nothing rendered')
    insertNotes(container, [['only the first']])
    const cards = Array.from(container.querySelectorAll<HTMLElement>(`.${notesClass}`))
    expect(cards.map((c) => c.hidden)).toEqual([false, true, true])
  })
})
