package app.arenawebview.locked.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The complete, immutable navigation policy of the wrapper.
 *
 * <p>Everything the WebView is allowed to touch is described here: one start URL, the GitHub.com
 * product host, and narrowly scoped resource-only hosts for GitHub's static assets, API data, avatars,
 * and repository content. An explicitly approved sign-in host can be represented with the existing
 * {@link AllowEntry.Role#SIGN_IN} role if a future, reviewed authentication flow requires one. Each
 * entry carries a role, so callers can distinguish an allowed GitHub page from a sign-in hand-off or
 * an in-page resource.</p>
 *
 * <p>The policy has exactly one public decision method per traffic class:</p>
 * <ul>
 *   <li>{@link #evaluateNavigation(String)} for anything that can become the visible document
 *       (main frame, iframes, redirects, {@code window.open} targets).</li>
 *   <li>{@link #evaluateSubdocument(String)} for a document embedded in the page (iframe), which may
 *       come from an asset host - that is how GitHub-hosted content and embeds are rendered.</li>
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
        this.primaryUrl = primaryUrl;
        this.entries = Collections.unmodifiableList(copy);
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

    /** Evaluate a URL that can become (or become part of) a rendered document. */
    public PolicyResult evaluateNavigation(String rawUrl) {
        return UrlPolicy.evaluate(rawUrl, this, UrlPolicy.TrafficClass.DOCUMENT);
    }

    /**
     * Evaluate a document that is rendered <em>inside</em> a page (iframe/frame/embed).
     *
     * <p>Kept separate from {@link #evaluateNavigation(String)} so that "may this replace what the user
     * is looking at?" and "may this be embedded in the page the user is looking at?" stay two different
     * questions. A resource-host frame may be allowed; the app must never be navigated to that host as its page.</p>
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
     * True when the URL is an explicitly approved sign-in page rather than a GitHub.com page. Used to
     * decide whether a pop-up must become an in-app sign-in window: only flows that require
     * {@code window.opener} may use that existing capability.
     */
    public boolean isSignInNavigation(String rawUrl) {
        PolicyResult result = evaluateNavigation(rawUrl);
        if (!result.isAllowed()) {
            return false;
        }
        AllowEntry entry = result.matchedEntry();
        return entry != null && entry.isSignIn();
    }

    /** True when the URL is a page of the GitHub.com product host. */
    public boolean isProductNavigation(String rawUrl) {
        PolicyResult result = evaluateNavigation(rawUrl);
        if (!result.isAllowed()) {
            return false;
        }
        AllowEntry entry = result.matchedEntry();
        return entry != null && entry.isProduct();
    }

    /** Find the entry for a canonical host, or null. Exact match only; there is nothing to iterate. */
    AllowEntry findByHost(String canonicalHost) {
        return canonicalHost == null ? null : entriesByHost.get(canonicalHost);
    }

    /** Human readable dump of the whole policy, used by the diagnostics report and by tests. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("start=").append(primaryUrl).append('\n');
        for (AllowEntry entry : entries) {
            sb.append("allow ").append(entry.describe()).append('\n');
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "PolicyConfig(start=%s, entries=%d)", primaryUrl, entries.size());
    }
}
