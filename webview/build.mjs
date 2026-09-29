// Bundles the preview page into one IIFE script + stylesheet for JCEF.
// Usage: node build.mjs [--outdir=<dir>] [--watch]
// Also writes THIRD-PARTY-NOTICES.txt (and esbuild's marp-preview.js.LEGAL.txt) next to the bundle.
import { build, context } from "esbuild"
import { copyFile, mkdir, writeFile } from "node:fs/promises"
import path from "node:path"
import { createOptions } from "./scripts/options.mjs"
import { collectPackages, renderFull } from "./scripts/third-party-notices.mjs"

const arg = (name) => process.argv.find((a) => a.startsWith(`--${name}=`))?.split("=").slice(1).join("=")
const outdir = path.resolve(arg("outdir") ?? "../build/generated/webview/webview")
const watch = process.argv.includes("--watch")
const options = createOptions(outdir)

const writeNotices = (metafile) =>
  writeFile(path.join(outdir, "THIRD-PARTY-NOTICES.txt"), renderFull(collectPackages(metafile)))

await mkdir(outdir, { recursive: true })
await copyFile("src/index.html", path.join(outdir, "index.html"))

if (watch) {
  const ctx = await context({
    ...options,
    plugins: [{ name: "notices", setup: (b) => b.onEnd((r) => r.metafile && writeNotices(r.metafile)) }],
  })
  await ctx.watch()
  console.log(`watching, output in ${outdir}`)
} else {
  const result = await build(options)
  await writeNotices(result.metafile)
}
