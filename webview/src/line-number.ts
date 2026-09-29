// Ported from marp-team/marp-vscode/src/plugins/line-number.ts (MIT)
// Based on the original line-number rendering rule of VS Code.

export const codeLineClass = 'code-line'
export const dataLine = 'data-line'

// Adding `code-line` to the slide `<section>` (`marpit_slide_open`) could break
// theme attribute selectors that expect `$=` on the slide class
// (https://github.com/marp-team/marp-vscode/issues/313), so the slide wrapper
// (`marpit_slide_containers_open`) carries the attributes instead.
const sourceMapIgnoredTypes = ['inline', 'marpit_slide_open']

export default function lineNumberPlugin(md: any): void {
  const { marpit_slide_containers_open } = md.renderer.rules

  md.renderer.rules.marpit_slide_containers_open = (
    tks: any[],
    i: number,
    opts: unknown,
    env: unknown,
    slf: any,
  ) => {
    const slide = tks.slice(i + 1).find((t) => t.type === 'marpit_slide_open')

    if (slide?.map?.length) {
      tks[i].attrJoin('class', codeLineClass)
      tks[i].attrSet(dataLine, slide.map[0])
    }

    const renderer = marpit_slide_containers_open || slf.renderToken
    return renderer.call(slf, tks, i, opts, env, slf)
  }

  md.core.ruler.push('marp_intellij_source_map_attr', (state: any) => {
    for (const token of state.tokens) {
      if (token.map?.length && !sourceMapIgnoredTypes.includes(token.type)) {
        token.attrJoin('class', codeLineClass)
        token.attrSet(dataLine, token.map[0])
      }
    }
  })
}
