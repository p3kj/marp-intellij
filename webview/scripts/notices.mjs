// Keeps the third-party summary in the repo NOTICE file in sync with the real bundle.
// Usage: node scripts/notices.mjs [--check]
//   (no flag)  rewrite the generated block of ../NOTICE
//   --check    fail (exit 1) when NOTICE is out of date, used by `./gradlew check` and CI
import { build } from "esbuild"
import { mkdtemp, rm } from "node:fs/promises"
import { readFileSync, writeFileSync } from "node:fs"
import os from "node:os"
import path from "node:path"
import { createOptions } from "./options.mjs"
import { collectPackages, renderSummary } from "./third-party-notices.mjs"

const BEGIN = "<!-- BEGIN GENERATED -->"
const END = "<!-- END GENERATED -->"
const noticeFile = path.resolve("../NOTICE")

const tmp = await mkdtemp(path.join(os.tmpdir(), "marp-notices-"))
let metafile
try {
  const result = await build({ ...createOptions(tmp), logLevel: "silent" })
  metafile = result.metafile
} finally {
  await rm(tmp, { recursive: true, force: true })
}

const block = `${BEGIN}\n${renderSummary(collectPackages(metafile))}\n${END}`
const current = readFileSync(noticeFile, "utf8")
const pattern = new RegExp(`${BEGIN}[\\s\\S]*?${END}`)
if (!pattern.test(current)) throw new Error(`NOTICE lacks the ${BEGIN} ... ${END} markers`)
const updated = current.replace(pattern, () => block)

if (process.argv.includes("--check")) {
  if (updated !== current) {
    console.error("NOTICE is out of date. Run `npm run notices` in webview/ and commit the result.")
    process.exit(1)
  }
  console.log("NOTICE is up to date.")
} else {
  writeFileSync(noticeFile, updated)
  console.log("NOTICE updated.")
}
