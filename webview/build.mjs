// Bundles the preview page into one IIFE script + stylesheet for JCEF.
// Usage: node build.mjs [--outdir=<dir>] [--watch]
import { build, context } from "esbuild"
import { copyFile, mkdir } from "node:fs/promises"
import path from "node:path"

const arg = (name) => process.argv.find((a) => a.startsWith(`--${name}=`))?.split("=").slice(1).join("=")
const outdir = path.resolve(arg("outdir") ?? "../build/generated/webview/webview")
const watch = process.argv.includes("--watch")

const options = {
  entryPoints: [
    { in: "src/preview.ts", out: "marp-preview" },
    { in: "src/preview.css", out: "marp-preview" },
  ],
  bundle: true,
  minify: true,
  format: "iife",
  platform: "browser",
  target: "chrome120",
  legalComments: "none",
  outdir,
  logLevel: "info",
}

await mkdir(outdir, { recursive: true })
await copyFile("src/index.html", path.join(outdir, "index.html"))

if (watch) {
  const ctx = await context(options)
  await ctx.watch()
  console.log(`watching, output in ${outdir}`)
} else {
  await build(options)
}
