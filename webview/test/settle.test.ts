// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import { assetsSettled } from '../src/settle'

const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

/** Whether the promise has settled, checked after `ms`. */
async function settledWithin(promise: Promise<void>, ms = 30): Promise<boolean> {
  let done = false
  void promise.then(() => (done = true))
  await wait(ms)
  return done
}

function root(...images: { complete: boolean }[]): { el: HTMLElement; imgs: HTMLImageElement[] } {
  const el = document.createElement('div')
  const imgs = images.map(({ complete }) => {
    const img = document.createElement('img')
    Object.defineProperty(img, 'complete', { value: complete, configurable: true })
    el.appendChild(img)
    return img
  })
  return { el, imgs }
}

afterEach(() => {
  Reflect.deleteProperty(document, 'fonts')
})

describe('assetsSettled', () => {
  it('resolves at once when nothing is loading', async () => {
    expect(await settledWithin(assetsSettled(document, root().el, 1000), 5)).toBe(true)
    expect(await settledWithin(assetsSettled(document, root({ complete: true }, { complete: true }).el, 1000), 5)).toBe(true)
  })

  it('waits for an image that is still loading, until it loads', async () => {
    const { el, imgs } = root({ complete: true }, { complete: false })
    const promise = assetsSettled(document, el, 1000)
    expect(await settledWithin(promise)).toBe(false)
    imgs[1]?.dispatchEvent(new Event('load'))
    expect(await settledWithin(promise, 5)).toBe(true)
  })

  it('counts a failed image as done', async () => {
    const { el, imgs } = root({ complete: false })
    const promise = assetsSettled(document, el, 1000)
    imgs[0]?.dispatchEvent(new Event('error'))
    expect(await settledWithin(promise, 5)).toBe(true)
  })

  it('waits for every image', async () => {
    const { el, imgs } = root({ complete: false }, { complete: false })
    const promise = assetsSettled(document, el, 1000)
    imgs[0]?.dispatchEvent(new Event('load'))
    expect(await settledWithin(promise)).toBe(false)
    imgs[1]?.dispatchEvent(new Event('error'))
    expect(await settledWithin(promise, 5)).toBe(true)
  })

  it('waits for the fonts', async () => {
    let fontsLoaded: () => void = () => {}
    const ready = new Promise<void>((resolve) => (fontsLoaded = resolve))
    Object.defineProperty(document, 'fonts', { value: { ready }, configurable: true })
    const promise = assetsSettled(document, root().el, 1000)
    expect(await settledWithin(promise)).toBe(false)
    fontsLoaded()
    expect(await settledWithin(promise, 5)).toBe(true)
  })

  it('gives up after the timeout', async () => {
    const { el } = root({ complete: false })
    const started = Date.now()
    await assetsSettled(document, el, 60)
    expect(Date.now() - started).toBeGreaterThanOrEqual(50)
  })

  it('does not wait for images outside the root', async () => {
    const outside = document.createElement('img')
    Object.defineProperty(outside, 'complete', { value: false, configurable: true })
    document.body.appendChild(outside)
    expect(await settledWithin(assetsSettled(document, root().el, 1000), 5)).toBe(true)
    outside.remove()
  })
})
