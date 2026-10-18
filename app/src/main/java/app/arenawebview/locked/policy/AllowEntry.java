package app.arenawebview.locked.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * One entry of the allow-list: an <em>exact</em> host plus an optional set of allowed path prefixes.
 *
 * <p>There is deliberately <b>no wildcard support</b>. An entry matches a host if and only if the
 * canonical host is byte-for-byte equal to {@link #host}. That single design decision is what makes
 * look-alike domains ({@code github-com.example}, {@code github.com.evil.example}, {@code xn--...},
 * {@code gіthub.com} with a Cyrillic character, {@code github.com.} with a trailing dot) impossible to reach:
 * they simply are not equal to {@code github.com}.</p>
 *
 * <p>Entries carry a {@link Role}: GitHub.com product pages, third-party asset hosts that may only ever
 * serve in-page resources, and any separately reviewed sign-in hosts required by an authentication
 * hand-off. Only {@code PRODUCT} and {@code SIGN_IN} entries can become the visible document, and the
 * in-app sign-in window additionally refuses anything that is not one of those two roles.</p>
 *
 * <p>Immutable and Android-free so that it can be unit tested on a plain JVM.</p>
 */
public final class AllowEntry {

    /**
     * What an entry is for. The role never widens the set of hosts - it only records which capability
     * the entry exists for, so the app can tell an ordinary page navigation apart from the step of a
     * sign-in hand-off (see {@link #isSignIn()}).
     */
    public enum Role {
        /** The product's primary web application: GitHub.com pages. */
        PRODUCT,
        /** A third-party asset host: sub-resources only, never a page. */
        ASSET,
        /** A third-party page explicitly approved for an authentication hand-off. */
        SIGN_IN
    }

    /** Exact canonical host, lower case, ASCII. */
    private final String host;

    /** Allowed path prefixes; empty list means "any path on this host". */
    private final List<String> pathPrefixes;

    /** Human readable justification, shown in the diagnostics report. */
    private final String purpose;

    /** What this entry is for; see {@link Role}. */
    private final Role role;

    /**
     * @param host         exact canonical host (lower case ASCII, e.g. {@code github.com})
     * @param pathPrefixes optional path prefixes such as {@code /assets/}; empty means any path
     * @param purpose      why this host is reachable, for auditability
     */
    public AllowEntry(String host, List<String> pathPrefixes, String purpose) {
        this(host, pathPrefixes, purpose, Role.PRODUCT);
    }

    /**
     * @param role what the entry is for; {@link Role#ASSET} means "sub-resources only, never a page"
     */
    public AllowEntry(String host, List<String> pathPrefixes, String purpose, Role role) {
        String canonical = UrlPolicy.canonicalizeHost(host);
        if (canonical == null) {
            // A malformed allow-list entry is a programming error and must fail loudly at start-up
            // rather than silently widening or breaking the policy.
            throw new IllegalArgumentException("Not a valid allow-list host: " + host);
        }
        if (!canonical.equals(host)) {
            throw new IllegalArgumentException(
                    "Allow-list hosts must already be canonical (lower case, no trailing dot): " + host);
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
        this.host = canonical;
        this.pathPrefixes = Collections.unmodifiableList(cleaned);
        this.purpose = purpose == null ? "" : purpose;
        this.role = role == null ? Role.PRODUCT : role;
    }

    /** Convenience factory for an "any path on this host" entry. */
    public static AllowEntry anyPath(String host, String purpose) {
        return new AllowEntry(host, Collections.<String>emptyList(), purpose);
    }

    /** Convenience factory for a path-scoped, resource-only entry (third-party asset/widget hosts). */
    public static AllowEntry resourceOnly(String host, List<String> pathPrefixes, String purpose) {
        return new AllowEntry(host, pathPrefixes, purpose, Role.ASSET);
    }

    /**
     * Convenience factory for a third-party <em>page</em> that exists only as one step of a sign-in
     * hand-off. Such a host may become the visible document, which is exactly what the OAuth flow
     * needs, and it is the only class of entry that may do so outside the {@link Role#PRODUCT} hosts.
     */
    public static AllowEntry signIn(String host, List<String> pathPrefixes, String purpose) {
        return new AllowEntry(host, pathPrefixes, purpose, Role.SIGN_IN);
    }

    /** True when this host may serve sub-resources but must never become the visible document. */
    public boolean isResourceOnly() {
        return role == Role.ASSET;
    }

    /** True when this entry is a step of an explicitly approved sign-in hand-off. */
    public boolean isSignIn() {
        return role == Role.SIGN_IN;
    }

    /** True when this entry is part of the product itself. */
    public boolean isProduct() {
        return role == Role.PRODUCT;
    }

    public Role role() {
        return role;
    }

    public String host() {
        return host;
    }

    public List<String> pathPrefixes() {
        return pathPrefixes;
    }

    public String purpose() {
        return purpose;
    }

    /** True when this entry restricts which paths may be used. */
    public boolean isPathRestricted() {
        return !pathPrefixes.isEmpty();
    }

    /**
     * Match the raw (still percent-encoded) path of a request against this entry's prefixes.
     *
     * <p>Matching is done on the <em>raw</em> path on purpose. Percent-decoding before the comparison
     * would let {@code /assets%2f..%2fanything} look like it starts with {@code /assets/}. Any
     * path on a path-restricted entry that contains a percent sign or a {@code ..} segment is refused
     * outright (fail closed) - GitHub's static asset URLs do not need either.</p>
     */
    public boolean matchesPath(String rawPath) {
        if (pathPrefixes.isEmpty()) {
            return true;
        }
        if (rawPath == null || rawPath.indexOf('%') >= 0 || rawPath.contains("..")) {
            return false;
        }
        for (String prefix : pathPrefixes) {
            if (rawPath.startsWith(prefix)) {
                return true;
            }
            // "/assets" should also match a prefix written as "/assets/".
            if (prefix.endsWith("/") && rawPath.equals(prefix.substring(0, prefix.length() - 1))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        if (pathPrefixes.isEmpty()) {
            return "AllowEntry{" + host + " any path}";
        }
        return "AllowEntry{" + host + " paths=" + pathPrefixes + "}";
    }

    /** Equality is host+paths only; used by regression tests. */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AllowEntry)) {
            return false;
        }
        AllowEntry that = (AllowEntry) other;
        return host.equals(that.host) && pathPrefixes.equals(that.pathPrefixes);
    }

    @Override
    public int hashCode() {
        return host.hashCode() * 31 + pathPrefixes.hashCode();
    }

    /** Renders the entry in a stable, lower-case form for reporting. */
    public String describe() {
        StringBuilder sb = new StringBuilder(host.toLowerCase(Locale.ROOT));
        if (role == Role.ASSET) {
            sb.append(" [resource-only]");
        } else if (role == Role.SIGN_IN) {
            sb.append(" [sign-in page]");
        }
        if (isPathRestricted()) {
            sb.append(" paths=").append(pathPrefixes);
        }
        if (!purpose.isEmpty()) {
            sb.append("  // ").append(purpose);
        }
        return sb.toString();
    }
}
