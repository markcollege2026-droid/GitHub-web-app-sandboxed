# Exact changed-file inventory

Comparison base: the verbatim Arena source import `334f840`, corresponding to
`markcollege2026-droid/Arena.ai-apk@86008ec377e7dddf94f33c1b36a6eb8395cd867e`.
This table follows explicit package/class renames rather than relying on Git similarity heuristics.
Every old production source has a named counterpart. The 20-file executable-code comparison is
independently pinned by `upstream-core-fingerprints.json` and verified by CI.

Imported code is still unchanged except for the recorded exceptions: site policy/branding/build
identity, the UA-honesty change, and the reviewed behavior changes listed below. Production additions
are pinned in `upstream-core-fingerprints.json#addedProductionFiles`. No new Activity, dashboard,
settings, approval flow, WebView implementation, input handler or proxy was introduced; downloads
reuse the existing proxy rather than adding a network stack of their own.

## Post-import review: downloads (2026-10-20)

Requested: make downloads work. They do now, without weakening the rest of the wrapper.

| File | Scope |
|---|---|
| `app/src/main/java/app/githubwebview/locked/net/DownloadPlan.java` | **New (reviewed addition).** Pure rules: `https` only, allow-listed host via `evaluateRequest`, 5-hop redirect budget, 1 GiB ceiling, hostile-input file naming (traversal, CR/LF, reserved characters, overlong names, MIME-derived extensions). No Android imports, so it is exhaustively JVM-tested. |
| `app/src/main/java/app/githubwebview/locked/net/FileDownloads.java` | **New (reviewed addition).** Opens every hop through the existing loopback CONNECT proxy (`EgressGuard.port()`; no direct branch), re-validates each redirect, sends the WebView's cookies only to allow-listed hosts, saves via `MediaStore.Downloads` on Android 10+ or the app's own external Downloads folder on 7-9, deletes partial files, reports progress/cancel/save/failure on the main thread. No storage permission, no platform download manager, no other app. |
| `app/src/main/java/app/githubwebview/locked/web/WebViewHardening.java` | **Recorded exception.** `BlockingDownloadListener` → `GuardedDownloadListener`, which forwards to `FileDownloads`; the WebView factory takes the downloader. Every other hardening switch is untouched. |
| `app/src/main/java/app/githubwebview/locked/MainActivity.java` | **Recorded exception (extended).** Owns the single `FileDownloads`, shows an in-app progress dialog with Cancel, and reports saved/failed/cancelled/refused natively; `onLoadError` reports a refused destination as a refusal. |
| `app/src/androidTest/.../web/DownloadGuardTest.java` | Rewritten: unapproved links are still refused synchronously with no network, no file and no hand-off; an approved link with no guard fails closed. |
| `app/src/androidTest/.../net/FileDownloadTest.java` | **New.** Live end-to-end: launches the activity, waits for the guard, downloads a file from `raw.githubusercontent.com`, asserts the row appears in `MediaStore` with bytes, then deletes it. |
| `app/src/test/.../net/DownloadPlanTest.java` | **New.** JVM cases for every hostile name, scheme, host, size boundary, MIME shape and redirect code. |
| `app/src/test/.../SourceInvariantsTest.java` | `downloadsAreRefused` → `downloadsAreSavedByTheAppAndNeverHandedOn`. |

## Post-import review: refusal messages (2026-10-20)

Reported symptom: "Help" and "Downloads" do nothing. Diagnosis and evidence are in
[DIAGNOSIS-help-downloads.md](DIAGNOSIS-help-downloads.md). The refusals themselves are correct and stay
in place; what changed is that they now say so.

| File | Scope |
|---|---|
| `app/src/main/java/app/githubwebview/locked/MainActivity.java` | **Recorded behavioural exception** (see `reviewedException` in `upstream-core-fingerprints.json`): `onLoadError` asks the policy whether the destination was actually refused and, if so, shows the existing native overlay with the refused host and reason instead of reporting a github.com outage; `onDownloadBlocked` shows that same overlay ("downloads are disabled in this app, so nothing was saved") instead of only a toast. No allow-list entry, verdict, permission, capability or `loadUrl` path changes. |
| `app/src/main/res/values/strings.xml` | Error-overlay title no longer claims github.com; overlay footer lists Help/Docs/Support/Status/Blog and downloads as separate, refused destinations. |
| `scripts/verify-preserved-core.py` | Verifies `reviewedException` rows instead of skipping them: rationale required, hash must match, added tokens must still be present and replaced tokens must stay gone; a stale exception also fails. |
| `app/src/test/.../SourceInvariantsTest.java` | New JVM invariant `refusalsAreReportedAsRefusalsNotAsOutages`. |
| `docs/upstream-core-fingerprints.json` | Records the exception and its rationale. |
| `README.md`, `DELIVERY.md`, `TEST_REPORT.md`, `docs/EVIDENCE.md`, `docs/RELEASE_NOTES.md` | Replace the direct artifact URL (404 when fetched signed-out) with the run page plus the `v*` tag → Release path for a sign-in-free download. |

## Original files changed or renamed

| Original path | GitHub path (same path if unchanged name) | Scope |
|---|---|---|
| `.github/workflows/android.yml` | `.github/workflows/android.yml` | Original workflow plus GitHub artifact/package names, preservation checks, precise results/evidence, passive host observation, exact release install verification; docs-only pushes ignored |
| `.gitignore` | `.gitignore` | Ignore generated observation/device/Python outputs |
| `README.md` | `README.md` | Current GitHub report; original retained verbatim under docs/upstream-arena/ |
| `REVIEW.md` | `REVIEW.md` | Current GitHub report; original retained verbatim under docs/upstream-arena/ |
| `SECURITY.md` | `SECURITY.md` | Current GitHub report; original retained verbatim under docs/upstream-arena/ |
| `TEST_REPORT.md` | `TEST_REPORT.md` | Current GitHub report; original retained verbatim under docs/upstream-arena/ |
| `app/build.gradle` | `app/build.gradle` | Namespace/applicationId and version only; SDKs, dependencies, signing and other settings unchanged |
| `app/src/androidTest/java/app/arenawebview/locked/AppSurfaceTest.java` | `app/src/androidTest/java/app/githubwebview/locked/AppSurfaceTest.java` | Retarget tests to GitHub and preserve security regression coverage |
| `app/src/androidTest/java/app/arenawebview/locked/AuthWindowTest.java` | `app/src/androidTest/java/app/githubwebview/locked/AuthWindowTest.java` | Retarget tests to GitHub and preserve security regression coverage |
| `app/src/androidTest/java/app/arenawebview/locked/BackNavigationTest.java` | `app/src/androidTest/java/app/githubwebview/locked/BackNavigationTest.java` | Retarget tests to GitHub and preserve security regression coverage |
| `app/src/androidTest/java/app/arenawebview/locked/EgressGuardTest.java` | `app/src/androidTest/java/app/githubwebview/locked/EgressGuardTest.java` | Retarget tests to GitHub and preserve security regression coverage |
| `app/src/androidTest/java/app/arenawebview/locked/EscapeAttemptTest.java` | `app/src/androidTest/java/app/githubwebview/locked/EscapeAttemptTest.java` | Retarget tests to GitHub and preserve security regression coverage |
| `app/src/androidTest/java/app/arenawebview/locked/GoogleSignInLiveTest.java` | `app/src/androidTest/java/app/githubwebview/locked/GoogleSignInLiveTest.java` | Retarget tests to GitHub and preserve security regression coverage; time-scoped observations and explicit unavailable-page skips |
| `app/src/androidTest/java/app/arenawebview/locked/LiveArenaSmokeTest.java` | `app/src/androidTest/java/app/githubwebview/locked/LiveGitHubSmokeTest.java` | Replace weak 1+1 smoke assertion with actual GitHub CSS/site-JS initialization checks |
| `app/src/androidTest/java/app/arenawebview/locked/WatchdogRecoveryTest.java` | `app/src/androidTest/java/app/githubwebview/locked/WatchdogRecoveryTest.java` | Retarget tests to GitHub and preserve security regression coverage |
| `app/src/androidTest/java/app/arenawebview/locked/WebViewSettingsTest.java` | `app/src/androidTest/java/app/githubwebview/locked/WebViewSettingsTest.java` | Retarget tests to GitHub and preserve security regression coverage |
| `app/src/androidTest/java/app/arenawebview/locked/web/DownloadGuardTest.java` | `app/src/androidTest/java/app/githubwebview/locked/web/DownloadGuardTest.java` | Retarget tests to GitHub and preserve security regression coverage |
| `app/src/androidTest/java/app/arenawebview/locked/web/FakeWebResourceRequest.java` | `app/src/androidTest/java/app/githubwebview/locked/web/FakeWebResourceRequest.java` | Retarget tests to GitHub and preserve security regression coverage |
| `app/src/androidTest/java/app/arenawebview/locked/web/NavigationGuardTest.java` | `app/src/androidTest/java/app/githubwebview/locked/web/NavigationGuardTest.java` | Retarget tests to GitHub and preserve security regression coverage |
| `app/src/androidTest/java/app/arenawebview/locked/web/PopupBlockingTest.java` | `app/src/androidTest/java/app/githubwebview/locked/web/PopupBlockingTest.java` | Retarget tests to GitHub and preserve security regression coverage |
| `app/src/androidTest/java/app/arenawebview/locked/web/RecordingGuardHost.java` | `app/src/androidTest/java/app/githubwebview/locked/web/RecordingGuardHost.java` | Retarget tests to GitHub and preserve security regression coverage |
| `app/src/main/AndroidManifest.xml` | `app/src/main/AndroidManifest.xml` | Theme identifier only; permissions, components, hardware acceleration and adjustResize unchanged |
| `app/src/main/java/app/arenawebview/locked/MainActivity.java` | `app/src/main/java/app/githubwebview/locked/MainActivity.java` | Mechanical package/class/branding changes; original executable logic preserved |
| `app/src/main/java/app/arenawebview/locked/net/EgressProxy.java` | `app/src/main/java/app/githubwebview/locked/net/EgressProxy.java` | Mechanical package/class/branding changes; original executable logic preserved |
| `app/src/main/java/app/arenawebview/locked/policy/AllowEntry.java` | `app/src/main/java/app/githubwebview/locked/policy/AllowEntry.java` | Mechanical package/class/branding changes; original executable logic preserved |
| `app/src/main/java/app/arenawebview/locked/policy/ArenaAllowlist.java` | `app/src/main/java/app/githubwebview/locked/policy/GitHubAllowlist.java` | Site URL/role/host configuration |
| `app/src/main/java/app/arenawebview/locked/policy/BackNavigator.java` | `app/src/main/java/app/githubwebview/locked/policy/BackNavigator.java` | Mechanical package/class/branding changes; original executable logic preserved |
| `app/src/main/java/app/arenawebview/locked/policy/BlockReason.java` | `app/src/main/java/app/githubwebview/locked/policy/BlockReason.java` | Mechanical package/class/branding changes; original executable logic preserved |
| `app/src/main/java/app/arenawebview/locked/policy/PolicyConfig.java` | `app/src/main/java/app/githubwebview/locked/policy/PolicyConfig.java` | Mechanical package/class/branding changes; original executable logic preserved |
| `app/src/main/java/app/arenawebview/locked/policy/PolicyMetrics.java` | `app/src/main/java/app/githubwebview/locked/policy/PolicyMetrics.java` | Mechanical package/class/branding changes; original executable logic preserved |
| `app/src/main/java/app/arenawebview/locked/policy/PolicyResult.java` | `app/src/main/java/app/githubwebview/locked/policy/PolicyResult.java` | Mechanical package/class/branding changes; original executable logic preserved |
| `app/src/main/java/app/arenawebview/locked/policy/UrlPolicy.java` | `app/src/main/java/app/githubwebview/locked/policy/UrlPolicy.java` | Mechanical package/class/branding changes; original executable logic preserved |
| `app/src/main/java/app/arenawebview/locked/ui/AuthWindow.java` | `app/src/main/java/app/githubwebview/locked/ui/AuthWindow.java` | Mechanical package/class/branding changes; original executable logic preserved |
| `app/src/main/java/app/arenawebview/locked/ui/PullToRefreshFrame.java` | `app/src/main/java/app/githubwebview/locked/ui/PullToRefreshFrame.java` | Mechanical package/class/branding changes; original executable logic preserved |
| `app/src/main/java/app/arenawebview/locked/ui/StatusOverlay.java` | `app/src/main/java/app/githubwebview/locked/ui/StatusOverlay.java` | Mechanical package/class/branding changes; original executable logic preserved |
| `app/src/main/java/app/arenawebview/locked/web/ArenaWebChromeClient.java` | `app/src/main/java/app/githubwebview/locked/web/GitHubWebChromeClient.java` | Mechanical package/class/branding changes; original executable logic preserved |
| `app/src/main/java/app/arenawebview/locked/web/ArenaWebViewClient.java` | `app/src/main/java/app/githubwebview/locked/web/GitHubWebViewClient.java` | Mechanical package/class/branding changes; original executable logic preserved |
| `app/src/main/java/app/arenawebview/locked/web/BaseGuardHost.java` | `app/src/main/java/app/githubwebview/locked/web/BaseGuardHost.java` | Mechanical package/class/branding changes; original executable logic preserved |
| `app/src/main/java/app/arenawebview/locked/web/BlockedResponses.java` | `app/src/main/java/app/githubwebview/locked/web/BlockedResponses.java` | Mechanical package/class/branding changes; original executable logic preserved |
| `app/src/main/java/app/arenawebview/locked/web/EgressGuard.java` | `app/src/main/java/app/githubwebview/locked/web/EgressGuard.java` | Mechanical package/class/branding changes; original executable logic preserved |
| `app/src/main/java/app/arenawebview/locked/web/GuardHost.java` | `app/src/main/java/app/githubwebview/locked/web/GuardHost.java` | Mechanical package/class/branding changes; original executable logic preserved |
| `app/src/main/java/app/arenawebview/locked/web/PopupRouterClient.java` | `app/src/main/java/app/githubwebview/locked/web/PopupRouterClient.java` | Mechanical package/class/branding changes; original executable logic preserved |
| `app/src/main/java/app/arenawebview/locked/web/WebViewHardening.java` | `app/src/main/java/app/githubwebview/locked/web/WebViewHardening.java` | Mechanical package/branding rename; remove UA masking only |
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | `app/src/main/res/drawable/ic_launcher_foreground.xml` | App-specific label/theme ID/icon monogram or page-host configuration; no new UI |
| `app/src/main/res/mipmap-anydpi/ic_launcher.xml` | `app/src/main/res/mipmap-anydpi/ic_launcher.xml` | App-specific label/theme ID/icon monogram or page-host configuration; no new UI |
| `app/src/main/res/values/ids.xml` | `app/src/main/res/values/ids.xml` | App-specific label/theme ID/icon monogram or page-host configuration; no new UI |
| `app/src/main/res/values/strings.xml` | `app/src/main/res/values/strings.xml` | App-specific label/theme ID/icon monogram or page-host configuration; no new UI |
| `app/src/main/res/values/themes.xml` | `app/src/main/res/values/themes.xml` | App-specific label/theme ID/icon monogram or page-host configuration; no new UI |
| `app/src/main/res/xml/network_security_config.xml` | `app/src/main/res/xml/network_security_config.xml` | App-specific label/theme ID/icon monogram or page-host configuration; no new UI |
| `app/src/test/java/app/arenawebview/locked/ManifestPolicyTest.java` | `app/src/test/java/app/githubwebview/locked/ManifestPolicyTest.java` | Retarget tests to GitHub and preserve security regression coverage |
| `app/src/test/java/app/arenawebview/locked/ResourcePolicyTest.java` | `app/src/test/java/app/githubwebview/locked/ResourcePolicyTest.java` | Retarget tests to GitHub and preserve security regression coverage |
| `app/src/test/java/app/arenawebview/locked/SourceInvariantsTest.java` | `app/src/test/java/app/githubwebview/locked/SourceInvariantsTest.java` | Retarget tests to GitHub and preserve security regression coverage |
| `app/src/test/java/app/arenawebview/locked/TestPaths.java` | `app/src/test/java/app/githubwebview/locked/TestPaths.java` | Retarget tests to GitHub and preserve security regression coverage |
| `app/src/test/java/app/arenawebview/locked/net/EgressProxyTest.java` | `app/src/test/java/app/githubwebview/locked/net/EgressProxyTest.java` | Retarget tests to GitHub and preserve security regression coverage |
| `app/src/test/java/app/arenawebview/locked/policy/AllowlistRegressionTest.java` | `app/src/test/java/app/githubwebview/locked/policy/AllowlistRegressionTest.java` | Retarget tests to GitHub and preserve security regression coverage |
| `app/src/test/java/app/arenawebview/locked/policy/BackNavigatorTest.java` | `app/src/test/java/app/githubwebview/locked/policy/BackNavigatorTest.java` | Retarget tests to GitHub and preserve security regression coverage |
| `app/src/test/java/app/arenawebview/locked/policy/PolicyCases.java` | `app/src/test/java/app/githubwebview/locked/policy/PolicyCases.java` | Retarget tests to GitHub and preserve security regression coverage |
| `app/src/test/java/app/arenawebview/locked/policy/PolicyConfigTest.java` | `app/src/test/java/app/githubwebview/locked/policy/PolicyConfigTest.java` | Retarget tests to GitHub and preserve security regression coverage |
| `app/src/test/java/app/arenawebview/locked/policy/PolicyMetricsTest.java` | `app/src/test/java/app/githubwebview/locked/policy/PolicyMetricsTest.java` | Retarget tests to GitHub and preserve security regression coverage |
| `app/src/test/java/app/arenawebview/locked/policy/PolicyPurityTest.java` | `app/src/test/java/app/githubwebview/locked/policy/PolicyPurityTest.java` | Retarget tests to GitHub and preserve security regression coverage |
| `app/src/test/java/app/arenawebview/locked/policy/TunnelPolicyTest.java` | `app/src/test/java/app/githubwebview/locked/policy/TunnelPolicyTest.java` | Retarget tests to GitHub and preserve security regression coverage |
| `app/src/test/java/app/arenawebview/locked/policy/UrlPolicyFuzzTest.java` | `app/src/test/java/app/githubwebview/locked/policy/UrlPolicyFuzzTest.java` | Retarget tests to GitHub and preserve security regression coverage |
| `app/src/test/java/app/arenawebview/locked/policy/UrlPolicyTest.java` | `app/src/test/java/app/githubwebview/locked/policy/UrlPolicyTest.java` | Retarget tests to GitHub and preserve security regression coverage |
| `docs/EVIDENCE.md` | `docs/EVIDENCE.md` | Current GitHub report; original retained verbatim under docs/upstream-arena/ |
| `docs/RELEASE_NOTES.md` | `docs/RELEASE_NOTES.md` | Current GitHub report; original retained verbatim under docs/upstream-arena/ |
| `settings.gradle` | `settings.gradle` | Project name only |

## Added files

* `DELIVERY.md` — GitHub handover, policy/provenance/change report or public source evidence.
* `app/src/androidTest/java/app/githubwebview/locked/EmailInputRegressionTest.java` — Additional Android device test/helper; not packaged in the app.
* `app/src/androidTest/java/app/githubwebview/locked/LiveGitHubWorkflowTest.java` — Additional Android device test/helper; not packaged in the app.
* `app/src/androidTest/java/app/githubwebview/locked/LivePageSupport.java` — Additional Android device test/helper; not packaged in the app.
* `app/src/androidTest/java/app/githubwebview/locked/web/GitHubResourceRolesTest.java` — Additional Android device test/helper; not packaged in the app.
* `docs/CHANGES.md` — GitHub handover, policy/provenance/change report or public source evidence.
* `docs/DOMAINS.md` — GitHub handover, policy/provenance/change report or public source evidence.
* `docs/PROVENANCE.md` — GitHub handover, policy/provenance/change report or public source evidence.
* `docs/observed-github-csp.txt` — GitHub handover, policy/provenance/change report or public source evidence.
* `docs/upstream-arena/EVIDENCE.md` — Verbatim historical Arena document.
* `docs/upstream-arena/NOTICE.md` — Marks archived results as historical Arena evidence, not current GitHub results.
* `docs/upstream-arena/README.md` — Verbatim historical Arena document.
* `docs/upstream-arena/RELEASE_NOTES.md` — Verbatim historical Arena document.
* `docs/upstream-arena/REVIEW.md` — Verbatim historical Arena document.
* `docs/upstream-arena/SECURITY.md` — Verbatim historical Arena document.
* `docs/upstream-arena/TEST_REPORT.md` — Verbatim historical Arena document.
* `docs/upstream-core-fingerprints.json` — Upstream-derived code fingerprints and exact normalization/exception record.
* `scripts/site-observations.cjs` — CI/audit/test-evidence tooling; not packaged in the app.
* `scripts/summarize-tests.py` — CI/audit/test-evidence tooling; not packaged in the app.
* `scripts/verify-preserved-core.py` — CI/audit/test-evidence tooling; not packaged in the app.
* `scripts/verify-release-on-device.sh` — CI/audit/test-evidence tooling; not packaged in the app.

## Original files kept byte-for-byte

* `app/src/main/res/drawable/overlay_card.xml`
* `app/src/main/res/drawable/overlay_scrim.xml`
* `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
* `app/src/main/res/values/colors.xml`
* `app/src/main/res/values/ic_launcher_background.xml`
* `build.gradle`
* `gradle.properties`
* `gradle/wrapper/gradle-wrapper.jar`
* `gradle/wrapper/gradle-wrapper.jar.sha256`
* `gradle/wrapper/gradle-wrapper.properties`
* `gradlew`
* `gradlew.bat`
* `keystore/README.md`
* `keystore/arena-webview-demo.p12`

## Reproduce the review

```sh
git diff --find-renames=40% 334f840 -- app .github settings.gradle build.gradle gradle.properties
python3 scripts/verify-preserved-core.py
```

The historical source tree and complete adaptation diff remain in Git. Generated APKs, screenshots,
JUnit XML and full browser/device logs are workflow artifacts, not committed binaries or datasets.
