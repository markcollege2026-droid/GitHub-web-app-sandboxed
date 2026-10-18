# GitHub WebView (Locked) — unreleased

This is the initial GitHub-specific conversion of the existing hardened Arena WebView source. It has a distinct application ID (`app.githubwebview.locked`) so it can install alongside the Arena app. The original repository and its `main` branch are not modified by this checkout.

## Included

- GitHub branding, start URL, launcher mark, app name, project name, and package/application identity.
- Exact allow-list with `github.com` as the only page-capable host and reviewed resource-only GitHub hosts/path scopes.
- HTTPS/system-CA network configuration and release-signing setup that imports no demo key or credential.
- Tests/documentation/Actions adapted for the GitHub build.
- Original WebView security, egress proxy, navigation/popup role distinctions, back/error behavior, and software-keyboard resize fix retained.
- Android WebView's default user-agent retained; no attempt is made to hide the WebView marker or bypass an authentication provider's embedded-browser policy.

## Known limitations

- The production policy enables no external identity-provider host. Organization SAML/SSO or other off-GitHub authentication hand-offs will be blocked.
- Downloads, archive/release assets, and file uploads remain blocked by the inherited security design.
- The production API/resource host set has not yet been validated by authenticated GitHub browsing or editing.
- No APK, passing test run, live authentication, or keyboard/IME result is claimed until CI/device verification completes. See `TEST_REPORT.md`.

## Build artifact and signing

The `github-webview-apk` Actions artifact is configured to include the installable debug APK, release APK, SHA-256 checksums, and badging output. Without optional signing secrets, the debug APK is signed with the standard debug key and the release APK remains unsigned. No private or demo keystore is committed.
