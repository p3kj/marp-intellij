// @vitest-environment jsdom
import { beforeAll, describe, expect, it } from 'vitest'
import type { MarpBridge } from '../src/types'

const posted: { type: string; message?: string }[] = []
let bridge: MarpBridge

const banner = () => Array.from(document.querySelectorAll('#marp-error-banner li')).map((li) => li.textContent)
const render = async (markdown: string) => {
  bridge.update({ markdown, baseHref: 'https://marp.localhost/doc/', options: { html: 'default', math: 'off' } })
  await new Promise((r) => setTimeout(r, 150))
}
const demoTheme = { source: 'demo.css', css: '/* @theme demo */\nsection { color: red }' }

beforeAll(async () => {
  document.body.innerHTML = '<div id="marp-root"></div>'
  ;(window as any).__marpHost = { post: (m: string) => posted.push(JSON.parse(m)) }
  ;(window as any).matchMedia ??= () => ({ matches: false, addEventListener() {}, removeEventListener() {} })
  await import('../src/preview')
  bridge = window.marpBridge
})

describe('unknown theme warning', () => {
  it('shows and posts once for an unknown theme, not again on the next keystroke', async () => {
    await render('---\ntheme: demo\n---\n# a')
    expect(banner()).toEqual(['Theme "demo" is not recognized. Add it in Settings | Tools | Marp or to themeSet in .marprc.yml.'])
    await render('---\ntheme: demo\n---\n# ab')
    expect(posted.filter((m) => m.type === 'error')).toHaveLength(1)
  })

  it('relabels through setStrings', () => {
    bridge.setStrings({ dismiss: 'x', themeError: 'x', renderError: 'x', unknownTheme: 'Motiv "{0}" neexistuje' })
    expect(banner()).toEqual(['Motiv "demo" neexistuje'])
  })

  it('clears once setThemes registers the theme', async () => {
    bridge.setThemes({ themes: [demoTheme], errors: [] })
    await new Promise((r) => setTimeout(r, 150))
    expect(banner()).toEqual([])
  })

  it('shows nothing for built-in themes or no directive', async () => {
    await render('---\ntheme: gaia\n---\n# a')
    expect(banner()).toEqual([])
    await render('# a')
    expect(banner()).toEqual([])
  })

  it('clears when the directive is fixed', async () => {
    await render('---\ntheme: nonexistent\n---\n# a')
    expect(banner()).toHaveLength(1)
    await render('---\ntheme: default\n---\n# a')
    expect(banner()).toEqual([])
  })
})
