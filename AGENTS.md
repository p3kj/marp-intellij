# Notes for AI-assisted contributors

This file is for coding agents (Claude Code, Copilot, Codex and others) and for humans using them. Human contribution rules are in CONTRIBUTING.md. This file adds the mechanics.

## Project shape

- Kotlin IntelliJ Platform plugin (`src/main/kotlin/cz/p3kj/marp`), built with the IntelliJ Platform Gradle Plugin 2.x, Kotlin 2.4, JDK 25, Gradle 9.
- Preview page in `webview/` (TypeScript, marp-core, esbuild, vitest). Gradle bundles it into the plugin jar under `/webview/`.
- Contract between the two sides: `docs/ARCHITECTURE.md` (bridge methods, host messages, URL scheme, CSP, threading). Read it before touching `MarpPreviewPanel.kt`, `MarpBridgeState.kt`, `webview/src/preview.ts` or `webview/src/types.ts`, and update it in the same change.

## Commands

```sh
./gradlew check                      # Kotlin tests, vitest, tsc, NOTICE check
./gradlew test --tests 'cz.p3kj.marp.preview.*'
cd webview && npm test               # vitest only
cd webview && npm run typecheck
cd webview && npm run notices        # after webview dependency changes
./gradlew runIde                     # manual check in a sandbox IDE (samples/ opens)
./gradlew verifyPlugin               # slow, CI runs it, run locally only for API changes
```

## Rules

- Bundle strings: every user-visible string goes into `src/main/resources/messages/MarpBundle.properties` and is read through `MarpBundle.message(...)`. Preview-page strings are sent through `setStrings`.
- Tests: add or extend tests with behavior changes. Pure logic in unit-testable classes, platform behavior in `MarpLightTestCase` subclasses, page behavior in `webview/test`.
- No em dashes anywhere (code comments, docs, UI text, commit messages). Use commas, colons, hyphens or new sentences.
- Threading: no blocking work on the EDT. Document text is read in read actions, file and HTTP I/O run on `Dispatchers.IO`, coroutine scopes are injected into services and cancelled on dispose, `CancellationException` is rethrown. Create Swing-only UI (the JCEF browser) on `Dispatchers.UI`, use `Dispatchers.EDT` only when touching the editor, PSI, VFS or documents. Everything is `DumbAware`, no components.
- API boundary: verify every platform API against the `262` branch of intellij-community before using it. Treat `@ApiStatus.Internal`, `@ApiStatus.Experimental`, `@ApiStatus.Obsolete`, deprecated APIs and anything in a `@file:ApiStatus.Internal` file as flagged, and avoid them. The one known exception is `TextEditorWithPreviewProvider`, see docs/ARCHITECTURE.md. `verifyPlugin` does not report `@Obsolete`, so check that by hand.
- EAP verification: `verifyPlugin` runs against the current release and the next EAP, and `.github/workflows/verify-eap.yml` runs it weekly. After API-related changes, run it locally if you can.
- Do not weaken the preview page security: the CSP in `webview/src/index.html`, the navigation rules in `MarpResourceRequestHandler.kt` and `MarpLinkPolicy.kt`, or the allowed-roots check for `/doc/` files.
- Add a CHANGELOG line under `[Unreleased]` for anything a user can notice.
- Commit messages: imperative subject, body with the why. Do not use `$()` in git commit commands.
- Do not edit generated regions: `NOTICE` between the `BEGIN GENERATED` and `END GENERATED` markers (run `npm run notices`), and `gradle/gradle-daemon-jvm.properties` (run `./gradlew updateDaemonJvm`).
- Never commit secrets, sandbox folders (`.intellijPlatform/`), `build/` or `webview/node_modules/`.

## Pre-flight checklist

1. `./gradlew check` passes.
2. `cd webview && npm test` passes when the webview changed.
3. New strings are in the bundle, docs and CHANGELOG are updated.
4. No em dashes in the diff.
