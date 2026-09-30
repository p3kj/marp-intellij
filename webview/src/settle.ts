/**
 * Resolves once the web fonts and the images under `root` have loaded (or failed), but at the latest after `timeoutMs`:
 * a slow or dead server must not hold an export forever. Used before the page is printed to PDF, because printing does
 * not wait for images that are still loading. Only `img` elements count, CSS background images are not waited for.
 */
export function assetsSettled(doc: Document, root: ParentNode, timeoutMs: number): Promise<void> {
  const waits: Promise<unknown>[] = []

  // Not every environment has it (jsdom does not).
  const fonts = (doc as Document & { fonts?: { ready?: Promise<unknown> } }).fonts
  if (fonts?.ready) waits.push(Promise.resolve(fonts.ready).catch(() => undefined))

  for (const img of root.querySelectorAll<HTMLImageElement>('img')) {
    if (img.complete) continue
    waits.push(
      new Promise((resolve) => {
        img.addEventListener('load', resolve, { once: true })
        img.addEventListener('error', resolve, { once: true })
      }),
    )
  }
  if (waits.length === 0) return Promise.resolve()

  return new Promise((resolve) => {
    const timer = setTimeout(resolve, timeoutMs)
    void Promise.all(waits).then(() => {
      clearTimeout(timer)
      resolve()
    })
  })
}
