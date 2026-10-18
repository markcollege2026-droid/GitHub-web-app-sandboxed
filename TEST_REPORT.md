# Test report — GitHub WebView (Locked)

## Status for this checkout

**No tests or Android build have completed in this environment. No APK has been produced here.** The Gradle command was attempted and stopped before Gradle started because this workspace has no Java runtime/JDK:

```text
./gradlew --no-daemon :app:testDebugUnitTest --stacktrace
ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
```

`command -v java` returned no executable and `/usr/lib/jvm` was empty. Therefore this report does not claim that compilation, JVM tests, instrumented tests, APK packaging, signing, or live GitHub behavior passed. The GitHub Actions workflow has been adapted to run those checks and publish APK artifacts; its result should be added here after a workflow run completes.

| Check | Intended execution | Result in this checkout |
| --- | --- | --- |
| JVM policy, allow-list, manifest, resource, and source-invariant tests | `:app:testDebugUnitTest` | **Not run** — no JDK; Gradle did not start |
| Egress proxy tests using local loopback sockets | JVM test task | **Not run** |
| Debug and release APK build; package/badging/signature checks | `:app:assembleDebug :app:assembleRelease` and workflow verification | **Not run** — no JDK; no APK exists |
| Instrumented WebView/security tests | API 34 emulator, `:app:connectedDebugAndroidTest` | **Not run** |
| Live GitHub homepage/login and DOM smoke probes | Instrumented tests with network | **Not run** |
| Login input/IME/keyboard interaction | Physical device or emulator with visible IME | **Not run** |
| Credential submission, 2FA/passkey, or organization SSO | Real user account | **Not attempted** |

## What the automated tests are intended to cover

- The shared URL case table checks GitHub `PRODUCT` page behavior separately from `ASSET` resources and documents; it checks exact hosts, API/static-asset path prefixes, redirects, userinfo, ports, custom schemes, look-alike domains, and inert browser-generated URLs.
- The seeded policy fuzz test runs 40,000 mutated URLs across the traffic classes and checks fail-closed and determinism invariants.
- `AllowlistRegressionTest` pins the exact production hosts and roles, asserts `github.com` is the only page-capable host, checks the API/static path scopes, and confirms external identity providers are not enabled.
- `EgressProxyTest` exercises the `CONNECT` guard using loopback sockets. Instrumented tests exercise the Android WebView hooks, popup routing, downloads, activity launches, the egress guard, back behavior, and renderer recovery.
- `GitHubLoginLiveTest` is a best-effort DOM smoke test. If it runs online it checks for GitHub's login/password fields and same-origin form target but does **not** fill or submit credentials. `LiveGitHubSmokeTest` checks the real GitHub page and JavaScript only if the emulator can reach it.
- Static manifest/source tests pin `adjustResize` in the manifest and activity code, preserve Android WebView's default user-agent, and check that no JavaScript or local HTML is injected to change GitHub input behavior. Those checks do not replace a real keyboard/IME test.

## Important limitations to preserve in future reports

- Do not describe an offline-tolerant live test as proof of successful authentication. If a page was not reached, report that explicitly.
- The live login probe does not authenticate, enter a password, or test an external identity-provider hand-off.
- No Google, SAML, passkey, CAPTCHA, upload/download, or keyboard behavior is claimed as working without an actual applicable test.
- A GitHub request to an unlisted host/path is intentionally refused. A regression report should include the refused host and path, and any proposed new entry must be reviewed for exact role and scope rather than widened by wildcard.
- The default CI artifact is the installable, debug-signed APK. The release APK is unsigned unless all release-signing secrets are configured. No Arena demo signing key is used or copied.
