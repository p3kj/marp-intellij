// Mock of the Kotlin side: receives JSON messages, drives window.marpBridge.
const params = new URLSearchParams(location.search)
const deckPath = params.get('deck')
const themePaths = (params.get('themes') ?? '').split(',').filter(Boolean)
const dark = params.get('dark') === '1'

const log = []
window.__hostLog = log

const text = async (url) => (await fetch(url)).text()
const fileUrl = (p) => '/file?path=' + encodeURIComponent(p)

async function start() {
  const markdown = deckPath ? await text(fileUrl(deckPath)) : await text('/sample.md')
  const themes = []
  for (const p of themePaths) themes.push({ source: p, css: await text(fileUrl(p)) })
  if (!deckPath) themes.push({ source: 'sample-theme.css', css: await text('/sample-theme.css') })

  const dir = deckPath ? deckPath.slice(0, deckPath.lastIndexOf('/') + 1) : '/'
  const baseHref = deckPath ? location.origin + '/doc' + dir : location.origin + '/'
  const bridge = window.marpBridge

  bridge.setIdeTheme(dark
    ? { dark: true, background: '#1e1f22', foreground: '#dfe1e5' }
    : { dark: false, background: '#ffffff', foreground: '#1e1e1e' })
  bridge.setThemes({ themes, errors: params.get('kerr') ? [params.get('kerr')] : [] })
  const options = { html: params.get('html') ?? 'default', math: params.get('math') ?? 'mathjax', notes: params.get('notes') === '1' }
  bridge.update({ markdown, baseHref, options })
  window.__mock = { markdown, baseHref, themes }
}

window.__marpHost = {
  post(raw) {
    const msg = JSON.parse(raw)
    log.push(msg)
    console.log('[host]', raw)
    if (msg.type === 'ready') start()
  },
}
window.__marpHostReady?.()
