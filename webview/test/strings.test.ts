// @vitest-environment jsdom
import { Marp } from '@marp-team/marp-core'
import { describe, expect, it, vi } from 'vitest'
import { createErrorBanner } from '../src/error-banner'
import { createMarp, marpKey } from '../src/marp-factory'
import { defaultStrings, formatMessage, mergeStrings } from '../src/strings'

describe('strings', () => {
  it('formats numbered placeholders', () => {
    expect(formatMessage('Theme {0}: {1}', 'a.css', 'boom')).toBe('Theme a.css: boom')
    expect(formatMessage('{1} before {0}, {0} again', 'x', 'y')).toBe('y before x, x again')
    expect(formatMessage('missing {2}', 'x')).toBe('missing {2}')
    expect(formatMessage('no $& or $1 expansion: {0}', '$&$1')).toBe('no $& or $1 expansion: $&$1')
  })

  it('keeps English defaults for missing or malformed fields', () => {
    expect(mergeStrings(undefined)).toEqual(defaultStrings)
    expect(mergeStrings({ dismiss: 'Zavřít', themeError: 42, renderError: '' })).toEqual({ ...defaultStrings, dismiss: 'Zavřít' })
  })

  it('has an empty-deck hint that a localized one replaces', () => {
    expect(defaultStrings.emptyDeck).toContain('---')
    expect(mergeStrings({ emptyDeck: 'Zatím žádné snímky.' }).emptyDeck).toBe('Zatím žádné snímky.')
    expect(mergeStrings({ emptyDeck: null }).emptyDeck).toBe(defaultStrings.emptyDeck)
  })
})

describe('error banner', () => {
  it('labels the close button and can relabel it', () => {
    const banner = createErrorBanner(document, 'Dismiss')
    const close = banner.element.querySelector('button')!
    expect(close.title).toBe('Dismiss')
    expect(close.getAttribute('aria-label')).toBe('Dismiss')
    banner.setDismissLabel('Zavřít')
    expect(close.title).toBe('Zavřít')
    expect(close.getAttribute('aria-label')).toBe('Zavřít')
  })

  it('shows messages until dismissed and again when they change', () => {
    const banner = createErrorBanner(document, 'Dismiss')
    banner.set(['a'])
    expect(banner.element.hidden).toBe(false)
    expect(banner.element.textContent).toContain('a')
    banner.element.querySelector('button')!.click()
    expect(banner.element.hidden).toBe(true)
    banner.set(['a'])
    expect(banner.element.hidden).toBe(true)
    banner.set(['a', 'b'])
    expect(banner.element.hidden).toBe(false)
    banner.set([])
    expect(banner.element.hidden).toBe(true)
  })

  it('closes on Escape while shown, and lets Escape through otherwise', () => {
    const banner = createErrorBanner(document, 'Dismiss')
    const escape = () => {
      const e = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })
      document.body.dispatchEvent(e)
      return e
    }
    banner.set(['a'])
    expect(escape().defaultPrevented).toBe(true)
    expect(banner.element.hidden).toBe(true)
    // Dismissed like the button: the same messages stay hidden, new ones show again.
    banner.set(['a'])
    expect(banner.element.hidden).toBe(true)
    expect(escape().defaultPrevented).toBe(false)
    banner.set(['b'])
    expect(banner.element.hidden).toBe(false)
    document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
    expect(banner.element.hidden).toBe(false)
  })
})

describe('theme errors', () => {
  it('are reported with their source, unformatted', () => {
    const { errors } = createMarp({ html: 'default', math: 'off' }, [{ source: 'broken.css', css: 'section { color: red }' }])
    expect(errors).toHaveLength(1)
    expect(errors[0]?.source).toBe('broken.css')
    expect(errors[0]?.message).not.toBe('')
  })
})

describe('unknown theme', () => {
  const unknown = (markdown: string, themes: { source: string; css: string }[] = []) => {
    const build = createMarp({ html: 'default', math: 'off' }, themes)
    build.marp.render(markdown)
    return build.unknownThemes()
  }
  const custom = { source: 'demo.css', css: '/* @theme demo */\nsection { color: red }' }

  it('reports a theme that is not registered, front matter and comment, quotes stripped', () => {
    expect(unknown('---\ntheme: nonexistent\n---\n# a')).toEqual(['nonexistent'])
    expect(unknown('---\ntheme: "demo"\n---\n# a')).toEqual(['demo'])
    expect(unknown("<!-- theme: 'demo' -->\n# a")).toEqual(['demo'])
  })

  it('is case sensitive like Marpit', () => {
    expect(unknown('---\ntheme: Gaia\n---\n# a')).toEqual(['Gaia'])
  })

  it('reports nothing for built-in themes, custom themes or no directive', () => {
    for (const name of ['default', 'gaia', 'uncover']) expect(unknown(`---\ntheme: ${name}\n---\n# a`)).toEqual([])
    expect(unknown('---\ntheme: demo\n---\n# a', [custom])).toEqual([])
    expect(unknown('# a')).toEqual([])
  })

  it('does not count @import of an unregistered theme', () => {
    const importing = { source: 'i.css', css: '/* @theme importing */\n@import "missing";\nsection { color: red }' }
    expect(unknown('---\ntheme: importing\n---\n# a', [importing])).toEqual([])
  })

  it('starts over on every render', () => {
    const build = createMarp({ html: 'default', math: 'off' }, [])
    build.marp.render('---\ntheme: nope\n---\n# a')
    build.marp.render('# a')
    expect(build.unknownThemes()).toEqual([])
  })

  it('has a default text with a placeholder and merges a localized one', () => {
    expect(formatMessage(defaultStrings.unknownTheme, 'demo')).toContain("'demo'")
    expect(mergeStrings({ unknownTheme: 'Motiv {0}' }).unknownTheme).toBe('Motiv {0}')
  })

  it('turns itself off, instead of breaking every render, when Marpit renames its directive rule', () => {
    const ruler = new Marp().markdown.core.ruler
    const proto = Object.getPrototypeOf(ruler)
    const original = proto.before
    const before = vi.spyOn(proto, 'before').mockImplementation(function (this: unknown, name: unknown, ...rest: unknown[]) {
      if (name === 'marpit_directives_global_parse') throw new Error(`Parser rule not found: ${name}`)
      return original.call(this, name, ...rest)
    })
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    try {
      for (let i = 0; i < 2; i++) {
        const build = createMarp({ html: 'default', math: 'off' }, [])
        expect(build.marp.render('---\ntheme: nope\n---\n# a').html).toContain('>a</h1>')
        expect(build.unknownThemes()).toEqual([])
      }
      expect(warn).toHaveBeenCalledTimes(1)
    } finally {
      before.mockRestore()
      warn.mockRestore()
    }
  })
})

describe('marpKey', () => {
  it('ignores the notes option: showing notes needs no new Marp instance', () => {
    const key = (notes?: boolean) => marpKey({ html: 'default', math: 'katex', ...(notes === undefined ? {} : { notes }) }, [])
    expect(key(true)).toBe(key())
    expect(key(false)).toBe(key())
    expect(marpKey({ html: 'all', math: 'katex' }, [])).not.toBe(key())
  })
})
