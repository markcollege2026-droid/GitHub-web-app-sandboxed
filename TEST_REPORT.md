# Test report — GitHub WebView (Locked)

## Verified GitHub Actions result

**Passed:** [Android CI run 39168860352](https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/actions/runs/39168860352), on commit `9e17807` (`Document successful GitHub Actions verification`). All blocking jobs completed successfully; the tag-only release job was skipped, as expected.

| Check | Result |
| --- | --- |
| JVM unit/security tests | **Passed — 2,445 test cases, 0 failures.** Includes URL policy/fuzz, host-role/path regression, egress proxy, manifest/network configuration, `adjustResize`, user-agent, and source invariants. |
| Android WebView/emulator tests | **Passed — 54 test cases, 0 failures** on an API 34 x86_64 Google APIs / Pixel 5 emulator. |
| Debug and release APK build | **Passed.** The workflow verified package badging, version, INTERNET permission, absence of camera/location/storage/contacts permissions, non-debuggable release manifest, a single launcher activity, and the debug APK signature. |
| APK artifact | **Published:** `github-webview-apk` (2,143,060-byte artifact archive). It contains the debug and release APKs, APK SHA-256 checksums, and release badging output. Download it from the linked Actions run's Artifacts section. |
| Instrumented evidence | **Published:** `instrumented-test-evidence`, including emulator test output and device logs/screenshots. |

The debug APK's signature was verified with `apksigner`. Release signing is optional and depends on repository secrets; this report does not claim that the release APK is signed. The artifact archive's API digest is `sha256:9e4714bd9694d2baeffadb1058bcd0e6f3656f16665f187988dbf35627572ae0` (this is the ZIP digest, not an individual APK checksum).

## Live WebView observations from the emulator run

- `LiveGitHubSmokeTest` reached `https://github.com/`; the page stayed on the GitHub origin and `evaluateJavascript` returned `2`.
- `GitHubLoginLiveTest` reached `https://github.com/login`. The DOM probe found the username text field (`name=login`, `autocomplete=username`), password field, and same-origin `https://github.com/session` form action. **No credentials were entered or submitted.** This is not proof of successful authentication.
- The device egress probe reported `CONNECT github.com:443` as `200 Connection established`; the instrumented egress guard cases passed.
- The observed favicon paths under `github.githubassets.com/favicons/` are now allowed as resource-only. The observed `api.github.com/_private/browser/stats` request remains refused with `PATH_NOT_ALLOWED`; GitHub telemetry (`collector.github.com`) and marketing-site imagery (`images.ctfassets.net`) are also refused. These are intentionally not required for core browsing or login.
- The activity/manifest `adjustResize` invariant and platform-default WebView user-agent checks passed. **A visible software keyboard/IME interaction was not performed**, so actual keyboard layout/rendering remains unverified.

## Local workspace limitation

The same JVM test task was attempted locally, but Gradle could not start because this environment has no Java/JDK:

```text
./gradlew --no-daemon :app:testDebugUnitTest --stacktrace
ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
```

No local APK was produced. The successful build and test results above are from GitHub Actions.

## Corrections made after earlier CI failures

- The first CI run exposed an outdated concurrent-tunnel test fixture using `auth.github.com`, which is correctly absent from the production allow-list. The fixture now uses `github.com`; the final JVM suite reports 2,445 cases with zero failures.
- The first live-login assertion treated every path refusal on a reviewed host as a host failure. The test now distinguishes intentional `PATH_NOT_ALLOWED` refusals (including nonessential browser-stats telemetry) from host/role mismatches. The observed favicon directory was added narrowly, and the final instrumented suite reports 54 cases with zero failures.

## Authentication and feature limits

No credential submission, real account sign-in, organization SAML/SSO, passkey, 2FA, CAPTCHA, upload/download, or visible keyboard test was performed. External identity providers and unreviewed paths/hosts remain blocked. GitHub may use additional endpoints for some workflows; those will fail closed until individually reviewed. The release APK is installable only when signed; use the verified debug APK artifact by default unless a maintainer has configured release-signing secrets.
