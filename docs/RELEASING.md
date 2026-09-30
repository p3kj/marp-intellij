# Release process

Releases are built and published by GitHub Actions. Tags have no `v` prefix: the tag is the version string (`0.9.0`, `1.0.0-beta.1`). Never create `v0.9.0` tags by hand.

## One-time setup

1. **Marketplace vendor and first upload.** The first version of a plugin has to be uploaded by hand at https://plugins.jetbrains.com/plugin/add (vendor profile, license MIT, source code URL, tags, at least one screenshot of 1200 x 760 or larger). Use the ZIP from the `plugin-distribution` artifact of a green Build run, or `./gradlew buildPlugin`. The repository must be public first. Later versions go through `publishPlugin`. Do not publish the GitHub draft release before the first manual upload: it would start the Release workflow, which fails without the secrets and still consumes the tag.
2. **Marketplace token.** Marketplace profile, My Tokens, new token. Store it as the repository secret `PUBLISH_TOKEN`.
3. **Signing certificate** (see https://plugins.jetbrains.com/docs/intellij/plugin-signing.html):

   ```sh
   openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
   openssl rsa -in private_encrypted.pem -out private.pem
   openssl req -key private.pem -new -x509 -days 3650 -out chain.crt
   ```

   Repository secrets: `CERTIFICATE_CHAIN` is the content of `chain.crt`, `PRIVATE_KEY` is the content of `private.pem`, `PRIVATE_KEY_PASSWORD` is the passphrase you gave for `private_encrypted.pem`. Keep the files outside the repository.
4. Optional: `QODANA_TOKEN` from Qodana Cloud. Without it the Qodana workflow skips itself.
5. In the repository settings, enable private vulnerability reporting (see SECURITY.md), add topics and set the homepage to the Marketplace listing once it exists.

So four secrets are needed for releases: `PUBLISH_TOKEN`, `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`.

## Every release

1. Set `version` in `gradle.properties` (SemVer). A suffix such as `-beta.1` selects the Marketplace channel `beta`, no suffix means the default channel.
2. Make sure the `[Unreleased]` section of `CHANGELOG.md` describes the release. Push or merge to `main`.
3. The Build workflow (build, tests, verifyPlugin) creates a draft GitHub release named after the version, with the Unreleased section as notes. It deletes older drafts, including hand-made ones.
4. Run the manual dynamic-unload check from [CONTRIBUTING.md](../CONTRIBUTING.md).
5. Open the draft and edit the notes if needed. They become the plugin's change notes and the CHANGELOG section. Publish the release (tick "pre-release" for a beta). The draft points at `main`, and the tag is created at publish time on the then-current head, so publish it promptly.
6. Publishing triggers the Release workflow. It checks that the tag equals the Gradle `version`, patches `CHANGELOG.md` with the release notes, builds, signs and publishes the plugin, uploads the ZIPs to the release and opens a "Changelog update" pull request. Merge that PR, otherwise the next release repeats these entries.
7. Marketplace moderation takes up to 2 business days for a new plugin. Updates are usually faster. Check the listing afterwards.

## Pre-release channel

A version like `0.2.0-beta.1` is published to the `beta` channel. Users subscribe by adding `https://plugins.jetbrains.com/plugins/beta/list` in Settings | Plugins | Manage Plugin Repositories. Versions on a custom channel override the stable one for those users, so publish the final version to the default channel as usual. The changelog plugin merges beta sections into the final version's section. Do not make the first release a beta: custom channels are invisible to normal users.

## Compatibility range

`sinceBuild` is `262` (2026.2), there is no until-build. Do not raise it to a point release such as `262.10968`: that would lock out earlier 2026.2 users. A weekly workflow (`verify-eap.yml`) runs the Plugin Verifier against the current release and the next EAP. When it fails, fix the plugin or set an until-build before the next IDE release.

## If a release fails

- The Release workflow failed before `publishPlugin`: fix the cause, delete the GitHub release and its tag, re-run the Build workflow to recreate the draft, publish again.
- The Release workflow failed after `publishPlugin`: the Marketplace already has the version and never accepts the same version twice. Do the remaining steps (upload the assets, changelog PR) by hand.

## After the first Marketplace release

- README: in the Installation section, make the Marketplace path the main one and drop the "not on the Marketplace yet" sentence.
- README: uncomment the Marketplace version and downloads badges and replace `NNNNN` with the numeric plugin id.
- Node: `NODE_VERSION` in the workflows is 24. Node 26 becomes LTS on 2026-10-28, bump it then (Dependabot does not change `env:` values).
