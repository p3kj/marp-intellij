/** Small dismissible banner at the top of the preview. Re-appears when the messages change. */
export function createErrorBanner(doc: Document) {
  const el = doc.createElement('div')
  el.id = 'marp-error-banner'
  el.setAttribute('role', 'alert')
  el.hidden = true

  const list = doc.createElement('ul')
  const close = doc.createElement('button')
  close.type = 'button'
  close.className = 'marp-error-close'
  close.title = 'Dismiss'
  close.setAttribute('aria-label', 'Dismiss')
  close.textContent = '×'
  el.append(list, close)

  let signature = ''
  let dismissed = ''

  close.addEventListener('click', () => {
    dismissed = signature
    el.hidden = true
  })

  return {
    element: el,
    set(messages: readonly string[]) {
      signature = messages.join('\n')
      list.replaceChildren(
        ...messages.map((m) => {
          const li = doc.createElement('li')
          li.textContent = m
          return li
        }),
      )
      el.hidden = messages.length === 0 || signature === dismissed
    },
  }
}
