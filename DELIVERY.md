# GitHub APK — delivery

**Built from your original Arena app, not a replacement implementation.** Verified on 2026-10-18 UTC.

## Download and install

### [Open the build run and download the `github-webview-apk` artifact](https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/actions/runs/39170410407)

Install **`app/build/outputs/apk/release/app-release.apk`** from the artifact ZIP.
The ZIP also contains a debug APK, SHA-256 sums, APK badging and signature verification output.
Choose the **release** APK for normal use. **GitHub only serves artifact downloads to a signed-in
browser session** — the direct per-artifact URL returns `404` when fetched signed-out, so it is not
linked. The artifact has 90-day retention (currently expires **2027-01-16**); keep your downloaded copy.

For a link that works without signing in, publish a tag. The `release` job then attaches the APKs and
checksums to a GitHub Release, which is publicly downloadable:

```sh
git tag v1.0.0 && git push origin v1.0.0
# → https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/releases/download/v1.0.0/app-release.apk
```


| Item | Value |
|---|---|
| App | GitHub WebView (Locked), unofficial wrapper |
| Application ID | `app.githubwebview.locked` |
| Version | 1.0.0 / code 1 |
| Start page | `https://github.com/` |
| Android minimum | API 24 / Android 7, with a compatible updated WebView |
| APK source | [`258e549d889bbec62f1e6735a7e4a1ba939dd089`](https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/commit/258e549d889bbec62f1e6735a7e4a1ba939dd089) |
| Build and verification | [Android CI run 39170410407](https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/actions/runs/39170410407) |

**Release APK SHA-256** (not the ZIP hash):

```text
ab044ecc6ea2da7c76747c83fee35739e0de0324fd8eb93eb36d8f16c8aa1dd8
```

The exact signed release bytes above were **installed and launched on the emulator**. Its egress
guard became ready and opened the GitHub connection. It is a different package from
`app.arenawebview.locked`, so it does not replace Arena. Debug and release use the same GitHub
package ID but different signing keys: uninstall a debug build before installing the release.

**Signing caveat:** the original public demo keystore is preserved. This is not private production
signing; use the existing private-key configuration before broader distribution. Download only the
artifact you intend to trust and verify its hash.

## What was preserved

The complete Arena 1.2.0 source at `86008ec377e7dddf94f33c1b36a6eb8395cd867e` was imported
verbatim in commit `334f840`. All 80 imported files were compared with that original source.
The original repository/build and both main branches are untouched by this work. The GitHub version
lives on `arena/d30b97e2-github-web-app-sandboxed`.

CI verifies that all **20 production Java files outside the site allowlist** retain the original
executable code after package/class/label substitutions, with one recorded exception: removal of
the inherited `; wv` user-agent masking, to respect your no-authentication-bypass requirement.

The WebView, CONNECT proxy, URL parser and roles, popup/auth window, request and service-worker
guards, TLS configuration, error overlays, back navigation, renderer recovery, refresh and watchdog
remain the original implementation. No dashboards, approval screens, extra settings, input hooks or
page CSS/JavaScript overrides were added. The opaque gstatic bundle and embedded-frame input fixes
remain intact.

## What actually passed

* **4,151 JVM cases** — zero failures/skips (includes parameterized URL-table assertions).
* **60 Android tests passed, zero failed, one skipped**, on API 34 / Pixel 5 / WebView 113.0.5672.136.
* Native GitHub email entry/editing, keyboard resizing, prompt-geometry checks and client-side submit
  validation with an empty password. Google’s **generic identifier form** also passed native entry,
  editing, resizing and overlap checks, without submission or authentication.
* In both form checks the WebView resized from **2,138 to 1,371 px**; typed and edited addresses had
  `placeholderShown=false` and `promptOverlaps=[]`. Screenshots are in the evidence artifact.
* Live GitHub HTML, loaded CSS and initialized site JavaScript; signed-out repository/file/commit,
  issue/PR and Actions pages; real login gates for file/workflow editing; the original guard tests.
* Signature/badging checks and installation/launch of the **actual release artifact**, not just debug.

## Important limits

The skipped test is the **actual GitHub → Google handoff**: GitHub did not offer its Google button
in this WebView (the control probe found none). The generic Google form diagnostic is **not** a
fallback, successful Google login or proof of GitHub OAuth. No identity masking or browser launch
was used to work around this.

No completed account login, 2FA/passkey flow, authenticated commit/issue/PR write, workflow edit/run,
private-repository access or live authenticated build-log stream was tested. Actions run links and
the completed-job page rendered, but no log rows were observed in the signed-out probe.
File pickers and unapproved external/app launches remain blocked as in Arena. Downloads are saved by
the app itself since 1.1 work: fetched through the same loopback egress guard and written with
`MediaStore` (Android 10+) or into the app's own Downloads folder (7-9) - no storage permission, no
platform download manager, no other app. See [README](README.md#downloads).

The keyboard test was timing-sensitive during development. The final test driver refreshes the
native editor connection and waits for the actual IME/resize; no app input implementation was changed,
and no failing assertion was removed. A physical-device/IME/font/rotation matrix remains untested.

## Full reports

* [Exact files changed](docs/CHANGES.md)
* [All 66 hosts, roles and the delta from Arena](docs/DOMAINS.md) — 2 PRODUCT, 1 SIGN_IN, 63 ASSET;
  no wildcards, and a resource host is not a navigation destination.
* [Source provenance and preservation checks](docs/PROVENANCE.md)
* [Detailed results, failures found and manual checks](TEST_REPORT.md)
* [Verbatim CI evidence and artifact links](docs/EVIDENCE.md)
* [Security boundary and inherited limitations](SECURITY.md)
