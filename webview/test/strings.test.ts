// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { createErrorBanner } from '../src/error-banner'
import { createMarp } from '../src/marp-factory'
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
})

describe('theme errors', () => {
  it('are reported with their source, unformatted', () => {
    const { errors } = createMarp({ html: 'default', math: 'off' }, [{ source: 'broken.css', css: 'section { color: red }' }])
    expect(errors).toHaveLength(1)
    expect(errors[0].source).toBe('broken.css')
    expect(errors[0].message).not.toBe('')
  })
})
