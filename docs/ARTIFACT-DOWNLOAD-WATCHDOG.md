# GitHub Actions artifact downloads and watchdog recovery

## Cause

The Actions results-storage hosts had been individually enumerated only through `productionresultssa19`. Newer endpoints were refused by the exact-host policy. Separately, the watchdog treated any non-document URL exposed by the WebView as an unauthorized page and recovered to `github.com/`, even when that URL was a resource-only artifact download redirect. The recovery target was always the homepage, not the page the user had been viewing.

## Change

`GitHubAllowlist` now contributes one anchored, case-insensitive resource-only host pattern:

```text
productionresultssa[0-9]+\.blob\.core\.windows\.net
```

The complete host must match. It accepts endpoints such as `sa0`, `sa19`, `sa30`, `sa49`, `sa100`, `sa199`, and `sa200`; it rejects suffix/prefix lookalikes, non-numeric endpoint names, and unrelated Azure Blob hosts. The pattern produces an ASSET entry: requests, embedded documents and port-443 tunnels remain policy-checked, while a main-frame document remains refused.

`NavigationStateTracker` tracks verified approved pages and active downloads. The guarded download listener increments the active-download count synchronously. While a download is active, a resource-only storage URL is treated as download traffic rather than page navigation and does not cause a watchdog reset or become the last verified page. Other unauthorized main-frame URLs remain blocked and recover to the most recent verified page. Saved, failed, cancelled and refused callbacks end the download state.

The existing downloader is unchanged: it uses EgressGuard's loopback proxy, disables automatic redirects, revalidates each hop, limits redirects to five and file size to 1 GiB, and saves through the existing MediaStore/app Downloads path. There is no external browser/intent or direct-network fallback.

## Verification status

Regression coverage was added for pattern matching, resource-vs-document policy, endpoint variants/lookalikes, and watchdog state across successful, failed, cancelled, and unauthorized navigation cases. The preservation fingerprint exceptions and the two added production files are declared in `upstream-core-fingerprints.json`.

GitHub Actions run [42919733147](https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/actions/runs/42919733147) completed successfully for commit `aaa1dffe669fa92867f9da8226e9b26d42d1731a`:

- JVM tests: **4,007 passed, 0 failed, 0 skipped**.
- Debug and release APKs: built; release APK identity, permissions, signing and checksums validated.
- Instrumented Android emulator tests: passed.
- Signed-out site observations: passed.

The first CI attempt exposed a faulty test assertion for a malformed `host:port` passed to the bare-host tunnel API. The assertion was corrected to test the malformed input directly; the successful run above includes the correction. This verifies compilation and automated policy/navigation regression coverage. It does not simulate a signed-in user's real workflow-run artifact download against a live Actions job.
