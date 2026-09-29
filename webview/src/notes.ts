export const notesClass = 'marp-notes'
export const dataNotesFor = 'data-marp-notes-for'

/**
 * Inserts a presenter-notes card after every slide wrapper in `container`: the slide's HTML comments that are not
 * directives (`marp.render().comments`, one array per slide) as plain-text paragraphs. Every slide gets a card, hidden
 * when it has no notes, so the container's children stay wrapper/card pairs: adding or editing a note replaces only that
 * card in patchSlides and leaves every slide's DOM alone. `data-marp-notes-for` is the slide number (1-based, like the
 * slide's `id` and page number). The cards carry no `code-line` class, so scroll sync and the active-slide marker skip
 * them.
 */
export function insertNotes(container: Element, comments: readonly (readonly string[])[]): void {
  const doc = container.ownerDocument
  const wrappers = Array.from(container.children).filter((c) => c.hasAttribute('data-marp-slide-wrapper'))
  wrappers.forEach((wrapper, i) => {
    const notes = (comments[i] ?? []).filter((text) => text.trim() !== '')
    const card = doc.createElement('aside')
    card.className = notesClass
    card.setAttribute(dataNotesFor, String(i + 1))
    // `note` instead of aside's implicit complementary landmark: one landmark per slide would only be noise.
    card.setAttribute('role', 'note')
    card.hidden = notes.length === 0
    for (const text of notes) {
      const p = doc.createElement('p')
      // Deck text, never markup.
      p.textContent = text
      card.append(p)
    }
    wrapper.after(card)
  })
}
