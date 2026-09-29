// esbuild options shared by build.mjs and scripts/notices.mjs, so that the license
// notices are always computed from exactly the bundle that ships.
import path from "node:path"

export function createOptions(outdir) {
  return {
    entryPoints: [
      { in: "src/preview.ts", out: "marp-preview" },
      { in: "src/preview.css", out: "marp-preview" },
    ],
    bundle: true,
    minify: true,
    format: "iife",
    platform: "browser",
    target: "chrome120",
    // Non-ASCII (highlight.js and MathJax tables) as UTF-8 instead of \uXXXX escapes: smaller, same behavior. The
    // script is decoded as UTF-8 because index.html declares it and /app/ serves text/javascript without a charset.
    charset: "utf8",
    // Keeps /*! ... */ license banners: written to marp-preview.js.LEGAL.txt, linked from the bundle.
    legalComments: "linked",
    metafile: true,
    outdir: path.resolve(outdir),
    logLevel: "info",
  }
}
