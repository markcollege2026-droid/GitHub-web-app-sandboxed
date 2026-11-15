# Adaptation review

This is a site-configuration adaptation of the imported Arena source, not a replacement implementation
or an independent third-party security audit. See:

* `docs/PROVENANCE.md` — pinned source/build lineage and the 20-file executable-token preservation check.
* `docs/CHANGES.md` — complete old/new file inventory, including files retained byte-for-byte.
* `docs/DOMAINS.md` — all 66 exact hosts, roles, path scopes, observations and the delta from Arena.
* `TEST_REPORT.md` and `docs/EVIDENCE.md` — actual CI outcomes, including earlier failures and the one
  unavailable Google handoff, not unqualified claims that authentication or every workflow works.
* `DELIVERY.md` — the exact tested signed APK artifact and its checksum.

The sole behavioral exception outside the site allowlist is removal of UA identity masking to respect
provider restrictions. The original review is preserved verbatim at `docs/upstream-arena/REVIEW.md`.
Its browser/proxy/shared-host limitations remain. No production input override, new native feature,
extra component, credential flow or UI screen was introduced. The existing public demo signing key
remains a deployment limitation; see `SECURITY.md` and `keystore/README.md`.
