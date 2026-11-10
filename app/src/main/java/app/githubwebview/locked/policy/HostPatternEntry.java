package app.githubwebview.locked.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A narrowly scoped host pattern for resource-only storage hosts whose numeric endpoint varies.
 *
 * <p>Exact hosts remain the norm in this policy (see {@link AllowEntry}). This type exists for the
 * one family where an exact list cannot stay correct: GitHub Actions results storage accounts are
 * named {@code productionresultssa} followed by a decimal endpoint number and then
 * {@code .blob.core.windows.net}. The endpoint number grows over time (0-19 were observed first,
 * then 30-49, then 100-199), so enumerating every number would break again on the next expansion.
 * The pattern below matches the complete hostname and nothing else: the prefix, one or more ASCII
 * digits, and the exact suffix, anchored at both ends.</p>
 *
 * <p>A pattern entry is always resource-only in this build. It may serve sub-resources and
 * downloads, and it may be embedded as a sub-document exactly like any other asset host, but it
 * may never become the visible page. The role check lives in {@link UrlPolicy} and is unchanged:
 * a pattern match produces an {@link AllowEntry} with role {@link AllowEntry.Role#ASSET}, so the
 * document question still refuses it.</p>
 *
 * <p>Matching is done on the canonical host (lower-cased by {@link UrlPolicy#canonicalizeHost}).
 * The pattern itself is compiled case-insensitively as a second line of defence, so a caller that
 * hands in a non-canonical host still gets the same answer the browser would. Look-alikes fail
 * because the match is anchored: a suffix such as {@code .evil.example} after the storage suffix,
 * a prefix such as {@code evil} before {@code productionresultssa}, a non-digit where the number
 * belongs, or a different storage suffix all fail the complete match.</p>
 *
 * <p>Immutable and Android-free so that it can be unit tested on a plain JVM.</p>
 */
public final class HostPatternEntry {

    private final String description;
    private final Pattern pattern;
    private final List<String> pathPrefixes;
    private final String purpose;
    private final AllowEntry.Role role;

    private HostPatternEntry(String description, String regex, List<String> pathPrefixes,
                             String purpose, AllowEntry.Role role) {
        if (description == null || description.isEmpty()) {
            throw new IllegalArgumentException("host pattern description must not be empty");
        }
        if (regex == null || regex.isEmpty()) {
            throw new IllegalArgumentException("host pattern regex must not be empty");
        }
        List<String> cleaned = new ArrayList<String>();
        if (pathPrefixes != null) {
            for (String p : pathPrefixes) {
                if (p == null || !p.startsWith("/")) {
                    throw new IllegalArgumentException("Path prefix must start with '/': " + p);
                }
                cleaned.add(p);
            }
        }
        this.description = description;
        this.pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        this.pathPrefixes = Collections.unmodifiableList(cleaned);
        this.purpose = purpose == null ? "" : purpose;
        this.role = role == null ? AllowEntry.Role.ASSET : role;
    }

    /**
     * Factory for a resource-only host pattern (sub-resources only, never a page).
     *
     * @param description human readable pattern, e.g. {@code productionresultssa<digits>.blob.core.windows.net}
     * @param regex       anchored regular expression for the complete canonical host
     * @param pathPrefixes optional path prefixes; empty means any path on a matched host
     * @param purpose     why this pattern is reachable, for auditability
     */
    public static HostPatternEntry resourceOnly(String description, String regex,
                                                List<String> pathPrefixes, String purpose) {
        return new HostPatternEntry(description, regex, pathPrefixes, purpose, AllowEntry.Role.ASSET);
    }

    /** True when this pattern matches the complete canonical host. */
    public boolean matchesHost(String canonicalHost) {
        if (canonicalHost == null || canonicalHost.isEmpty()) {
            return false;
        }
        // Canonical hosts are already lower-cased; lower-casing again keeps direct callers honest
        // without changing the answer for canonical input.
        String candidate = canonicalHost.toLowerCase(Locale.ROOT);
        return pattern.matcher(candidate).matches();
    }

    /**
     * The exact entry for a matched host. Callers only invoke this after {@link #matchesHost}
     * returned true, so the synthesized entry is a plain exact host entry with this pattern's
     * role, path pins and purpose.
     */
    public AllowEntry toEntry(String canonicalHost) {
        return new AllowEntry(canonicalHost, pathPrefixes, purpose, role);
    }

    public String description() {
        return description;
    }

    public List<String> pathPrefixes() {
        return pathPrefixes;
    }

    public String purpose() {
        return purpose;
    }

    public AllowEntry.Role role() {
        return role;
    }

    public boolean isResourceOnly() {
        return role == AllowEntry.Role.ASSET;
    }

    public boolean isPathRestricted() {
        return !pathPrefixes.isEmpty();
    }

    /** Renders the pattern in a stable form for reporting. */
    public String describe() {
        StringBuilder sb = new StringBuilder(description.toLowerCase(Locale.ROOT));
        if (role == AllowEntry.Role.ASSET) {
            sb.append(" [resource-only pattern]");
        } else if (role == AllowEntry.Role.SIGN_IN) {
            sb.append(" [sign-in page pattern]");
        }
        if (isPathRestricted()) {
            sb.append(" paths=").append(pathPrefixes);
        }
        if (!purpose.isEmpty()) {
            sb.append("  // ").append(purpose);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "HostPatternEntry{" + description + "}";
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof HostPatternEntry)) {
            return false;
        }
        HostPatternEntry that = (HostPatternEntry) other;
        return description.equals(that.description)
                && pattern.pattern().equals(that.pattern.pattern())
                && pathPrefixes.equals(that.pathPrefixes)
                && role == that.role;
    }

    @Override
    public int hashCode() {
        int result = description.hashCode();
        result = result * 31 + pattern.pattern().hashCode();
        result = result * 31 + pathPrefixes.hashCode();
        result = result * 31 + role.hashCode();
        return result;
    }
}
