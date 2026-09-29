// Dev server: bundles the preview (watch mode) and serves a mock-host page at http://127.0.0.1:5173/
//   /                          dev page (?deck=<abs md path>&themes=<abs css>,<abs css>&dark=1)
//   /doc/<absolute path>       local files, like the plugin's https://marp.localhost/doc/ handler
//   /file?path=<abs path>      raw text of a file (used by the mock host)
// The dev page gets the Content-Security-Policy of src/index.html, adapted to this origin (CSP=0 leaves it out).
// Dev only: serves any readable file on this machine to localhost.
import { context } from "esbuild"
import { createServer } from "node:http"
import { copyFile, mkdir, readFile, writeFile } from "node:fs/promises"
import path from "node:path"
import { fileURLToPath } from "node:url"

const here = path.dirname(fileURLToPath(import.meta.url))
const out = path.join(here, "out")
const port = Number(process.env.PORT ?? 5173)

await mkdir(out, { recursive: true })
await writeFile(path.join(out, "index.html"), await devIndexHtml())
await copyFile(path.join(here, "mock-host.js"), path.join(out, "mock-host.js"))
await copyFile(path.join(here, "sample.md"), path.join(out, "sample.md"))
await copyFile(path.join(here, "sample-theme.css"), path.join(out, "sample-theme.css"))

const ctx = await context({
  entryPoints: [
    { in: path.join(here, "../src/preview.ts"), out: "marp-preview" },
    { in: path.join(here, "../src/preview.css"), out: "marp-preview" },
  ],
  bundle: true,
  format: "iife",
  platform: "browser",
  target: "chrome120",
  outdir: out,
  logLevel: "warning",
})
await ctx.watch()

/** dev/index.html with the production CSP: scripts from this origin instead of /app/, and fetch for the mock host. */
async function devIndexHtml() {
  const html = await readFile(path.join(here, "index.html"), "utf8")
  if (process.env.CSP === "0") return html
  const production = await readFile(path.join(here, "../src/index.html"), "utf8")
  const csp = production.match(/<meta http-equiv="Content-Security-Policy" content="([^"]+)">/)?.[1]
  if (!csp) throw new Error("src/index.html has no Content-Security-Policy meta")
  const dev = csp.replaceAll("https://marp.localhost/app/", "'self'").replace("connect-src 'none'", "connect-src 'self'")
  return html.replace(/<!-- CSP:[^>]*-->/, `<meta http-equiv="Content-Security-Policy" content="${dev}">`)
}

const types = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".svg": "image/svg+xml", ".webp": "image/webp", ".gif": "image/gif", ".md": "text/plain; charset=utf-8" }

async function send(res, file) {
  try {
    const body = await readFile(file)
    res.writeHead(200, { "content-type": types[path.extname(file).toLowerCase()] ?? "application/octet-stream" })
    res.end(body)
  } catch {
    res.writeHead(404).end("not found")
  }
}

createServer(async (req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${port}`)
  if (url.pathname.startsWith("/doc/")) return send(res, decodeURIComponent(url.pathname.slice(4)))
  if (url.pathname === "/file") return send(res, url.searchParams.get("path") ?? "")
  const name = url.pathname === "/" ? "index.html" : url.pathname.slice(1)
  return send(res, path.join(out, path.normalize(name).replace(/^(\.\.[/\\])+/, "")))
}).listen(port, "127.0.0.1", () => console.log(`dev page: http://127.0.0.1:${port}/`))
