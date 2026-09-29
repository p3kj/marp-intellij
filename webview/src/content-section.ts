// Ported from marp-team/marp-vscode/src/plugins/content-section.ts (MIT)

export const rule = 'marp_intellij_content_section'

export const dataStartLine = 'data-marp-content-start-line'
export const dataEndLine = 'data-marp-content-end-line'

/** Marks every slide `<section>` with the first and last source line of its content. */
export default function contentSectionPlugin(md: any): void {
  md.core.ruler.push(rule, (state: any) => {
    if (state.inlineMode) return

    let lastSlideToken: any = null
    let currentLine = 0
    let maxLine = 0

    for (const token of state.tokens) {
      if (token.map) {
        currentLine = token.map[0]
        maxLine = Math.max(maxLine, ...token.map)
      }

      if (token.type === 'marpit_slide_open') {
        if (lastSlideToken) {
          if (lastSlideToken.map) lastSlideToken.attrSet(dataStartLine, lastSlideToken.map[0])
          lastSlideToken.attrSet(dataEndLine, currentLine - 1)
        }
        lastSlideToken = token
      }
    }

    if (lastSlideToken) {
      if (lastSlideToken.map) lastSlideToken.attrSet(dataStartLine, lastSlideToken.map[0])
      lastSlideToken.attrSet(dataEndLine, maxLine)
    }
  })
}
