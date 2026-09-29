<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Marp Preview Changelog

## [Unreleased]

### Added

- Live Marp slide preview in JCEF next to the Markdown editor, refreshed while typing (no save needed)
- Editor / Split / Preview layouts for Markdown files with `marp: true`; other Markdown files keep the regular preview
- "Marp deck detected" banner when `marp: true` is added to a file that is already open
- Two-way scroll sync between the editor and the preview, highlight of the slide under the caret, double-click in the preview jumps to the source line
- Custom themes from files, folders and URLs, configured in Settings | Tools | Marp
- Automatic pickup of `themeSet` from `.marprc.yml`, `.marprc.yaml`, `.marprc.json` or `.marprc` in the project root
- Theme CSS edits show up in the preview while typing, before the file is saved
- Warning in the preview when the `theme:` directive names a theme that is not available
- Local image support, including `![bg](...)` backgrounds
- Math rendering with MathJax (works offline) or KaTeX
- Setting for inline HTML in slides: off, default or all
- Preview background follows the IDE light or dark theme
- Locked-down preview page: Content Security Policy, no navigation away from the preview, links open in the IDE (local files inside the project) or the system browser
- Untrusted projects render without HTML and without remote or `.marprc` themes until trusted
- Third-party license notices for the bundled webview packages (THIRD-PARTY-NOTICES.txt inside the plugin)
- Support for all IntelliJ-based IDEs on the 2026.2 platform and newer
