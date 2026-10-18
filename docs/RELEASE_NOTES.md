# GitHub WebView (Locked) — unreleased

This is the initial GitHub-specific conversion of the existing hardened Arena WebView source. It has a distinct application ID (`app.githubwebview.locked`) so it can install alongside the Arena app. The original repository and its `main` branch are not modified by this checkout.

## Included

- GitHub branding, start URL, launcher mark, app name, project name, and package/application identity.
- Exact allow-list with `github.com` as the only page-capable host and reviewed resource-only GitHub hosts/path scopes.
- HTTPS/system-CA network configuration and release-signing setup that imports no demo key or credential.
- Tests/documentation/Actions adapted for the GitHub build.
- Original WebView security, egress proxy, navigation/popup role distinctions, back/error behavior, and software-keyboard resize fix retained.
- Android WebView's default user-agent retained; no attempt is made to hide the WebView marker or bypass an authentication provider's embedded-browser policy.

## Verification

[GitHub Actions run 39168265984](https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/actions/runs/39168265984) passed 2,445 JVM test cases, 54 API 34 instrumented WebView cases, and the debug/release APK build. It verified APK badging and the debug APK signature, then published the `github-webview-apk` artifact with APK checksums and badging output. The public homepage and login DOM probes ran without submitting credentials. See `TEST_REPORT.md` for results and limitations.

## Known limitations

- The production policy enables no external identity-provider host. Organization SAML/SSO or other off-GitHub authentication hand-offs will be blocked.
- Downloads, archive/release assets, and file uploads remain blocked by the inherited security design.
- GitHub's public homepage and login DOM have been exercised, but authenticated repository editing, commits, issues, PRs, Actions, uploads/downloads, and organization flows were not tested with an account.
- The keyboard resize invariant was tested in source; visible IME/input behavior was not tested on a physical device or by tapping the login field.
- GitHub-internal telemetry and unlisted hosts or paths remain blocked by design. Features depending on them may be incomplete.

## Build artifact and signing

The `github-webview-apk` Actions artifact contains the installable debug APK, the release APK, SHA-256 checksums, and release badging output. The debug APK's signature is verified in CI. Release signing is optional and depends on configured repository secrets; no private or demo keystore is committed.
