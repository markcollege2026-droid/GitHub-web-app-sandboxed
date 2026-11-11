package app.githubwebview.locked.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The complete, immutable navigation policy of the wrapper.
 *
 * <p>Everything the WebView is allowed to touch is described here: one start URL, the first-party
 * hosts, and the narrowly scoped third-party entries that exist only because a specific site
 * capability cannot work without them - Google's sign-in pages, Google's sign-in assets and the
 * CAPTCHA widget assets. Each entry carries a {@link AllowEntry.Role}, so callers can ask not only
 * "is this allowed?" but "is this a GitHub page, or a step of a sign-in hand-off?".</p>
 *
 * <p>The policy has exactly one public decision method per traffic class:</p>
 * <ul>
 *   <li>{@link #evaluateNavigation(String)} for anything that can become the visible document
 *       (main frame, iframes, redirects, {@code window.open} targets).</li>
 *   <li>{@link #evaluateSubdocument(String)} for a document embedded in the page (iframe), which may
 *       come from an asset host - that is how CAPTCHA and identity widgets are embedded.</li>
 *   <li>{@link #evaluateRequest(String)} for every network request the WebView makes
 *       (XHR/fetch, scripts, images, media, trackers, ...).</li>
 * </ul>
 *
 * <p>Both return a {@link PolicyResult}; there is no code path that returns "unknown", so the default
 * for anything unverifiable is a refusal.</p>
 */
public final class PolicyConfig {

    private final String primaryUrl;
    private final List<AllowEntry> entries;
    private final List<HostPatternEntry> hostPatterns;

    /** Exact host to entry. Built once; a host can appear at most once, which the constructor enforces. */
    private final Map<String, AllowEntry> entriesByHost;

    /**
     * @param primaryUrl the app's start page; must itself pass {@link #evaluateNavigation(String)}
     * @throws IllegalArgumentException if a host is listed twice. Two entries for one host would mean
     *                                  that whichever came second is silently ignored - a path pin or a
     *                                  role that a reviewer can read in the source but that is never
     *                                  enforced. That is a programming error, so it fails at start-up.
     */
    public PolicyConfig(String primaryUrl, List<AllowEntry> entries) {
        this(primaryUrl, entries, Collections.<HostPatternEntry>emptyList());
    }

    /**
     * @param primaryUrl   the app's start page; must itself pass {@link #evaluateNavigation(String)}
     * @param entries      exact host entries (the norm)
     * @param hostPatterns narrowly scoped resource-only host patterns for families whose exact
     *                     host varies by a numeric endpoint (see {@link HostPatternEntry}). An exact
     *                     entry always wins over a pattern for the same host.
     */
    public PolicyConfig(String primaryUrl, List<AllowEntry> entries,
                        List<HostPatternEntry> hostPatterns) {
        if (primaryUrl == null || primaryUrl.isEmpty()) {
            throw new IllegalArgumentException("primaryUrl must not be empty");
        }
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("at least one allow-list entry is required");
        }
        List<AllowEntry> copy = new ArrayList<AllowEntry>(entries);
        Map<String, AllowEntry> byHost = new HashMap<String, AllowEntry>(copy.size() * 2);
        for (AllowEntry entry : copy) {
            if (entry == null) {
                throw new IllegalArgumentException("allow-list entries must not be null");
            }
            if (byHost.put(entry.host(), entry) != null) {
                throw new IllegalArgumentException("host listed twice in the allow-list: " + entry.host());
            }
        }
        List<HostPatternEntry> patternCopy = new ArrayList<HostPatternEntry>();
        if (hostPatterns != null) {
            for (HostPatternEntry pattern : hostPatterns) {
                if (pattern == null) {
                    throw new IllegalArgumentException("host pattern entries must not be null");
                }
                if (!pattern.isResourceOnly()) {
                    throw new IllegalArgumentException(
                            "host patterns must be resource-only: " + pattern.description());
                }
                if (patternCopy.contains(pattern)) {
                    throw new IllegalArgumentException(
                            "host pattern listed twice in the allow-list: " + pattern.description());
                }
                patternCopy.add(pattern);
            }
        }
        this.primaryUrl = primaryUrl;
        this.entries = Collections.unmodifiableList(copy);
        this.hostPatterns = Collections.unmodifiableList(patternCopy);
        this.entriesByHost = byHost;

        // Fail loudly at start-up if the configured start page would not pass the configured policy.
        // "Pass" means a real product page: an inert form such as about:blank is also "allowed" as a
        // document, but a start page that shows nothing is a misconfiguration, not a policy.
        PolicyResult start = evaluateNavigation(primaryUrl);
        if (!start.isAllowed()) {
            throw new IllegalStateException("primaryUrl is not allowed by this policy: " + start.describe());
        }
        if (start.matchedEntry() == null || !start.matchedEntry().isProduct()) {
            throw new IllegalStateException("primaryUrl must be a page of the product itself: " + primaryUrl);
        }
    }

    public String primaryUrl() {
        return primaryUrl;
    }

    public List<AllowEntry> entries() {
        return entries;
    }

    /** Narrowly scoped resource-only host patterns (empty for policies without any). */
    public List<HostPatternEntry> hostPatterns() {
        return hostPatterns;
    }

    /** Evaluate a URL that can become (or become part of) a rendered document. */
    public PolicyResult evaluateNavigation(String rawUrl) {
        return UrlPolicy.evaluate(rawUrl, this, UrlPolicy.TrafficClass.DOCUMENT);
    }

    /**
     * Evaluate a document that is rendered <em>inside</em> a page (iframe/frame/embed).
     *
     * <p>Kept separate from {@link #evaluateNavigation(String)} so that "may this replace what the user
     * is looking at?" and "may this be embedded in the page the user is looking at?" stay two different
     * questions. A CAPTCHA widget frame must be allowed; the app must never be navigated to it.</p>
     */
    public PolicyResult evaluateSubdocument(String rawUrl) {
        return UrlPolicy.evaluate(rawUrl, this, UrlPolicy.TrafficClass.SUBDOCUMENT);
    }

    /** Evaluate a sub-resource request (script, image, XHR, websocket-less fetch, ...). */
    public PolicyResult evaluateRequest(String rawUrl) {
        return UrlPolicy.evaluate(rawUrl, this, UrlPolicy.TrafficClass.SUBRESOURCE);
    }

    /**
     * Evaluate a TCP tunnel request ({@code CONNECT host:port}) from the egress proxy: may this app open
     * any TLS connection to that host at all? Host-level only; the URL questions above still apply to
     * everything the WebView hooks can see.
     */
    public PolicyResult evaluateTunnel(String host, int port) {
        return UrlPolicy.evaluateTunnel(host, port, this);
    }

    /**
     * True when the URL is a page of a sign-in hand-off (Google's account host) rather than an GitHub
     * page. Used to decide whether a pop-up must become an in-app sign-in window: the OAuth flow needs a
     * real window of its own, because the site's page talks to it through {@code window.opener}.
     */
    public boolean isSignInNavigation(String rawUrl) {
        PolicyResult result = evaluateNavigation(rawUrl);
        if (!result.isAllowed()) {
            return false;
        }
        AllowEntry entry = result.matchedEntry();
        return entry != null && entry.isSignIn();
    }

    /** True when the URL is a page of the product itself (any first-party GitHub host). */
    public boolean isProductNavigation(String rawUrl) {
        PolicyResult result = evaluateNavigation(rawUrl);
        if (!result.isAllowed()) {
            return false;
        }
        AllowEntry entry = result.matchedEntry();
        return entry != null && entry.isProduct();
    }

    /**
     * Find the entry for a canonical host, or null.
     *
     * <p>Exact entries win. Only when no exact entry matches are the resource-only host patterns
     * consulted, in order, and a pattern match synthesizes the exact entry for that host. The
     * pattern match is anchored to the complete hostname, so a look-alike that merely contains
     * the pattern text never matches.</p>
     */
    AllowEntry findByHost(String canonicalHost) {
        if (canonicalHost == null) {
            return null;
        }
        AllowEntry exact = entriesByHost.get(canonicalHost);
        if (exact != null) {
            return exact;
        }
        for (HostPatternEntry pattern : hostPatterns) {
            if (pattern.matchesHost(canonicalHost)) {
                return pattern.toEntry(canonicalHost);
            }
        }
        return null;
    }

    /** True when the canonical host is allow-listed, exactly or through a resource-only pattern. */
    public boolean isAllowedHost(String canonicalHost) {
        return findByHost(canonicalHost) != null;
    }

    /** Human readable dump of the whole policy, used by the diagnostics report and by tests. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("start=").append(primaryUrl).append('\n');
        for (AllowEntry entry : entries) {
            sb.append("allow ").append(entry.describe()).append('\n');
        }
        for (HostPatternEntry pattern : hostPatterns) {
            sb.append("allow ").append(pattern.describe()).append('\n');
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "PolicyConfig(start=%s, entries=%d)", primaryUrl, entries.size());
    }
}
