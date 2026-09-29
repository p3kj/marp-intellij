# Contributing to Marp Preview

Thanks for helping. Bug reports, feature requests and pull requests are welcome.

## Before you start

- Search the [issues](https://github.com/p3kj/marp-intellij/issues) first. For anything bigger than a bug fix, open an issue to discuss the approach before writing code.
- Questions about Marp syntax, directives or theme CSS belong to the [Marpit documentation](https://marpit.marp.app/), not to this tracker.
- Be kind, see [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

## Requirements

- JDK 25. Gradle provisions it through the daemon JVM criteria in `gradle/gradle-daemon-jvm.properties` (foojay resolver), so a system JDK is optional.
- Node.js LTS with npm (24 today, see `NODE_VERSION` in `.github/workflows/build.yml`). Gradle runs `npm ci` for you.
- An IntelliJ-based IDE. The project opens as a Gradle project. Run configurations are in `.run/`.

## Build and run

```sh
./gradlew buildPlugin        # plugin ZIP in build/distributions
./gradlew runIde             # sandbox IDE with samples/ opened, JCEF DevTools on http://localhost:9222
./gradlew runPhpStorm -PphpStormPath=/path/to/phpstorm   # sandbox run in a local PhpStorm, DevTools on 9223
./gradlew runIdeForUiTests   # sandbox IDE with the Robot Server on http://127.0.0.1:8082
./gradlew check              # Kotlin tests, webview vitest, tsc, NOTICE check, Kover
./gradlew verifyPlugin       # Plugin Verifier against the recommended IDEs (slow, downloads IDEs)
```

`runIdeForUiTests` opens `samples/` unless you pass `-PuiTestsProject=/path/to/project`. Its Robot Server runs scripts inside the IDE and takes screenshots of IDE components (see [intellij-ui-test-robot](https://github.com/JetBrains/intellij-ui-test-robot)). Use it as a manual tool.

## Working on the webview

The preview page is an npm project in `webview/` (TypeScript, marp-core, esbuild, vitest).

```sh
cd webview
npm ci
npm test              # vitest
npm run typecheck     # tsc --noEmit
npm run dev           # dev page with a mock IDE host on http://127.0.0.1:5173/
```

The dev page bundles in watch mode and serves any local deck, for example `http://127.0.0.1:5173/?deck=/abs/path/deck.md&themes=/abs/a.css,/abs/b.css&dark=1`. It applies the production Content Security Policy. Set `CSP=0` in the environment to switch that off while experimenting. See the "Dev page" section of [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for details.

The Kotlin and JS contract (`window.marpBridge`, host messages) is documented in `docs/ARCHITECTURE.md`. Change the document, `webview/src/types.ts` and `MarpBridgeState.kt` together.

## Third-party notices

`webview/build.mjs` writes `THIRD-PARTY-NOTICES.txt` from the esbuild metafile, and it ships in the plugin jar under `/webview/`. The package table in `NOTICE` between the `BEGIN GENERATED` and `END GENERATED` markers is generated too. After changing webview dependencies (for example a Dependabot bump) run this and commit the result:

```sh
cd webview
npm run notices
```

`./gradlew check` runs `npm run notices:check` and fails when `NOTICE` is stale. Never edit the generated block by hand.

## Code conventions

- Kotlin: official style, 4 spaces, 120 columns. Only stable IntelliJ Platform APIs. Avoid `@Internal`, deprecated, experimental and obsolete APIs (the Plugin Verifier checks part of this in CI). Verify platform APIs against the `262` branch of intellij-community.
- All user-visible strings go into `src/main/resources/messages/MarpBundle.properties` and are read with `MarpBundle.message(...)`. Texts the preview page shows are sent from the bundle through `setStrings`.
- TypeScript: strict, no runtime dependencies beyond marp-core. Keep the preview page free of inline scripts (Content Security Policy).
- Tests: add or extend a test for behavior changes. Put pure logic into small classes that can be unit-tested without the platform. Platform tests extend `MarpLightTestCase`.
- Prose (README, CHANGELOG, UI text, docs): plain dashes, colons or new sentences. No em dashes.

### Threading rules in short

- No blocking work on the EDT. Read document text in read actions. File and network I/O on `Dispatchers.IO`.
- Services are light `@Service` classes with an injected `CoroutineScope`. Editors and panels are `Disposable` and registered with `Disposer`.
- Everything is `DumbAware`. No components.
- Rethrow `CancellationException`.

The full rules are in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Manual check: dynamic unload

The plugin must load and unload without restarting the IDE. Before a release, and after changes to extensions or services:

1. Run the inspection "Plugin DevKit | Plugin descriptor | Plugin.xml dynamic plugin verification" on `plugin.xml`.
2. Start a sandbox IDE (`./gradlew runIde`), open a deck from `samples/`, then disable and re-enable the plugin in Settings | Plugins.
3. Confirm there is no "restart required" prompt and no errors in the log under the `com.intellij.ide.plugins.DynamicPlugins` category.
4. To hunt a leak, add the VM option `-XX:+UnlockDiagnosticVMOptions` and set the registry key `ide.plugins.snapshot.on.unload.fail=true`.

## Pull requests

- One topic per PR, against `main`. Keep the diff focused. Refactorings go into separate PRs.
- Describe what changed and why, and how you tested it (IDE and OS).
- CI must be green: Build, Test, Verify plugin. Run `./gradlew check` locally first.
- Add a line to the `[Unreleased]` section of `CHANGELOG.md` under `### Added`, `### Changed`, `### Fixed` or `### Removed` if users can notice the change.
- Update `docs/ARCHITECTURE.md` when the bridge, URL scheme, security rules or threading rules change.
- Commit messages: imperative subject under 72 characters, body explains the why.

By contributing you agree that your contribution is licensed under the MIT License of this project.
