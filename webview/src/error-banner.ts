/** Small dismissible banner at the top of the preview. Re-appears when the messages change. */
export function createErrorBanner(doc: Document, dismissLabel: string) {
  const el = doc.createElement('div')
  el.id = 'marp-error-banner'
  // role=alert already implies an assertive live region; an extra aria-live would only contradict it.
  el.setAttribute('role', 'alert')
  el.hidden = true

  const list = doc.createElement('ul')
  const close = doc.createElement('button')
  close.type = 'button'
  close.className = 'marp-error-close'
  close.textContent = '×'
  const setDismissLabel = (label: string) => {
    close.title = label
    close.setAttribute('aria-label', label)
  }
  setDismissLabel(dismissLabel)
  el.append(list, close)

  let signature = ''
  let dismissed = ''

  const dismiss = () => {
    dismissed = signature
    el.hidden = true
  }
  close.addEventListener('click', dismiss)
  // Escape while the preview has focus. Only consumed when there was a banner to close, so a second Escape still
  // reaches the IDE (JCEF passes unhandled keys on).
  doc.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape' || el.hidden) return
    e.preventDefault()
    dismiss()
  })

  return {
    element: el,
    setDismissLabel,
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
