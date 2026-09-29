// Bundles the preview page into one IIFE script + stylesheet for JCEF.
// Usage: node build.mjs [--outdir=<dir>] [--watch]
import { build } from "esbuild"
import { copyFile, mkdir } from "node:fs/promises"
import path from "node:path"

const arg = (name) => process.argv.find((a) => a.startsWith(`--${name}=`))?.split("=").slice(1).join("=")
const outdir = path.resolve(arg("outdir") ?? "../build/generated/webview/webview")

await mkdir(outdir, { recursive: true })
await build({
  entryPoints: { "marp-preview": "src/preview.ts" },
  bundle: true,
  format: "iife",
  platform: "browser",
  target: "chrome120",
  outdir,
  logLevel: "info",
})
await copyFile("src/index.html", path.join(outdir, "index.html"))
