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
    // Keeps /*! ... */ license banners: written to marp-preview.js.LEGAL.txt, linked from the bundle.
    legalComments: "linked",
    metafile: true,
    outdir: path.resolve(outdir),
    logLevel: "info",
  }
}
