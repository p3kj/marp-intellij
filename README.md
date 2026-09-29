# Marp Preview

Marp Preview renders [Marp](https://marp.app/) slide decks inside your JetBrains IDE. The slides appear in a JCEF preview next to the Markdown editor and refresh while you type.

<!-- Screenshot placeholder: add docs/screenshot.png and reference it here with ![Marp Preview](docs/screenshot.png) -->

_Screenshot coming soon._

The plugin works in all IntelliJ-based IDEs on the 2026.2 platform and newer (IntelliJ IDEA, PhpStorm, WebStorm, PyCharm, GoLand, CLion, Rider, RubyMine and others) and depends only on the bundled Markdown plugin and JCEF.

## Installation

The plugin is not on the JetBrains Marketplace yet. Install a release build from disk:

1. Download the plugin ZIP from the GitHub Releases page (or build it yourself, see [Development](#development)).
2. Open Settings | Plugins, click the gear icon and choose Install Plugin from Disk.
3. Select the ZIP (do not unpack it). The plugin loads without restarting the IDE.

## Features

- Live preview of Marp decks next to the Markdown editor, refreshed while typing
- Two-way scroll sync: scrolling the editor moves the preview and the other way round
- Custom themes from files, folders and URLs
- Automatic pickup of `themeSet` from `.marprc.yml`
- Local images with relative paths, including `![bg](...)` backgrounds
- Math with MathJax (works offline) or KaTeX
- Emoji support through Twemoji
- Works in every IntelliJ-based IDE, no IDE-specific APIs

A Markdown file is treated as a Marp deck when its front matter contains `marp: true`.

## Settings

Open Settings | Tools | Marp.

| Setting | Description |
| ------- | ----------- |
| Themes | List of theme CSS files, folders or URLs available to your decks. Each theme declares its name with a `/* @theme name */` comment. |
| Use `.marprc` themeSet | When enabled, the `themeSet` from `.marprc.yml` (or `.yaml`, `.json`, `.marprc`) in the project root is loaded automatically. |
| HTML | Inline HTML in slides: off, default (Marp's safe allow list) or all. |
| Math | Math library: `mathjax`, `katex` or off. |
| Scroll sync | Toggles synchronized scrolling between editor and preview. |

## Themes

Marp themes are plain CSS files that start with a `/* @theme name */` comment, see the [Marpit theme documentation](https://marpit.marp.app/theme-css). Select a theme in the deck's front matter with `theme: name`.

Ways to add themes:

- In Settings | Tools | Marp, add CSS files, folders (all `.css` files inside, including subfolders, are loaded) or URLs to the themes list.
- Put a `.marprc.yml` with a `themeSet` in the project root, the same file Marp CLI uses. It is picked up automatically:

  ```yaml
  themeSet: themes
  ```

  Paths are relative to the `.marprc.yml` file. See `samples/.marprc.yml` and `samples/themes/demo.css` for a working example.

### Migrating from VS Code

If you used the Marp for VS Code extension:

- Setting `markdown.marp.themes`: copy its entries (files, folders or URLs) into Settings | Tools | Marp. Relative paths like `./themes/sketch.css` work as they are.
- A `.marprc.yml` with `themeSet` needs no migration. It is picked up automatically.

## Known limitations

- Twemoji images and KaTeX fonts are loaded from a CDN. Offline you lose emoji and KaTeX glyphs. MathJax works offline, so prefer it if you work without a network.
- Remote Development split mode (JetBrains Gateway thin client) is not supported.
- Export is not available yet. It is planned via Marp CLI.

## Development

Requirements: JDK 25 (Gradle finds or provisions it through the daemon JVM criteria in `gradle/gradle-daemon-jvm.properties`) and Node.js LTS with npm for the webview.

```sh
./gradlew buildPlugin        # build the plugin ZIP into build/distributions
./gradlew runIde             # run a sandbox IDE with samples/ opened
./gradlew runPhpStorm -PphpStormPath=/path/to/phpstorm   # run in a local PhpStorm
./gradlew test               # Kotlin tests
./gradlew check              # all tests, including the webview vitest suite
./gradlew verifyPlugin       # IntelliJ Plugin Verifier against recommended IDEs
```

The preview page lives in `webview/` (marp-core bundled with esbuild). Gradle runs `npm ci` and `node build.mjs` for you (`buildWebview`, `testWebview`). To work on it directly:

```sh
cd webview
npm ci
npm test
npm run typecheck
```

Third-party licenses: `build.mjs` generates `THIRD-PARTY-NOTICES.txt` (all bundled npm packages with their full license and NOTICE texts, taken from the esbuild metafile) into the webview output, so it ships in the plugin jar under `/webview/`, next to esbuild's `marp-preview.js.LEGAL.txt` with the kept license banners. The package list in [NOTICE](NOTICE) between the `BEGIN GENERATED` and `END GENERATED` markers is generated too: after changing webview dependencies (for example a Dependabot bump) run `npm run notices` in `webview/` and commit the result. `./gradlew check` runs `npm run notices:check` and fails when NOTICE is stale.

`npm ci` may warn that `@xmldom/xmldom` has known issues. It is a dependency of speech-rule-engine (pulled in by mathjax-full), is tree-shaken out of the bundle and is not shipped.

In `runIde` the JCEF DevTools are available on port 9222 (open `http://localhost:9222` in a Chromium browser). `runPhpStorm` uses port 9223.

IntelliJ run configurations for these tasks are in `.run/`. See `docs/ARCHITECTURE.md` for how the plugin is put together.

## License

MIT, see [LICENSE](LICENSE).

## Credits

- [Marp](https://marp.app/) by the Marp team: marp-core and Marpit do the actual rendering.
- Code ported from [marp-vscode](https://github.com/marp-team/marp-vscode) (MIT, Marp team) and from the scroll sync of the [VS Code](https://github.com/microsoft/vscode) Markdown preview (MIT, Microsoft Corporation).
- Third-party packages bundled in the webview are listed in [NOTICE](NOTICE).

Marp Preview is an independent project and is not affiliated with the Marp team.
