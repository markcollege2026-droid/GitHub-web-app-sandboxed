# Site re-check, 2026-11-14

**Question.** Has github.com, or any host attached to it, changed since the allow-list was reviewed on
2026-10-18 - and if so, does the app need a new host list and a new APK?

**Answer.** GitHub changed a little. The app did not need to: every host github.com names or requested
today is either already allowed or a refusal that was already on record. The allow-list is unchanged
(46 exact hosts and one anchored pattern). A fresh APK was built and tested in GitHub Actions from this
branch anyway, so there is a current, verified build.

All observation, building and testing below ran in GitHub Actions. Nothing was built or tested on a
local machine.

## What changed on the site (2026-10-18 to 2026-11-14)

Compared on `https://github.com/login`, the one page recorded in October.
Source: [run 43634419382](https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/actions/runs/43634419382),
full report kept verbatim in [SITE-DRIFT-2026-11-14-vs-2026-10-18.md](SITE-DRIFT-2026-11-14-vs-2026-10-18.md).

| Change | Hosts | What the app does | Needs a change? |
|---|---|---|---|
| 3 added | `productionresultssa30`, `31`, `32` `.blob.core.windows.net` (Actions artifact/log storage) | Already allowed by the host pattern added on 2026-11-10 | No |
| 6 added | `production-copilot-host-<region>.webpubsub.azure.com` (austriaeast, centralus, centralus-02, eastasia, westus, westus3) | Refused: Copilot is deliberately outside this app | No |
| 0 removed | - | - | No |

In the Android emulator (real WebView), the sub-resource refusals published by the run were the ones
the app has always refused on purpose: `collector.github.com` (analytics) and `play.google.com/log`
(Google sign-in logging). The rest of that list is look-alike hosts the tests inject deliberately.
The run publishes only the first lines of the lower-level tunnel log, so that log was not read in full;
earlier runs show one more refusal there, `content-autofill.googleapis.com`, which is the WebView's own
autofill service and not GitHub.

Seven more pages were recorded for the first time (start page, repository, file, issues, pull requests,
Actions, releases). They have no October snapshot, so they establish a baseline rather than a
comparison. Nothing on them was unreviewed.

One thing seen only in desktop Chrome: on `/login` the Google sign-in widget requested an image from
`lh3.googleusercontent.com` outside the `/a/` path the app allows. In the Android WebView GitHub does
not offer the Google button at all (`offered=false` in every emulator run), so this has no effect on
the app today. It is listed in the report each run so it is not forgotten.

## What changed in this repository

Nothing in the installed app changed: no file under `app/src/main` was touched.

* **`.github/workflows/site-drift.yml`, `scripts/site-drift.py`** - the check itself. Runs weekly (from
  `main`, so only once merged), on demand, and when the allow-list changes on a working branch. It fails if github.com names or requests a host
  that is neither allowed nor a recorded refusal. It never edits the allow-list. Started by hand with
  *refresh* ticked, it commits the new snapshot and report to the branch (refused on `main`).
* **`docs/site-reviewed-exclusions.json`** - the refusals, each with its reason, in one reviewable file.
* **`docs/observed-github-csp.txt`, `docs/SITE-DRIFT.md`** - replaced by Actions with today's
  observation (8 pages instead of 1).
* **`docs/DOMAINS.md`** - corrected. It still listed 20 numbered storage hosts and "66 exact hosts"; the
  code has enforced 46 exact hosts plus one pattern since 2026-11-10. `DomainsDocTest` now fails the
  build if the table and the code differ.
* **`scripts/site-observations.cjs`** - also records the app's start page, `https://github.com/`.
* **`EmailInputRegressionTest`, `LivePageSupport`** (test code only) - see below.

## The build

| | |
|---|---|
| Run | [Android CI 43634780295](https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/actions/runs/43634780295), all jobs passed |
| Source | `7338b0a54c16c977eefe034026a1c232b4011923` |
| JVM tests | 4,009 passed, 0 failed, 0 skipped |
| Emulator tests | 67 reported: 65 passed, 0 failed, 2 skipped |
| Release APK SHA-256 | `784b0657133aacef419c14f30b9ddea918ce4f46aa284aa410b0fd7107974019` |
| Install check | The exact signed release APK was installed and launched on the emulator; egress guard ready, github.com tunnel observed |
| Artifact | `github-webview-apk` on the run page (signed-in browser needed), kept until 2027-02-12 |

The two skips are the same two as in the earlier runs of this code: GitHub did not offer its Google
button in the WebView, and the download test for "the guard is not running", which skips itself when
the guard is already installed in the test process.

App identity is unchanged: `app.githubwebview.locked`, version 1.0.0 / code 1. The version was **not**
bumped, because the app's behaviour did not change in this work. Note that `main` has gained background
downloads since the `v1.0.0` release was published, still under the same version number; a new public
release would need a new tag and should carry a higher version code.

## The red run on `main`

The last run on `main` ([43125854812](https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/actions/runs/43125854812))
is red. It is not a site change. Two emulator tests failed with "tap must focus the real input", on the
GitHub form and on Google's form in the same run, while the identical commit passed on its branch the
day before and passed again today, unchanged, in
[run 43632529815](https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/actions/runs/43632529815).

The cause is not established. The failing run's screenshots are in its evidence artifact, which this
work could not open. What was done: when a tap does not focus the field, the test now records which
window was in front and takes a screenshot, and it retries only if that window was **not** this app
(for example an emulator "isn't responding" dialog). If the app's own window was in front, the test
still fails. In the three runs today the first tap worked every time, so the new path was not
exercised and the fix is unproven.

## Not checked

* Anything signed in: private repositories, live job logs, uploads, account challenges. A signed-out
  load cannot reach those, and they can use hosts that never appear in it.
* A real phone. The emulator is API 34 with WebView 113.
* The original Arena app this project was adapted from has moved from 1.2.0 to 1.3.1
  ([Arena.ai-apk](https://github.com/markcollege2026-droid/Arena.ai-apk/releases)). None of it was
  ported. 1.3.0 opens sub-resource traffic to any HTTPS host, which is the opposite of this app's
  exact-host rule, and 1.3.1 is specific to the Arena site. 1.2.1 contains a pull-to-refresh fix "for
  some devices" that could apply here; it touches a file this project keeps identical to the original,
  so it is a separate decision.
