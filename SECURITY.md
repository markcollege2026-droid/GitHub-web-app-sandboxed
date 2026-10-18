# Security model — GitHub WebView (Locked)

This document distinguishes enforced controls from unverified behavior. The app is a single-purpose Android WebView wrapper for GitHub, not a general browser or an authentication client.

## Scope and trust boundaries

The only page-capable host in the production policy is `github.com`. Other listed hosts have an `ASSET` role and may only supply approved in-page requests; they cannot become top-level pages, redirects, or popup destinations. No external identity-provider host has been approved for this build.

| Component or destination | Trust decision |
| --- | --- |
| `github.com` | Product pages, including same-origin login/session pages; only page-capable host |
| `github.githubassets.com` | Resource-only, limited to `/assets/` and `/favicons/` |
| `api.github.com` | Resource-only, limited to reviewed API path prefixes |
| `opengraph.githubassets.com`, `avatars.githubusercontent.com`, `user-images.githubusercontent.com`, `private-user-images.githubusercontent.com`, `raw.githubusercontent.com`, `github-cloud.s3.amazonaws.com` | Resource-only, exact hosts; opaque content/image paths |
| Google identity, organization SAML/SSO providers, other GitHub sites, and all other hosts | Untrusted and blocked unless a future, separate review explicitly adds an exact role and scope |
| Android WebView and system certificate store | Trusted platform dependencies; WebView must remain updated |
| User-installed certificate authorities and cleartext transports | Not trusted; cleartext is disabled and only system CAs are accepted |
| Rooted/compromised devices and modified APKs | Outside the threat model |

## Enforced controls

| Guarantee | Enforcement | Evidence in source/tests |
| --- | --- | --- |
| Exact host matching; no wildcard or suffix matching | `policy/GitHubAllowlist.java`, `AllowEntry`, `PolicyConfig`, `UrlPolicy` | `AllowlistRegressionTest`, `UrlPolicyTest`, `TunnelPolicyTest` |
| Only HTTPS and port 443; malformed URLs, userinfo, IP literals, look-alikes, and custom schemes fail closed | `UrlPolicy` and egress-proxy tunnel validation | Policy case table and fuzz tests |
| Page navigation distinct from embedded documents and resources | `DOCUMENT`, `SUBDOCUMENT`, and `SUBRESOURCE` traffic classes | `UrlPolicyTest`, `NavigationGuardTest` |
| Resource hosts cannot become the visible page | `AllowEntry.Role.ASSET`; navigation policy refuses resource-only entries | allow-list regression and WebView navigation tests |
| Path restrictions for `github.githubassets.com` (`/assets/`, `/favicons/`) and `api.github.com` | Raw-path matching in `AllowEntry` and `UrlPolicy` | allow-list regression, URL policy table, `NavigationGuardTest` |
| Redirects and popup destinations are rechecked; only an explicitly configured `SIGN_IN` entry can promote a popup to the in-app sign-in window | `ArenaWebViewClient`, `PopupRouterClient`, `ArenaWebChromeClient` | `NavigationGuardTest`, `PopupBlockingTest` (synthetic test-only provider for SIGN_IN routing) |
| No external browser/app launch, deep-link entry, file URL access, or JavaScript bridge | `MainActivity`, manifest, hardened WebView settings | `EscapeAttemptTest`, `ManifestPolicyTest`, `SourceInvariantsTest` |
| No downloads, file selection, camera/microphone capture, or geolocation prompts | `WebViewHardening`, `ArenaWebChromeClient` | `DownloadGuardTest`, `SourceInvariantsTest` |
| TLS errors are cancelled; mixed content and cleartext are disabled; only system CAs are trusted | `ArenaWebViewClient`, `network_security_config.xml`, manifest | `ManifestPolicyTest`, `SourceInvariantsTest` |
| WebView connections use a local `CONNECT`-only proxy before the first page load; no direct fallback | `EgressProxy`, `EgressGuard`, `MainActivity` | JVM proxy tests, `TunnelPolicyTest`, source invariants, instrumented egress tests (execution status is in `TEST_REPORT.md`) |
| Android WebView user-agent remains the platform default | No `setUserAgentString` call | `SourceInvariantsTest`, `WebViewSettingsTest` |
| Software keyboard resizes the WebView/activity instead of replacing the input fix with injected page code | Manifest `windowSoftInputMode="adjustResize"` and runtime window setting | `ManifestPolicyTest`; actual IME behavior still needs a device/manual test |

## Explicit domain and role policy

The complete enforced list is in `GitHubAllowlist.java`. In brief:

- `PRODUCT` — `github.com`, any path. This permits GitHub repository, editing, commit, issues, pull requests, Actions/workflow, logs, and same-origin login routes to be navigated within the app.
- `ASSET` — `github.githubassets.com` on `/assets/` and `/favicons/`. The favicon path was observed during the GitHub login probe; other paths remain blocked.
- `ASSET` — `api.github.com` under `/repos/`, `/orgs/`, `/users/`, `/user/`, `/notifications/`, `/search/`, `/graphql/`, and `/rate_limit/` (with exact root routes accepted).
- `ASSET` — `opengraph.githubassets.com`, `avatars.githubusercontent.com`, `user-images.githubusercontent.com`, `private-user-images.githubusercontent.com`, `raw.githubusercontent.com`, and `github-cloud.s3.amazonaws.com`, on those exact hosts only. These have opaque image/object/content paths and cannot be page destinations.
- No production `SIGN_IN` entries. The role and window-routing mechanism remain available in source for a separately reviewed exact provider, but it is not enabled by default.

Exact host ownership is not enough to grant navigation: raw files, avatars, API data, and static assets remain resource-only. The egress proxy sees a hostname and port, not an HTTP path; it permits only exact listed hosts and port 443. The WebView URL hooks perform the additional path and role checks.

## Authentication restrictions and known limitations

- GitHub's ordinary login is on `github.com`. The wrapper permits the same-origin login page and session endpoint, but no credentials are entered or submitted by automated tests, and no completed authentication is claimed.
- A redirect to an external identity provider (including organization SAML/SSO) is blocked unless that exact hostname is separately reviewed and added as a `SIGN_IN` entry. The existing authentication window does not itself grant access to any provider.
- The app deliberately preserves Android WebView's default user-agent, including the WebView marker where the platform supplies it. It does not disguise the WebView or use a system browser/Custom Tab to work around GitHub, Google, or another provider's restrictions. If a provider refuses embedded authentication, the app reports that limitation rather than bypassing it.
- The GitHub login instrumentation test is DOM-level only when the live page is reachable. It does not enter a username/password, test the keyboard/IME, complete 2FA/passkey flows, test SAML, or prove successful sign-in. The observed `/api.github.com/_private/browser/stats` request remains outside the approved API paths and is intentionally blocked as nonessential telemetry. See `TEST_REPORT.md` for run status.
- Uploads and downloads are blocked by the inherited security design. GitHub text editing may work while attachment uploads, release downloads, source archives, and links that redirect to external object hosts do not.
- CAPTCHA/bot-protection handling is not enabled by default and is never solved or automated. A challenge may fail if GitHub requires an unlisted host or an unsupported embedded flow.

## What the network guard does not prove

The `CONNECT` proxy is a destination/port guard, not a TLS inspection proxy. It forwards encrypted bytes and cannot inspect page content, HTTP paths, cookies, or request bodies. Path scoping is enforced by the WebView request policy, not the proxy. A compromised device, modified APK, malicious content served by an allow-listed host, or a future WebView/platform vulnerability is outside this wrapper's guarantees. The exact allow-list is not a promise that every GitHub feature will work: unsupported endpoints fail closed and must be reviewed rather than silently allowed.

The app stores its WebView session within its private application storage; Android backup is disabled. The wrapper does not read credentials from GitHub's page or expose a JavaScript bridge. Diagnostics omit query strings and fragments; do not share screenshots/logs that contain personal or account data.

## Reporting

Report a suspected policy bypass with reproduction steps and the Android/WebView version. Do not include passwords, cookies, access tokens, or private repository content. The app's diagnostics may include a refused host/path but deliberately omit query strings.
