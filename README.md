# GitHub WebView (Locked)

A separate Android application that opens GitHub in the existing hardened WebView architecture. The app ID is `app.githubwebview.locked`, so it installs alongside the original Arena app rather than replacing it. This checkout is a separate copy of the verified Arena source (`86008ec377e7dddf94f33c1b36a6eb8395cd867e`); no changes are made to the original repository or its `main` branch.

**Web app:** <https://github.com/>

**Version:** 1.0.0 (unreleased) · **minSdk:** 24 · **targetSdk:** 35

**Permissions:** `INTERNET`, `ACCESS_NETWORK_STATE` only.

The interface is GitHub itself, rendered in one Android WebView. The wrapper does not reimplement GitHub, automate authentication, solve challenges, or launch a system browser. Existing WebView security, navigation, back-stack, error, and software-keyboard resize handling are retained. The platform WebView user-agent is left unchanged; the app does not hide the WebView marker to work around provider restrictions.

## Current verification status

No APK is claimed by this repository until the GitHub Actions workflow produces it. See [TEST_REPORT.md](TEST_REPORT.md) for the tests that were attempted and their actual results. This agent environment did not have a JDK installed, so the local Gradle test/build attempt could not start; Actions is configured to build and upload the APK artifacts.

## What works by policy

The page-capable host is **only** `github.com`. This covers normal GitHub web pages, including repository browsing, commits, issues, pull requests, Actions/workflow pages, workflow editing, and same-origin login/session pages. GitHub-owned supporting hosts do not inherit page permission:

| Exact host | Role | Path scope | Purpose |
| --- | --- | --- | --- |
| `github.com` | `PRODUCT` | Any path | Visible pages, same-origin authentication, repositories and web workflows |
| `github.githubassets.com` | `ASSET` only | `/assets/`, `/favicons/` | GitHub JavaScript, CSS, fonts, and observed login/site icons |
| `opengraph.githubassets.com` | `ASSET` only | Exact host; opaque image paths | Repository/social preview images |
| `avatars.githubusercontent.com` | `ASSET` only | Exact host; opaque image paths | User and organization avatars |
| `user-images.githubusercontent.com` | `ASSET` only | Exact host; opaque image paths | User-uploaded repository, issue, and PR images |
| `private-user-images.githubusercontent.com` | `ASSET` only | Exact host; opaque image paths | Private-repository attachments |
| `raw.githubusercontent.com` | `ASSET` only | Exact host; opaque content paths | Raw repository content embedded in GitHub pages; never a top-level destination |
| `github-cloud.s3.amazonaws.com` | `ASSET` only | Exact host; opaque object paths | Exact GitHub-linked content bucket |
| `api.github.com` | `ASSET` only | `/repos/`, `/orgs/`, `/users/`, `/user/`, `/notifications/`, `/search/`, `/graphql/`, `/rate_limit/` (the exact route is also accepted without its trailing slash) | Selected in-page REST/GraphQL data |

There are no wildcard entries. Redirects, top-level navigation, pop-ups, embedded documents, subresources, service workers, and egress-proxy tunnels retain distinct checks. Resource-role hosts cannot become visible pages. `raw.githubusercontent.com` is usable as an in-page resource, but direct raw-file navigation is blocked.

Unlisted destinations stay blocked, including Google identity hosts, organization-specific SAML/SSO providers, `docs.github.com`, `support.github.com`, `gist.github.com`, GitHub Pages, `github.dev`, Copilot endpoints, archive/download hosts such as `codeload.github.com` and `objects.githubusercontent.com`, and unrelated websites. A new host should be added only after observing a real GitHub workflow need and reviewing the exact host, role, and path scope.

## Authentication and workflow limitations

- GitHub's ordinary login page and `/session` form endpoint are on `github.com`, so they are within the product role. The app does not submit test credentials or claim that an account sign-in succeeded.
- Authentication that redirects to an external identity provider (for example, organization SAML/SSO) is blocked unless that exact provider host is separately reviewed and explicitly added with the `SIGN_IN` role. The existing in-app sign-in-window routing remains in the source, but the production GitHub allow-list has **no external sign-in host**.
- No Google authentication host is enabled. Provider decisions are not bypassed: the default Android WebView user-agent is retained, and no system browser or Custom Tab fallback is used.
- Downloads and file pickers/uploads are refused by the preserved security policy. This means archive downloads, release assets, and attachment uploads may not work even when the surrounding GitHub page loads. Text-based editing, commits, issues, PRs, and Actions pages remain ordinary `github.com` navigation where GitHub does not require a blocked external hand-off.
- GitHub may change its asset or API routes. An unlisted host or path will fail closed and may cause a page feature to be incomplete; review evidence and add only the narrowest necessary entry.
- CAPTCHA/bot-protection hosts are not added by default. The wrapper does not solve, automate, or bypass a challenge.

## Build locally

Prerequisites: JDK 17 and Android SDK platform 35/build-tools 35.x. The Gradle wrapper is included.

```bash
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:assembleDebug
./gradlew --no-daemon :app:assembleRelease
```

Install the debug APK (signed with the local Android debug key):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The release variant is unsigned unless all four release-signing values are supplied: `SIGNING_KEYSTORE_PATH`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, and `SIGNING_KEY_PASSWORD`. No signing key is committed. In GitHub Actions, configure `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, and `SIGNING_KEY_PASSWORD` repository secrets to sign the release variant; otherwise the workflow still publishes an installable debug APK and an unsigned release APK.

## GitHub Actions artifacts

`.github/workflows/android.yml` runs JVM tests, builds debug and release APKs, checks package identity/permissions, and runs the instrumented suite on an Android emulator. The **`github-webview-apk`** workflow artifact contains APKs, SHA-256 checksums, and release badging output. The debug APK is the installable default artifact. A `v*` tag additionally creates a GitHub Release; this checkout has no release/tag yet. Instrumented test evidence is uploaded separately as `instrumented-test-evidence`.

## Security architecture

- Pure-Java, fail-closed URL policy with exact host/role/path matching; HTTPS and port 443 only.
- A local `CONNECT`-only egress proxy limits outbound tunnels to exact allow-listed hosts on port 443. TLS remains end-to-end and is not inspected. WebView request hooks additionally enforce path and traffic-role rules.
- System CA trust only; cleartext and mixed content disabled; TLS errors cancelled; Safe Browsing enabled.
- One exported launcher activity, no deep-link filter, no JavaScript bridge, no external activity launches, no downloads, no file access, and no runtime permission beyond network access.
- Pop-ups are revalidated and routed into the single visible WebView or blocked. Only a separately approved exact `SIGN_IN` host can use the existing in-app authentication window.
- The original input workaround is retained: Android's WebView user-agent is not spoofed, and the activity/manifest keep `adjustResize` for the software keyboard.

Read [SECURITY.md](SECURITY.md) for the threat model and [REVIEW.md](REVIEW.md) for conversion decisions. [TEST_REPORT.md](TEST_REPORT.md) records executed checks and explicitly marks what remains unverified.
