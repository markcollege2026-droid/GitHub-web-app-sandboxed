# GitHub WebView (Locked)

An **unofficial GitHub adaptation of the existing Arena Android app**, not a new wrapper.
Source baseline: [Arena.ai-apk at 86008ec](https://github.com/markcollege2026-droid/Arena.ai-apk/tree/86008ec377e7dddf94f33c1b36a6eb8395cd867e), Arena 1.2.0.
The verbatim import is commit `334f840` in this repository. The original repository and both main branches are unchanged.

This session works only on `arena/d30b97e2-github-web-app-sandboxed`.
The install ID is `app.githubwebview.locked` (version 1.0.0 / code 1), separate from
`app.arenawebview.locked`; the two apps can coexist.

## Preserved

The original single Activity and WebView, dark native overlays, pull-to-refresh, back behavior,
OAuth popup window, watchdog, renderer recovery, cookies, hardware rendering and `adjustResize`.
The pure-Java URL parser and PRODUCT / ASSET / SIGN_IN roles, service-worker guard,
CONNECT egress proxy with no direct fallback, TLS checks, escape/file-picker refusals, and the original
refusal to hand anything to another app. Downloads are the one added capability: they are fetched and
saved **by the app**, through that same proxy (see [Downloads](#downloads)).
No dashboards, domain management, approval flows, extra settings, script injection or input CSS fixes.

The start URL is `https://github.com/`. Only `github.com`, `www.github.com` and the approved
`accounts.google.com` handoff may be visible pages. GitHub resources and CAPTCHA/identity widgets
have ASSET roles: **being fetchable does not make a host navigable**. All hosts are exact; HTTPS/443 only.

## Authentication and input

The Arena email-field fix is retained: Google identity bundles on `www.gstatic.com` and
`ssl.gstatic.com` remain path-unrestricted **assets**, widget frames use SUBDOCUMENT rather than
DOCUMENT, and group merging cannot narrow those entries. The app never manipulates input fields.

One deliberate security difference: the inherited user-agent `; wv` removal is gone. Providers see
the genuine WebView identity; refusal of embedded sign-in is not bypassed. There is no browser or
external-app fallback. Google login, passkeys, security keys and enterprise SSO may be unavailable.
Passwords, 2FA, account permissions and completed authenticated workflows require real-device testing
with your own account; no personal credentials were requested or embedded in the project/test code.

## Builds and downloads

The original `.github/workflows/android.yml`, Gradle wrapper, SDK versions and Android dependencies
are reused. CI builds debug and **signed release** APKs, checks badging and SHA-256, and runs
unit/Android-emulator tests. The workflow artifact is **`github-webview-apk`**; install its
`app-release.apk`. Build output is not committed to Git.

**Get the APK:** open [run 39170410407](https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/actions/runs/39170410407)
and download the `github-webview-apk` artifact. GitHub only lets a **signed-in** browser download
artifacts, so a direct artifact link is not published here — fetched signed-out it answers `404`, which
is a GitHub rule and not a broken build. For a permanent, sign-in-free link, publish a tag; the
`release` job then attaches the APKs and checksums to a GitHub Release:

```sh
git tag v1.0.0 && git push origin v1.0.0   # → …/releases/download/v1.0.0/app-release.apk
```

[Delivery and installation](DELIVERY.md)

[Run 39170410407](https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/actions/runs/39170410407) passed: **4,151 JVM cases**, **60 Android tests passed / 1 skipped / 0 failed**,
and the exact signed release APK installed and launched. Native GitHub and generic Google identifier
entry/editing/resize/overlap checks passed. The actual GitHub → Google handoff was skipped because
GitHub did not offer the button in this WebView; no completed authentication is claimed.

[Run 39415422258](https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/actions/runs/39415422258)
passed all four jobs after downloads were added: source preservation, **4,169 JVM cases (0 failed)**,
the signed APK build, and the emulator suite (**61 passed, 0 failed, 2 skipped**). The emulator run
includes a live save: `FileDownloadTest` downloads a real file through the loopback guard, asserts the
`MediaStore` row and its size, then deletes it. Help and download refusals are still covered by
`refusalsAreReportedAsRefusalsNotAsOutages`.

[Run 43634780295](https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/actions/runs/43634780295)
is the most recent build (2026-11-14, source `7338b0a`): **4,009 JVM cases (0 failed)**, emulator suite
**65 passed, 0 failed, 2 skipped**, and the exact signed release APK installed and launched. Release
SHA-256 `784b0657133aacef419c14f30b9ddea918ce4f46aa284aa410b0fd7107974019`. It was built after
re-checking the live site; the allow-list did not need to change. See
[docs/SITE-RECHECK-2026-11-14.md](docs/SITE-RECHECK-2026-11-14.md).

See [TEST_REPORT.md](TEST_REPORT.md) for scope/earlier failures and [docs/EVIDENCE.md](docs/EVIDENCE.md)
for actual results. The original Arena reports are archived under `docs/upstream-arena/`, not reused
as evidence for this APK. [Exact changed files](docs/CHANGES.md) · [All domains/roles](docs/DOMAINS.md)
· [Source preservation](docs/PROVENANCE.md).

The original public demo signing key is retained for reproducible install/upgrade identity. It is
**not a private production key**. Use the existing `SIGNING_*` environment variables/secrets for a
private distribution (see `keystore/README.md`). Debug and release have the same package ID but
usually different signing keys: uninstall one before installing the other. Neither replaces Arena.

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
./gradlew --no-daemon :app:connectedDebugAndroidTest
```

Android 7+ (minSdk 24), compile/target SDK 35, JDK 17. A WebView supporting AndroidX
`PROXY_OVERRIDE` is required; otherwise the original error screen fails closed.

## Is the host list still current?

GitHub changes its site without notice. The **Site drift** workflow
(`.github/workflows/site-drift.yml`) looks at the live, signed-out site and compares it with the
allow-list the APK is built from:

* it runs every Monday (from `main`, so only once this workflow is merged), whenever the allow-list
  changes on a working branch, and on demand (Actions → *Site drift* → *Run workflow*);
* it **fails** when github.com names or requests a host that is neither allowed nor a recorded refusal
  in [docs/site-reviewed-exclusions.json](docs/site-reviewed-exclusions.json), so a new host is never
  silently adopted and never silently ignored;
* it never edits the allow-list. Allowing a host stays a reviewed change to `GitHubAllowlist.java`,
  its pinning test and [docs/DOMAINS.md](docs/DOMAINS.md);
* run by hand with *refresh* ticked on a branch, it commits the new snapshot and report itself.

The latest result is [docs/SITE-DRIFT.md](docs/SITE-DRIFT.md). It is a signed-out observation: hosts
used only when signed in, or only by a running job, cannot show up in it.

## Automatic AppLock enrollment exemption

This application declares itself exempt from **automatic enrollment/protection** by compatible
AppLock implementations that recognize the marker below. The declaration is application-level
metadata in `app/src/main/AndroidManifest.xml` and is included in the installable app's merged
manifest:

```xml
<meta-data
    android:name="dev.pranav.applock.AUTOLOCK_EXEMPT"
    android:value="true" />
```

To reproduce or verify it, inspect the `<application>` element in the source manifest, then build
an APK with `./gradlew :app:processReleaseMainManifest :app:assembleRelease`. Inspect the merged
manifest under `app/build/intermediates/merged_manifests/release/` (the exact subdirectory can vary
by Android Gradle Plugin version), or inspect the final APK with Android SDK `aapt2 dump xmltree`
and confirm the metadata name and Boolean `true` value.

This is a **self-declared opt-out**, not a security boundary. It does not remove an application
that an AppLock user manually added to a protected list, and does not bypass or disable protection
that an AppLock user manually applied.

## What the app will not open (and what you will see)

Two things on github.com leave the site on a click. Both are refused on purpose, and both now say so
instead of looking like a fault:

* **Help.** GitHub's Help, Docs, Support, Status and Blog entries are redirects to
  `docs.github.com`, `support.github.com`, `www.githubstatus.com` and `github.blog` — separate sites
  that are deliberately not on the allow-list (Terms and Privacy redirect to `docs.github.com` too).
  Clicking one shows the native "Blocked by this app" panel naming the host and the policy reason.
  By direct URL, only `github.com` and `www.github.com` are page hosts; every other listed host is a
  resource host for a github.com page and can never become the page.
* **Downloads from anywhere else.** See [Downloads](#downloads) for what is allowed; a link to an
  unapproved host, a `blob:`/`data:` link, cleartext `http` or anything above the size limit raises the
  same native panel, with the refused host and the policy reason, and nothing is written.

A load error that the policy did not cause (no network, TLS, renderer) still reports as a load
failure; one that *was* a refusal is reported as a refusal with the refused host. "Copy diagnostics"
in that panel gives the exact `BlockReason`.

## Downloads

GitHub's download links point at resource hosts (`raw.githubusercontent.com` for a file,
`codeload.github.com` for an archive, `objects.githubusercontent.com` for a release asset). Those hosts
are on the allow-list as **resources**, so the app can save their bytes - and now does:

* the WebView's `DownloadListener` forwards the URL to the app's own downloader; **no** platform
  download manager, browser, share sheet or other app is ever involved, and no storage permission is
  declared;
* the bytes are fetched by the app **through the same loopback CONNECT proxy as the WebView**, so the
  host must still be allow-listed and the port is still 443. There is no direct-connection branch: if
  the guard is not running, the download fails instead of going around it;
* every redirect hop is re-checked against the policy (at most 5), so a redirect into an unapproved
  host is refused before it is opened;
* Android 10+ saves into the system **Downloads** collection via `MediaStore` (no permission);
  Android 7-9 saves into the app's own external Downloads folder (`Android/data/app.githubwebview.locked/files/Downloads`, no permission either);
* the file name is taken from `Content-Disposition`/the URL and sanitised (no directories, control
  characters or reserved characters), the transfer is capped at **1 GiB**, progress is shown in-app with
  a Cancel button, and a failed or cancelled transfer deletes its partial file.

Refused by design: `blob:`/`data:` downloads (they would need a JavaScript bridge, which this project
does not have), `http://`, any host not on the allow-list, and files over the cap. The app never opens
the saved file: it is yours to open.

Native file pickers stay disabled. Raw/archive links to resource-only hosts are
blocked as pages. Uploaded attachments, external images not served through approved proxies,
third-party SSO, GitHub Pages, Gist, Codespaces, Copilot and arbitrary external sites are not made
browsable. In-page repository browsing, editing, commits, issues, PRs and Actions depend on the real
website and authentication; an allowlist is not proof these workflows have completed.

The proxy checks tunnel **host and port**, not encrypted paths, SNI or WebSocket messages.
See [SECURITY.md](SECURITY.md) for the inherited security boundary and limitations.
