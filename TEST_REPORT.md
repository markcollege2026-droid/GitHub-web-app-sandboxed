# GitHub adaptation — final test report

Verified **2026-10-18 UTC** against source **`258e549d889bbec62f1e6735a7e4a1ba939dd089`**.
[Android CI run 39170410407](https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/actions/runs/39170410407) concluded **success**. Unit tests, build, emulator tests
and signed-out browser observations passed. The tag-only release-publishing job was skipped as
expected; the APK is delivered as a **workflow artifact**, not a tagged release.

## Results

| Check | Actual outcome |
|---|---|
| Original source import | All 80 files in import `334f840` match Arena source `86008ec`; six archived historical reports remain verbatim |
| Preserved implementation | All 20 non-configuration production Java files match upstream executable tokens after recorded identifiers/labels and the sole UA-honesty exception |
| JVM suite | **4,151 passed; 0 failed; 0 skipped** |
| Android suite | **61 reported: 60 passed; 0 failed; 1 skipped** |
| Debug/release build | Both built; signed release passed badging, permissions/debug-flag and `apksigner verify` checks |
| Exact release installation | PASS: installed and launched the downloaded build-apk artifact; egress guard ready and GitHub tunnel observed |
| Browser observations | Separate signed-out Chrome observations completed; not substituted for Android/authentication results |

The JVM count includes the inherited parameterized URL-table assertions; it does **not** mean 4,151
independent end-user workflows. The suite includes seeded URL fuzzing, exact allowlist/role/path
checks, immutable config/back-navigation/metrics tests, real loopback CONNECT socket tests, manifest,
resource and source invariants. App ID is `app.githubwebview.locked`, version 1.0.0 / code 1.

Release SHA-256: `ab044ecc6ea2da7c76747c83fee35739e0de0324fd8eb93eb36d8f16c8aa1dd8`.
[APK artifact in run 39170410407](https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/actions/runs/39170410407) (GitHub requires a signed-in session for artifact downloads). [Device evidence](https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/actions/runs/39170410407/artifacts/12152014368).

## Actual Android environment

Android 14 / API 34, x86_64 Google APIs image, Pixel 5 profile, stock
`com.google.android.webview` **113.0.5672.136**. The emulator exposes a hardware-keyboard configuration;
software keyboard display was enabled for testing. The installed release is not debuggable.
A contemporary WebView is recommended on real devices; this is evidence for the specific tested
provider/version, not every Android/WebView combination.

## Live input and rendering results

### GitHub login — passed

The real `https://github.com/login` form and its JavaScript initialized. Test-only native touch and
input-connection operations typed `probe@example.invalid`, selected/edited it to
`edited@example.invalid`, and observed the form's actual value. The software keyboard appeared;
WebView height changed **2,138 → 1,371 px**. Both snapshots reported:

* value lengths 21 / 22, respectively;
* font size 16, input height 40;
* `placeholderShown=false`;
* `promptOverlaps=[]`.

A native click on Sign in with the **password intentionally empty** triggered required-field client
validation and kept the page on GitHub login. This did not submit a password or authenticate.
The test observes the real form; it does not rewrite field values with JavaScript or patch its CSS.

### Google's generic identifier form — passed, **not OAuth/login success**

Google served its generic identifier form to the genuine WebView UA. The same native entry/editing
and keyboard/geometry checks ran there, with height **2,138 → 1,371 px**, input height 52, font size 16,
value lengths 21 / 22, `placeholderShown=false`, and `promptOverlaps=[]`.
**No Next click, credential submission or authentication was performed in this diagnostic.** It is
not an app fallback for GitHub's unavailable social-login flow. It specifically tests the field/rendering
regression while preserving the gstatic and SUBDOCUMENT fixes from Arena.

### Actual GitHub Google handoff — skipped, not passed

`EmailInputRegressionTest.googleIdentifierInputAndValidationOnlyWhenProviderServesTheForm` was
skipped because GitHub offered no Google control in this WebView. The normalized control probe
reported `controls=[]`; the real UA retained `; wv`. The separate desktop Chrome observation did
offer the button and followed it to `accounts.google.com/v3/signin/identifier`. Those are different
browser contexts. Neither observation establishes a completed Google/GitHub sign-in.

No UA disguise, custom browser identity, external browser/app fallback, CAPTCHA solving or provider
restriction bypass was used. Real credentials are not requested, stored or included in the tests.

## Site and guard coverage

| Area | Performed | Boundary |
|---|---|---|
| Home | Real GitHub HTML, loaded CDN stylesheet objects and initialized GitHub custom elements | Not just a blank page evaluating `1+1` |
| Repository, file, commits, issues, PR | Public pages rendered with styles inside the real app | Read-only/signed-out; no mutations |
| Actions | Workflow-run list rendered with **25 run links**; completed-job page rendered | **0 log rows observed** signed-out; live/authenticated log streaming not verified |
| File/workflow edit links | Both reached the real GitHub login gate without a browser escape | No authenticated edit or commit |
| Signup/CAPTCHA | Real signup challenge resources inspected; no configured challenge host/path was refused | No challenge solved and no account created |
| URL roles | Real Android callbacks driven with synthetic resource/frame/navigation/redirect/popup URLs | ASSET permission never grants a top-level page or auth window |
| Egress | Real CONNECT sockets and a real WebSocket attempt to a refused host; all-process routing checked | Host/port boundary, not encrypted content/path inspection |
| Escapes, back, recovery, settings | Original device guard tests retained and retargeted | Not a proof against every browser transport or OS behavior |
| Signed release | Actual artifact installed, launched, guard-ready and GitHub connection confirmed | Detailed instrumented interactions run on debug, same application code; release debugging stays off |

The native form driver and DOM observations exist only in `androidTest`, not the APK. Screenshots
were captured to the evidence artifact. No manual visual or physical-phone inspection is claimed.
`resourceHosts` probe fields describe attempted resources, not necessarily successful fetches:
telemetry such as `collector.github.com` and `play.google.com/log` remained refused.

## Failures found, corrected and retained in history

| Run / source | Actual result | What changed afterward |
|---|---|---|
| [39164490059](https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/actions/runs/39164490059) / `22a3168` | Build/JVM passed; device 54 pass, 1 fail | Retargeted test still used an Arena Turnstile path on an Arkose host; corrected the test, not the allowlist to accept a wrong path |
| [39164988590](https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/actions/runs/39164988590) / `4d54812` | 4,015 JVM pass; device 54 pass, 7 fail | Five screenshot-helper failures (`UiAutomation` does not interpret `&&`), one real DataDome `/i.js` path gap, plus the stale test |
| [39165580281](https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/actions/runs/39165580281) / `531d1cf` | 4,063 JVM pass; device 58 pass, 2 fail, 1 skip; release installed | Versioned DataDome root bundle still blocked; mobile Actions assertion relied on a hidden repository-name label |
| [39166545809](https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/actions/runs/39166545809) / `38d561d` | 4,103 JVM pass; device 59 pass, 1 fail, 1 skip | Native test touch injection was self-UID targeted while the IME changed windows; switched test touch dispatch and viewport coordinates |
| [39167360418](https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/actions/runs/39167360418) / `dd9019a` | Build/JVM passed; device 59 pass, 1 fail, 1 skip | GitHub software-keyboard visibility assertion failed; kept it strict and investigated test-side IME binding |
| [39167622790](https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/actions/runs/39167622790) / `c9a4547` | Whole run succeeded; device 60 pass, 0 fail, 1 skip | First complete passing run, including both native input diagnostics; further checks addressed repeatability and Google's observed connection-check iframe |
| `c8257eb` / run 39168234333 | Pending run cancelled by workflow concurrency before execution | No tests claimed for this cancelled run |
| [39168377245](https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/actions/runs/39168377245) / `105ca07` | 4,151 JVM pass; device 59 pass, 1 fail, 1 skip; release installed | GitHub IME visibility remained timing-sensitive; generic Google input passed |
| [39169606407](https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/actions/runs/39169606407) / `92ee140` | 4,151 JVM pass; device 59 pass, 1 fail, 1 skip; release installed | Explicit normal IME request alone was insufficient; final driver refreshes the native editor connection and gives its handshake a UI turn |
| [39170410407](https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/actions/runs/39170410407) / `258e549` | **4,151 JVM pass; device 60 pass, 0 fail, 1 skip; exact release installed** | Delivered artifact |
| [39405364177](https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/actions/runs/39405364177) / `58b224d` | Preservation check, JVM suite, APK build and device suite all passed (all four jobs green) | Refusal-message change ([diagnosis](docs/DIAGNOSIS-help-downloads.md)); counts are in that run's step summary |
| [39417461337](https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/actions/runs/39417461337) / `bc2056b` | **All four jobs green** (4,169 JVM cases; device 61 passed, 0 failed, 2 skipped) | Adds the save-offer for an inline file link; downloads and refusals unchanged. Two Android CI runs were created for this commit: this one passed, while the sibling run `39417453572` had the same two live-provider failures as the earlier `39168377245`/`39169606407` runs ("tap must focus the real input", IME timing) - none of the download tests failed in either run. |
| [39415422258](https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/actions/runs/39415422258) / `a0dd313` | **4,169 JVM pass, 0 failed**; device **61 pass, 0 fail, 2 skip**; all four jobs green | Downloads saved in-app. The two skips are provider/guard-availability assumptions (`DownloadGuardTest.anApprovedLinkFailsClosedWhenTheGuardIsNotRunning` and one Google-form case). `FileDownloadTest` passed: a real transfer through the loopback guard and a real `MediaStore` row, deleted afterwards. The sibling push run for the same commit (`39415430957`) had one emulator-job flake in the live provider tests, the timing sensitivity already documented above. |

The app's input/rendering code was **not** changed to address test-driver timing. No failing test
was deleted or relabeled as a skip. The final driver uses native touch/IME operations, not forced
keyboard flags or production CSS/JS overrides. Passing this run does not establish an exhaustive
stability, device, language or IME matrix.

The final policy also explicitly scopes Google's observed
`accounts.youtube.com/accounts/CheckConnection` as ASSET-only. YouTube navigation remains blocked.
DataDome's dedicated versioned-script host is path-unrestricted ASSET, analogous to the preserved
gstatic exception, rather than repeatedly pinning changing filenames. See [domains/roles](docs/DOMAINS.md).

## Not performed / manual acceptance checklist

* [ ] Install on a physical phone alongside the original Arena app and confirm both remain usable.
* [ ] Complete GitHub login/2FA with your own account; check session persistence. Never share credentials
  in chat. Google/passkey/security-key/enterprise-provider support is not established by this run.
* [ ] Repeat email editing, validation, font-size/language/IME changes and rotation on the target device.
* [ ] Browse private repositories; edit/save/commit a file and a workflow; create/update issues and PRs.
* [ ] Run a workflow and inspect authenticated running/completed logs, streaming and backscroll.
* [ ] Confirm the expected blocked-link behavior for external destinations and Raw/archive navigation.

Downloads, file pickers, unapproved external destinations and app/browser launches remain deliberately
disabled, not pending features. Unknown regional Actions transports fail closed; no wildcard
`*.actions.githubusercontent.com`, S3, Azure or Google allowance was added.

## Local checks versus Actions

This editing sandbox cannot reach Android/Gradle artifact servers. Local checks were resource XML,
JavaScript/shell syntax, Git diffs, the complete upstream import comparison and preserved-core
fingerprints. Compilation, JVM tests, APK signature/badging, installation and emulator tests actually
ran in GitHub Actions. No historical Arena pass count is reused as GitHub verification.
