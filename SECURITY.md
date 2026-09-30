# Security policy

## Supported versions

Only the latest release on the JetBrains Marketplace receives fixes.

## Reporting a vulnerability

Please do not open a public issue for security problems.

Use GitHub's private vulnerability reporting: https://github.com/p3kj/marp-intellij/security/advisories/new. You can also email p3k.jaros@gmail.com. You should get an acknowledgement within 7 days. Fixes ship as a plugin update. The advisory is published after the update is available.

## What counts

Marp Preview renders Markdown you open in the IDE, including raw HTML when the "HTML in slides" setting allows it, inside the IDE's embedded Chromium (JCEF). The preview is designed so that a malicious deck or theme cannot:

- run script in the preview page (Content Security Policy: only the bundled script may run, no inline scripts, event handlers, `javascript:` URLs, frames, plugins or `fetch`, and forms cannot submit),
- navigate the preview to another page, or open URLs without a click,
- read files outside the project (only files under the project base directory, content roots or the deck's folder are served, resolved through real paths),
- make the IDE open local files outside those roots, or URLs other than http(s) and mailto,
- load raw HTML, URL themes or `.marprc` themes in a project the IDE does not trust.

Reports that break any of these, or that make the IDE hang or crash from a deck, are in scope.

## Out of scope

- Twemoji images and KaTeX fonts load from a public CDN, and remote images and theme URLs are fetched from the network. This is by design and documented.
- A remote theme CSS you chose to add can observe deck content through CSS techniques. This is the same in Marp for VS Code.
- Vulnerabilities in marp-core or other bundled packages that are already public. Report them upstream. Updates arrive through Dependabot.
- Problems that need a modified IDE or plugin, or a project you have explicitly trusted and set to allow everything.
