// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { createExportMarp, EXPORT_CSS, exportDocument, standaloneHtml } from '../src/export-html'
import { PRESENT_CSS, presentScript } from '../src/present'
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

  it('stays byte-identical without base, screen css and script', () => {
    expect(standaloneHtml({ title: 'Deck', ...parts })).toBe(
      '<!DOCTYPE html><html><head><meta charset="utf-8">' +
        '<meta name="viewport" content="width=device-width, initial-scale=1">' +
        `<title>Deck</title><style>section{color:red}\n${EXPORT_CSS}</style></head>` +
        '<body><div class="marpit"></div></body></html>\n',
    )
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

  it('adds a base after the charset, other screen css and a script only when asked to', () => {
    const doc = standaloneHtml({ title: 'Deck', ...parts, baseHref: 'file:///d/', screenCss: '@media screen{x{}}', script: 'run()' })
    expect(doc).toContain('<head><meta charset="utf-8"><base href="file:///d/"><meta name="viewport"')
    expect(doc).toContain('<style>section{color:red}\n@media screen{x{}}</style>')
    expect(doc).not.toContain(EXPORT_CSS)
    expect(doc.endsWith('<div class="marpit"></div><script>run()</script></body></html>\n')).toBe(true)
    expect(standaloneHtml({ title: 'Deck', ...parts, baseHref: '' })).not.toContain('<base href')
    expect(standaloneHtml({ title: 'Deck', ...parts })).not.toContain('<script')
  })

  it('escapes the base href and cannot be closed early by a </script in the script', () => {
    const doc = standaloneHtml({ title: 't', ...parts, baseHref: 'file:///a&b/"x"/', script: 'a="</script><b>";/* </SCRIPT */' })
    expect(doc).toContain('<base href="file:///a&amp;b/&quot;x&quot;/">')
    const parsed = new DOMParser().parseFromString(doc, 'text/html')
    expect(parsed.querySelector('base')?.getAttribute('href')).toBe('file:///a&b/"x"/')
    expect(parsed.querySelectorAll('script')).toHaveLength(1)
    expect(parsed.querySelector('script')?.textContent).toBe('a="<\\/script><b>";/* <\\/SCRIPT */')
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

  it('has no base, no script of its own and the export css without present', () => {
    const doc = exportDocument(deck, options, [], 'f')
    expect(doc).not.toContain('<base href')
    expect(doc).toContain(EXPORT_CSS)
    expect(doc).not.toContain(PRESENT_CSS)
    // Only marp-core's helper script, which comes with the rendered slides.
    expect(doc.match(/<script>/g)).toHaveLength(1)
  })

  describe('as a presentation', () => {
    const present = { baseHref: 'file:///d/', start: 1 }
    const doc = exportDocument(deck, options, [], 'f', present)

    it('sets the base right after the charset, before the styles', () => {
      expect(doc).toContain('<meta charset="utf-8"><base href="file:///d/">')
      expect(doc.indexOf('<base')).toBeLessThan(doc.indexOf('<style>'))
    })

    it('uses the presentation css instead of the export css', () => {
      expect(doc).toContain(PRESENT_CSS)
      expect(doc).not.toContain(EXPORT_CSS)
      expect(doc).toMatch(/@page\s*\{\s*size:\s*1280px 720px/)
    })

    it('ends with the presentation script, after the slides and the helper script', () => {
      const script = presentScript(1)
      expect(doc.endsWith(`<script>${script}</script></body></html>\n`)).toBe(true)
      expect(doc.match(/<script>/g)).toHaveLength(2)
      expect(doc.match(/data-marpit-svg=""/g)).toHaveLength(2)
    })

    it('leaves out the base when the deck has no folder', () => {
      expect(exportDocument(deck, options, [], 'f', { start: 0 })).not.toContain('<base href')
    })

    it('still takes the title and language from the deck', () => {
      const titled = exportDocument('---\ntitle: Talk\nlang: cs\n---\n\n# T\n', options, [], 'f', present)
      expect(titled).toContain('<html lang="cs">')
      expect(titled).toContain('<title>Talk</title>')
    })

    it('runs as a document: the script finds the slides of the rendered html', () => {
      const parsed = new DOMParser().parseFromString(doc, 'text/html')
      expect(parsed.querySelectorAll('div.marpit > svg[data-marpit-svg]')).toHaveLength(2)
    })
  })
})
