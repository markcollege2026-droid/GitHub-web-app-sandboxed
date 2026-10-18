# Conversion and security review

## Scope

This is a separate GitHub-branded application built by copying the existing Arena WebView source at commit `86008ec377e7dddf94f33c1b36a6eb8395cd867e`. The original repository and its `main` branch are not changed by this checkout. The Android source namespace remains `app.arenawebview.locked` to avoid a broad rewrite; the install identity is `app.githubwebview.locked`, allowing the APK to coexist with Arena.

The conversion preserves the original single-activity/WebView architecture, local egress proxy, exact-host policy engine, navigation and popup role distinctions, native error/back handling, and `adjustResize` keyboard behavior. The existing page-input workaround was not replaced with injected JavaScript, local HTML, or CSS. The WebView's Android-default user-agent is preserved; hiding the WebView marker would risk bypassing a provider's embedded-authentication policy, so that inherited behavior was removed for this GitHub build.

## Policy decision

Only `github.com` has the `PRODUCT` role and can be a top-level page. Supporting GitHub hosts have `ASSET` role and remain unusable as top-level navigation, redirect, or popup destinations.

| Host | Role | Scope / purpose |
| --- | --- | --- |
| `github.com` | PRODUCT | Any HTTPS path on port 443; browser app, same-origin login, repository and web workflow pages |
| `github.githubassets.com` | ASSET | `/assets/` and `/favicons/` only; static scripts/styles/fonts/images and observed site icons |
| `api.github.com` | ASSET | `/repos/`, `/orgs/`, `/users/`, `/user/`, `/notifications/`, `/search/`, `/graphql/`, `/rate_limit/` (exact route roots also match) |
| `opengraph.githubassets.com` | ASSET | Exact host; repository preview images |
| `avatars.githubusercontent.com` | ASSET | Exact host; avatars |
| `user-images.githubusercontent.com` | ASSET | Exact host; user attachments |
| `private-user-images.githubusercontent.com` | ASSET | Exact host; private attachments |
| `raw.githubusercontent.com` | ASSET | Exact host; embedded raw content; top-level raw navigation blocked |
| `github-cloud.s3.amazonaws.com` | ASSET | Exact host; opaque bucket objects referenced by captured site markup |

No wildcard or suffix entries are used. Google, SAML/SSO providers, GitHub Pages, `github.dev`, Gist, Docs, Support, Copilot, release-object, and archive hosts are excluded. `SIGN_IN` remains an available policy role, but the production allow-list has no external sign-in host. Instrumented popup tests use a synthetic `.example` identity host only to verify that existing role-routing code remains narrow; it is not present in production.

The policy requires redirects and page destinations to pass normal URL role checks. The proxy itself sees only CONNECT host/port; `api.github.com` path scopes are enforced by WebView request interception. If Chromium/platform traffic is not visible to a URL hook, the proxy still refuses unlisted hosts but cannot enforce an HTTP path inside TLS.

## Evidence quality and remaining decisions

The domain set is deliberately smaller than GitHub's published broad infrastructure metadata. Captured markup was supplemented by an emulator run that reached `github.com/login` and confirmed the username, password, and same-origin `/session` form fields. That run observed GitHub icons under `github.githubassets.com/favicons/`; this exact resource path was added alongside `/assets/`. It also observed `api.github.com/_private/browser/stats`, which remains blocked as nonessential telemetry, plus third-party analytics/marketing requests that remain blocked. Markup references, preconnect hints, and Open Graph tags are evidence to investigate, not proof that a host is required during an authenticated workflow. In particular, the `github-cloud.s3.amazonaws.com` reference has not been shown necessary by a successful live upload/download session; it is exact-host and resource-only, but should be removed if live traffic shows it is unnecessary. API path prefixes are also a deliberate narrow starting scope, not a claim that every GitHub page has been exercised.

No authenticated workflow has been run in this checkout. The same-origin login page is allowed, but external identity-provider redirects are intentionally blocked. Organization SAML/SSO, passkeys, 2FA, upload/download, release assets, and challenge behavior remain unverified. The login test is designed only to inspect the login DOM and never enters credentials. The input/keyboard resize fix is preserved in manifest/activity source but has not been tested with a visible IME here.

## Current verification

GitHub Actions run [39168265984](https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/actions/runs/39168265984) passed 2,445 JVM test cases, 54 API 34 instrumented WebView cases, and debug/release APK packaging. It verified the release manifest/badging and debug APK signature and published `github-webview-apk` plus emulator evidence. The live page and login DOM probes ran; no credentials were submitted. The local JVM task still cannot start because this workspace has no `java` executable or `JAVA_HOME`. A physical/visible-IME test and authenticated GitHub workflows remain unverified. See `TEST_REPORT.md` for exact outcomes and limitations.
