package app.arenawebview.locked.policy;

import java.util.Locale;

/**
 * Outcome of evaluating one URL against the {@link PolicyConfig}.
 *
 * <p>Immutable value object; contains no Android dependencies.</p>
 */
public final class PolicyResult {

    private final boolean allowed;
    private final BlockReason reason;
    private final String canonicalHost;
    private final String rawUrl;
    private final String detail;
    private final AllowEntry matchedEntry;

    private PolicyResult(boolean allowed,
                         BlockReason reason,
                         String canonicalHost,
                         String rawUrl,
                         String detail,
                         AllowEntry matchedEntry) {
        this.allowed = allowed;
        this.reason = reason;
        this.canonicalHost = canonicalHost == null ? "" : canonicalHost;
        this.rawUrl = rawUrl == null ? "" : rawUrl;
        this.detail = detail == null ? "" : detail;
        this.matchedEntry = matchedEntry;
    }

    static PolicyResult allow(String canonicalHost, String rawUrl, AllowEntry entry) {
        return new PolicyResult(true, null, canonicalHost, rawUrl, "", entry);
    }

    static PolicyResult block(BlockReason reason, String canonicalHost, String rawUrl, String detail) {
        return new PolicyResult(false, reason, canonicalHost, rawUrl, detail, null);
    }

    public boolean isAllowed() {
        return allowed;
    }

    /** Non-null only when {@link #isAllowed()} is false. */
    public BlockReason reason() {
        return reason;
    }

    /** Canonical (lower-cased, punycode-free, trailing-dot-stripped) host, or "" if not determinable. */
    public String canonicalHost() {
        return canonicalHost;
    }

    /** The exact string handed to the policy (kept for diagnostics; never used to load anything). */
    public String rawUrl() {
        return rawUrl;
    }

    /** Additional non-sensitive explanation, e.g. the malformed authority that was rejected. */
    public String detail() {
        return detail;
    }

    /** The allow-list entry that matched, or null. */
    public AllowEntry matchedEntry() {
        return matchedEntry;
    }

    /** One-line summary suitable for a log line or the diagnostics report. */
    public String describe() {
        if (allowed) {
            return String.format(Locale.US, "ALLOW host=%s", canonicalHost);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("BLOCK reason=").append(reason == null ? "?" : reason.name());
        if (!canonicalHost.isEmpty()) {
            sb.append(" host=").append(canonicalHost);
        }
        if (!detail.isEmpty()) {
            sb.append(" detail=").append(detail);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return describe();
    }
}
