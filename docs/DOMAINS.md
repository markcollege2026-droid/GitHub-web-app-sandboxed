# Reviewed host/role inventory

This is the entire configured set, not a suffix/wildcard policy. Every host permits TLS tunnels
on **443 only**. `PRODUCT` / `SIGN_IN` may be top-level pages; `ASSET` may only be a request or
embedded document. Redirects and pop-ups do not convert an ASSET into a navigation destination.

## Evidence and selection

* Primary HTML and Content-Security-Policy retrieved from `https://github.com/login`, a public
  repository/file page and an Actions page on 2026-10-18. A cookie-free/header-only snapshot is in
  [observed-github-csp.txt](observed-github-csp.txt). JS/CSS on `github.githubassets.com`, avatars,
  APIs, file-preview origins, exact GitHub S3 buckets and the 20 exact Actions result-store accounts
  are identified there. Presence in CSP is evidence of intended use, not proof a feature was exercised.
* Homepage HTML explicitly uses Contentful space `8aevphvgewt8`; only that prefix is approved on
  `images.ctfassets.net` and `videos.ctfassets.net`, not every customer's content.
* GitHub's [connectivity documentation source](https://github.com/github/docs/blob/dae83f7d28f3b8e10015bd6b6d8b72267cedceca/content/get-started/using-github/troubleshooting-connectivity-problems.md)
  identifies Octocaptcha/Arkose for account verification. Exact challenge client endpoints are
  scoped to `/fc/` and `/v2/`, not the general Arkose site. Actual per-run observations are reported
  separately; not every optional challenge is forced or encountered by CI.
* From this editing network, `/signup` returned a DataDome interstitial referencing
  `ct.captcha-delivery.com/c.js` and `geo.captcha-delivery.com`. These are explicitly resource-only
  challenge allowances; this observation is network-specific, not a claim that all GitHub users
  see DataDome. The Android signup probe additionally requested `/i.js` and `/interstitial.1.33.1.202610161.js` on `ct.captcha-delivery.com`. Pinning only bootstrap files caused an actual blocked-script failure; this dedicated challenge-asset host is therefore a reviewed path-unrestricted ASSET exception, like gstatic. The signed-out Chrome trace also requested `static.captcha-delivery.com`; it is resource-only. No challenge was solved or bypassed.
* GitHub supports Google social sign-in. [1](https://github.blog/changelog/2025-07-15-social-login-with-google-is-now-generally-available/)
  The original Google identity entries and reCAPTCHA support are retained. The real Android Google
  probe also requested `accounts.youtube.com/accounts/CheckConnection`; that exact connection-check
  prefix is approved only as a resource/embedded frame. It grants no YouTube browsing or video access.
  `play.google.com/log` remains refused, and no complete authentication claim follows from these allowances. The exact opaque
  gstatic identity-bundle paths must not be repinned to `/accounts/`: that caused the original
  superimposed-email/dead-form bug. Arkose replaces the Arena-only Cloudflare Turnstile exception.
* `alive.github.com` is the production real-time channel; it is **not proof that every Actions
  log transport works**. The encrypted proxy checks its host, not WebSocket frames. The shared-channel design is also described in [3](https://github.com/Hacksore/github-websocket-api). Dynamic
  regional `*.actions.githubusercontent.com` hosts are NOT blanket-approved. Such a host needs a
  concrete observation and reviewed exact-host entry; otherwise that part of live logs may fail closed.

The automated desktop observations and Android probes in the CI artifacts are different tests.
A signed-out page load, a policy allow decision and a completed authenticated workflow are not
interchangeable evidence. See [../TEST_REPORT.md](../TEST_REPORT.md) for actual outcomes.

## Complete configuration

| Exact host | Role | Allowed path prefixes | Change from Arena |
|---|---|---|---|
| `github.com` | PRODUCT | Any path | Added |
| `www.github.com` | PRODUCT | Any path | Added |
| `github.githubassets.com` | ASSET | Any path | Added |
| `api.github.com` | ASSET | Any path | Added |
| `avatars.githubusercontent.com` | ASSET | Any path | Added |
| `private-avatars.githubusercontent.com` | ASSET | Any path | Added |
| `identicons.github.com` | ASSET | Any path | Added |
| `user-images.githubusercontent.com` | ASSET | Any path | Added |
| `private-user-images.githubusercontent.com` | ASSET | Any path | Added |
| `secured-user-images.githubusercontent.com` | ASSET | Any path | Added |
| `camo.githubusercontent.com` | ASSET | Any path | Added |
| `raw.githubusercontent.com` | ASSET | Any path | Added |
| `media.githubusercontent.com` | ASSET | Any path | Added |
| `objects.githubusercontent.com` | ASSET | Any path | Added |
| `objects-origin.githubusercontent.com` | ASSET | Any path | Added |
| `release-assets.githubusercontent.com` | ASSET | Any path | Added |
| `codeload.github.com` | ASSET | Any path | Added |
| `uploads.github.com` | ASSET | Any path | Added |
| `viewscreen.githubusercontent.com` | ASSET | Any path | Added |
| `notebooks.githubusercontent.com` | ASSET | Any path | Added |
| `repository-images.githubusercontent.com` | ASSET | Any path | Added |
| `opengraph.githubassets.com` | ASSET | Any path | Added |
| `alive.github.com` | ASSET | Any path | Added |
| `github-cloud.s3.amazonaws.com` | ASSET | Any path | Added |
| `github-production-repository-file-5c1aeb.s3.amazonaws.com` | ASSET | Any path | Added |
| `github-production-upload-manifest-file-7fdce7.s3.amazonaws.com` | ASSET | Any path | Added |
| `github-production-user-asset-6210df.s3.amazonaws.com` | ASSET | Any path | Added |
| `github-production-repository-image-32fea6.s3.amazonaws.com` | ASSET | Any path | Added |
| `github-production-release-asset-2e65be.s3.amazonaws.com` | ASSET | Any path | Added |
| `productionresultssa0.blob.core.windows.net` | ASSET | Any path | Added |
| `productionresultssa1.blob.core.windows.net` | ASSET | Any path | Added |
| `productionresultssa2.blob.core.windows.net` | ASSET | Any path | Added |
| `productionresultssa3.blob.core.windows.net` | ASSET | Any path | Added |
| `productionresultssa4.blob.core.windows.net` | ASSET | Any path | Added |
| `productionresultssa5.blob.core.windows.net` | ASSET | Any path | Added |
| `productionresultssa6.blob.core.windows.net` | ASSET | Any path | Added |
| `productionresultssa7.blob.core.windows.net` | ASSET | Any path | Added |
| `productionresultssa8.blob.core.windows.net` | ASSET | Any path | Added |
| `productionresultssa9.blob.core.windows.net` | ASSET | Any path | Added |
| `productionresultssa10.blob.core.windows.net` | ASSET | Any path | Added |
| `productionresultssa11.blob.core.windows.net` | ASSET | Any path | Added |
| `productionresultssa12.blob.core.windows.net` | ASSET | Any path | Added |
| `productionresultssa13.blob.core.windows.net` | ASSET | Any path | Added |
| `productionresultssa14.blob.core.windows.net` | ASSET | Any path | Added |
| `productionresultssa15.blob.core.windows.net` | ASSET | Any path | Added |
| `productionresultssa16.blob.core.windows.net` | ASSET | Any path | Added |
| `productionresultssa17.blob.core.windows.net` | ASSET | Any path | Added |
| `productionresultssa18.blob.core.windows.net` | ASSET | Any path | Added |
| `productionresultssa19.blob.core.windows.net` | ASSET | Any path | Added |
| `images.ctfassets.net` | ASSET | `/8aevphvgewt8/` | Added |
| `videos.ctfassets.net` | ASSET | `/8aevphvgewt8/` | Added |
| `accounts.google.com` | SIGN_IN | Any path | Retained |
| `accounts.youtube.com` | ASSET | `/accounts/CheckConnection` | Added |
| `ssl.gstatic.com` | ASSET | Any path | Retained |
| `www.gstatic.com` | ASSET | Any path | Retained |
| `apis.google.com` | ASSET | `/js/`, `/gsi/`, `/_/` | Retained |
| `fonts.gstatic.com` | ASSET | `/s/` | Retained |
| `lh3.googleusercontent.com` | ASSET | `/a/` | Retained |
| `www.google.com` | ASSET | `/recaptcha/` | Retained |
| `www.recaptcha.net` | ASSET | `/recaptcha/` | Retained |
| `octocaptcha.com` | ASSET | Any path | Added |
| `github-api.arkoselabs.com` | ASSET | `/fc/`, `/v2/` | Added |
| `client-api.arkoselabs.com` | ASSET | `/fc/`, `/v2/` | Added |
| `static.captcha-delivery.com` | ASSET | Any path | Added |
| `geo.captcha-delivery.com` | ASSET | `/captcha/`, `/interstitial/` | Added |
| `ct.captcha-delivery.com` | ASSET | Any path | Added |

**66 exact hosts:** 2 PRODUCT,
1 SIGN_IN,
63 ASSET.
`AllowlistRegressionTest` pins the literal list, roles and reviewed path exceptions.

ASSET is a URL role, not an enforced MIME type. Paths are **prefixes**, using the original engine's
raw-path checks (no percent escapes or `..` in pinned paths). These are prefix rules,
not exact-file matchers. The proxy cannot inspect encrypted paths or every redirect hop; see
[../SECURITY.md](../SECURITY.md).


## Delta from Arena 1.2.0

**58 hosts added, 8 retained, 13 removed.** The table marks each added host and its role.

Retained with unchanged role/path scope: `accounts.google.com`, `apis.google.com`, `fonts.gstatic.com`, `lh3.googleusercontent.com`, `ssl.gstatic.com`, `www.google.com`, `www.gstatic.com`, `www.recaptcha.net`.

Removed (not reachable by the GitHub configuration):

* `agent.arena.ai`
* `arena.ai`
* `auth.arena.ai`
* `blog.arena.ai`
* `cdn.arena.ai`
* `challenges.cloudflare.com`
* `code.arena.ai`
* `help.arena.ai`
* `indexable.arena.ai`
* `news.arena.ai`
* `status.arena.ai`
* `trust.arena.ai`
* `www.arena.ai`

No retained resource host was promoted to a page. The Google connection-check addition is ASSET-only.

## Intentionally not approved

All Arena hosts; arbitrary external links, app/deep-link schemes and insecure HTTP;
GitHub Pages, Gist, Docs/Support/blog external sites, Codespaces/VS Code/Copilot; telemetry such as
`collector.github.com`; staging hosts; arbitrary S3/Azure accounts; arbitrary enterprise SSO;
Apple ID and other identity providers; Google Search outside reCAPTCHA, Gmail, Drive, Play and
unlisted Google hosts. A GitHub-owned name is not by itself approval.

Raw and archive hosts are ASSET-only: navigating a Raw/ZIP link is still refused (an asset host can
never become the page), but the bytes may be **saved** - that is what "resource" means, and since
`FileDownloads` the app fetches those bytes itself through the same egress guard. A download is judged
with the request role (`evaluateRequest`), so it can never widen what is navigable: `docs.github.com`,
`github.blog`, `www.githubstatus.com`, Pages, Gist and every other unlisted host remain unreachable even
as a download target.
Download the **APK** from the workflow artifact in your browser, not through this locked wrapper.

## Google handoff boundary

Only `accounts.google.com` is approved as a non-product page. It is allowed in the main WebView
or the original in-app sign-in window (retaining opener/cookies); the inherited policy is host-based,
not a stateful OAuth client-ID/redirect-URI verifier. Google resource hosts never become pages.
The app keeps the platform user agent, makes no browser substitution and does not add any external
fallback. A provider restriction remains a restriction, even if the initial email form is displayed.
