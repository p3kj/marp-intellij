// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { createExportMarp, EXPORT_CSS, exportDocument, standaloneHtml } from '../src/export-html'
import type { RenderOptions } from '../src/types'

const options: RenderOptions = { html: 'default', math: 'off' }
const deck = '---\nmarp: true\n---\n\n# One\n\n---\n\n## Two\n'

describe('standaloneHtml', () => {
  const parts = { css: 'section{color:red}', html: '<div class="marpit"></div>' }

  it('wraps css and html into a complete document', () => {
    const doc = standaloneHtml({ title: 'Deck', ...parts })
    expect(doc.startsWith('<!DOCTYPE html><html><head><meta charset="utf-8">')).toBe(true)
    expect(doc).toContain('<meta name="viewport" content="width=device-width, initial-scale=1">')
    expect(doc).toContain('<title>Deck</title>')
    expect(doc).toContain(`<style>section{color:red}\n${EXPORT_CSS}</style></head><body><div class="marpit"></div></body></html>`)
  })

  it('escapes the title', () => {
    const doc = standaloneHtml({ title: `<b>"A" & 'B'</b>`, ...parts })
    expect(doc).toContain('<title>&lt;b&gt;&quot;A&quot; &amp; &#39;B&#39;&lt;/b&gt;</title>')
    expect(doc).not.toContain('<b>')
  })

  it('sets lang only when given, escaped', () => {
    expect(standaloneHtml({ title: 't', ...parts })).toContain('<html><head>')
    expect(standaloneHtml({ title: 't', lang: '', ...parts })).toContain('<html><head>')
    expect(standaloneHtml({ title: 't', lang: 'cs', ...parts })).toContain('<html lang="cs"><head>')
    expect(standaloneHtml({ title: 't', lang: 'x"><script>', ...parts })).toContain('<html lang="x&quot;&gt;&lt;script&gt;"><head>')
  })

  it('cannot be closed early by a </style in the css', () => {
    const doc = standaloneHtml({ title: 't', css: 'a::after{content:"</style><script>x</script>"} b::after{content:"</STYLE>"}', html: '' })
    const style = doc.slice(doc.indexOf('<style>') + 7)
    expect(style.indexOf('</style>')).toBe(style.lastIndexOf('</style>'))
    expect(style.slice(0, style.indexOf('</style>'))).toContain('<\\/style><script>x</script>')
    expect(new DOMParser().parseFromString(doc, 'text/html').querySelectorAll('script')).toHaveLength(0)
  })
})

describe('createExportMarp', () => {
  const render = (markdown: string, opts: RenderOptions = options, themes: { source: string; css: string }[] = []) =>
    createExportMarp(opts, themes).render(markdown)

  it('uses marp-core defaults: a marpit container, inline SVG and the helper script', () => {
    const { html, css } = render(deck)
    expect(html).toContain('<div class="marpit">')
    expect(html).toContain('data-marpit-svg')
    expect(html).toContain('<script>')
    expect(css).toContain('@page')
  })

  it('has no preview-only markup: line numbers and content sections', () => {
    const { html } = render(deck)
    expect(html).not.toContain('data-line')
    expect(html).not.toContain('code-line')
    expect(html).not.toContain('data-marp-content-start-line')
    expect(html).not.toContain('data-marp-slide-wrapper')
    expect(html).not.toContain('__marp-preview')
  })

  it('follows the html option like the preview', () => {
    const md = '# T\n\n<i>it</i> <b onclick="x()">bold</b>\n'
    const htmlOf = (html: RenderOptions['html']) => render(md, { html, math: 'off' }).html
    expect(htmlOf('off')).not.toContain('<i>it</i>')
    expect(htmlOf('default')).toContain('<i>it</i>')
    expect(htmlOf('default')).not.toContain('onclick')
    expect(htmlOf('all')).toContain('<b onclick="x()">bold</b>')
  })

  it('follows the math option', () => {
    const md = '# T\n\n$E = mc^2$\n'
    expect(render(md, { html: 'default', math: 'off' }).html).not.toContain('MathJax')
    expect(render(md, { html: 'default', math: 'mathjax' }).html).toContain('MathJax')
  })

  it('picks a custom theme by its @theme name and skips one marp-core rejects', () => {
    const themes = [
      { source: 'bad.css', css: 'not a theme' },
      { source: 'good.css', css: '/* @theme exported */\nsection { background: #123456; }' },
    ]
    const { css } = render('---\ntheme: exported\n---\n\n# T\n', options, themes)
    expect(css).toContain('#123456')
    expect(render('# T\n', options, themes).css).not.toContain('#123456')
  })

  it('keeps the title directive out of the markup', () => {
    expect(render('---\ntitle: Secret title\n---\n\n# T\n').html).not.toContain('Secret title')
  })
})

describe('exportDocument', () => {
  it('renders every slide into a standalone document', () => {
    const doc = exportDocument(deck, options, [], 'file')
    expect(doc).toContain('<!DOCTYPE html>')
    expect(doc.match(/data-marpit-svg=""/g)).toHaveLength(2)
    expect(doc).toContain('<h1 id="one">One</h1>')
    expect(doc).toContain('<h2 id="two">Two</h2>')
  })

  it('takes the title from the title directive, the fallback otherwise', () => {
    expect(exportDocument('---\ntitle: My Talk\n---\n\n# T\n', options, [], 'file')).toContain('<title>My Talk</title>')
    expect(exportDocument('---\ntitle: "Quoted & <odd>"\n---\n\n# T\n', options, [], 'file')).toContain('<title>Quoted &amp; &lt;odd&gt;</title>')
    expect(exportDocument(deck, options, [], 'my-deck')).toContain('<title>my-deck</title>')
    expect(exportDocument('---\ntitle:\n---\n\n# T\n', options, [], 'my-deck')).toContain('<title>my-deck</title>')
  })

  it('takes the language from the lang directive', () => {
    expect(exportDocument('---\nlang: cs\n---\n\n# T\n', options, [], 'f')).toContain('<html lang="cs">')
    expect(exportDocument(deck, options, [], 'f')).toContain('<html><head>')
  })

  it('keeps the size directive in the page rule that the PDF export relies on', () => {
    expect(exportDocument('---\nsize: 4:3\n---\n\n# T\n', options, [], 'f')).toMatch(/@page\s*\{\s*size:\s*960px 720px/)
    expect(exportDocument(deck, options, [], 'f')).toMatch(/@page\s*\{\s*size:\s*1280px 720px/)
  })
})
