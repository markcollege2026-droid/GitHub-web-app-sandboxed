package app.githubwebview.locked.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GitHub configuration for the original Arena wrapper, not a new policy engine.
 *
 * <p>PRODUCT = github.com and its www redirect. SIGN_IN = accounts.google.com only.
 * All other entries are ASSET: requests and embedded documents, never top-level pages, redirects
 * or pop-up destinations. Exact hosts only, with one narrowly scoped exception: GitHub Actions
 * results storage hosts are matched by the exact structure
 * {@code productionresultssa<digits>.blob.core.windows.net} (see {@link #resourceHostPatterns()}),
 * because the numeric endpoint grows over time and an enumerated list breaks on every expansion.
 * That pattern is anchored to the complete hostname, is resource-only, and is not a suffix match,
 * a wildcard, remote configuration or a user approval. Every connection still passes the
 * original CONNECT proxy on port 443.</p>
 *
 * <p>Host evidence and limitations are in docs/DOMAINS.md. GitHub's response CSP is evidence of
 * service names, NOT a policy to copy wholesale: telemetry, arbitrary clouds, Pages, Copilot,
 * marketing links and enterprise identity providers remain outside this allow-list.</p>
 *
 * <p>The original input fix remains: identity bundles are not path-pinned, SUBDOCUMENT differs
 * from DOCUMENT, and merge() cannot narrow an unrestricted asset entry. No input CSS/JS is injected.
 * Neither this configuration nor any test solves a CAPTCHA or bypasses a provider refusal.</p>
 */
public final class GitHubAllowlist {

    /** The only page the app ever opens on its own. */
    public static final String PRIMARY_URL = "https://github.com/";

    /** Exact end-point of every redirect that the wrapper follows back into the app. */
    public static final List<String> FIRST_PARTY_HOSTS;

    /**
     * Sign in with a Google account. GitHub offers it on its sign-in and sign-up pages, and the flow
     * cannot complete without reaching Google's account host.
     *
     * <p>Set to {@code false} to remove the Google identity group. Google CAPTCHA resources are
     * separately controlled by ENABLE_CAPTCHA_WIDGETS. Password/in-page 2FA remain website features;
     * passkeys depend on WebView support, and external authenticators are never launched.</p>
     */
    public static final boolean ENABLE_GOOGLE_SIGN_IN = true;

    /**
     * CAPTCHA widget hosts: GitHub Octocaptcha/Arkose, Google reCAPTCHA and the observed DataDome interstitial.
     *
     * <p>These are <em>assets only</em>: the challenge iframe is a sub-resource of the github.com
     * page, so the widget can render and the user can answer it, but the app can never be navigated to
     * a CAPTCHA host as a page. Enabling them does not weaken any check - the wrapper still does not
     * solve, pre-answer or script a challenge.</p>
     */
    public static final boolean ENABLE_CAPTCHA_WIDGETS = true;

    static {
        List<String> hosts = new ArrayList<String>();
        for (AllowEntry entry : firstPartyEntries()) {
            hosts.add(entry.host());
        }
        FIRST_PARTY_HOSTS = Collections.unmodifiableList(hosts);
    }

    private GitHubAllowlist() {
        // no instances
    }

    /**
     * The complete set of exact hosts this build accepts, in a stable order.
     *
     * <p>Hosts matched by {@link #resourceHostPatterns()} are not enumerated here because the set
     * is open-ended by design; use {@link #isProductionResultsHost(String)} or
     * {@link PolicyConfig#isAllowedHost(String)} to ask about a specific pattern host. The
     * diagnostics report prints the patterns through {@link PolicyConfig#describe()}.</p>
     */
    public static List<String> allowedHosts() {
        List<String> hosts = new ArrayList<String>();
        for (AllowEntry entry : activeEntries()) {
            hosts.add(entry.host());
        }
        return Collections.unmodifiableList(hosts);
    }

    /** The entries actually enforced by this build (first-party, plus the enabled capability groups). */
    public static List<AllowEntry> activeEntries() {
        List<AllowEntry> entries = new ArrayList<AllowEntry>(firstPartyEntries());
        List<List<AllowEntry>> groups = new ArrayList<List<AllowEntry>>();
        groups.add(serviceEntries());
        if (ENABLE_GOOGLE_SIGN_IN) {
            groups.add(signInEntries());
        }
        if (ENABLE_CAPTCHA_WIDGETS) {
            groups.add(captchaEntries());
        }
        entries.addAll(merge(groups));
        return Collections.unmodifiableList(entries);
    }

    /**
     * First-party, GitHub-operated page hosts. Each one is an exact host name - no wildcards exist in
     * this code base at all.
     */
    public static List<AllowEntry> firstPartyEntries() {
        List<AllowEntry> entries = new ArrayList<AllowEntry>();
        entries.add(AllowEntry.anyPath("github.com", "The product itself (canonical host)."));
        entries.add(AllowEntry.anyPath("www.github.com",
                "Canonical alternate host for the same site (redirects to github.com)."));
        return Collections.unmodifiableList(entries);
    }

    /**
     * GitHub's own service and asset hosts: static assets, the site's JSON API, avatars and images,
     * repository content hosts and the real-time channel. Every entry is {@code Role.ASSET}: they may
     * be fetched and embedded, and none of them may ever become the visible document. This is the
     * line the policy draws between "may serve a resource" and "may be a website" - being needed by
     * github.com does not make a host a navigation destination.
     *
     * <p>Path restriction is deliberately not used on these hosts: GitHub's asset and content paths
     * are content-hashed or repository-scoped and change without notice (the same reviewed exception
     * the Arena build made for Google's {@code gstatic.com} hosts - pinning such paths broke sign-in
     * there, and would break page rendering here). The role restriction is what contains them.</p>
     */
    public static List<AllowEntry> serviceEntries() {
        List<AllowEntry> entries = new ArrayList<AllowEntry>();
        entries.add(AllowEntry.resourceOnly("github.githubassets.com", Collections.<String>emptyList(),
                "GitHub JavaScript, CSS, fonts and workers; content-hashed asset paths."));
        entries.add(AllowEntry.resourceOnly("api.github.com", Collections.<String>emptyList(),
                "GitHub API requests used by repository, issue, pull-request and checks pages."));
        entries.add(AllowEntry.resourceOnly("avatars.githubusercontent.com", Collections.<String>emptyList(),
                "Public user and organization avatars."));
        entries.add(AllowEntry.resourceOnly("private-avatars.githubusercontent.com", Collections.<String>emptyList(),
                "Private avatars referenced by the site CSP."));
        entries.add(AllowEntry.resourceOnly("identicons.github.com", Collections.<String>emptyList(),
                "Default avatar images referenced by the site CSP."));
        entries.add(AllowEntry.resourceOnly("user-images.githubusercontent.com", Collections.<String>emptyList(),
                "Legacy Markdown attachments."));
        entries.add(AllowEntry.resourceOnly("private-user-images.githubusercontent.com", Collections.<String>emptyList(),
                "Authenticated Markdown attachments."));
        entries.add(AllowEntry.resourceOnly("secured-user-images.githubusercontent.com", Collections.<String>emptyList(),
                "Signed attachment images and media."));
        entries.add(AllowEntry.resourceOnly("camo.githubusercontent.com", Collections.<String>emptyList(),
                "GitHub image proxy for Markdown; does not allow direct external images."));
        entries.add(AllowEntry.resourceOnly("raw.githubusercontent.com", Collections.<String>emptyList(),
                "Repository file bytes fetched by pages; Raw navigation is still refused."));
        entries.add(AllowEntry.resourceOnly("media.githubusercontent.com", Collections.<String>emptyList(),
                "Git LFS media rendered in repository pages."));
        entries.add(AllowEntry.resourceOnly("objects.githubusercontent.com", Collections.<String>emptyList(),
                "Repository/release content requested by pages; no file saving."));
        entries.add(AllowEntry.resourceOnly("objects-origin.githubusercontent.com", Collections.<String>emptyList(),
                "GitHub object/content origin referenced by the site CSP."));
        entries.add(AllowEntry.resourceOnly("release-assets.githubusercontent.com", Collections.<String>emptyList(),
                "Release content resources; download guard remains in place."));
        entries.add(AllowEntry.resourceOnly("codeload.github.com", Collections.<String>emptyList(),
                "Repository archive resources; archive navigation/downloads remain blocked."));
        entries.add(AllowEntry.resourceOnly("uploads.github.com", Collections.<String>emptyList(),
                "Site upload API; native file picker is still disabled."));
        entries.add(AllowEntry.resourceOnly("viewscreen.githubusercontent.com", Collections.<String>emptyList(),
                "Embedded file previews, including non-text content."));
        entries.add(AllowEntry.resourceOnly("notebooks.githubusercontent.com", Collections.<String>emptyList(),
                "Embedded notebook previews."));
        entries.add(AllowEntry.resourceOnly("repository-images.githubusercontent.com", Collections.<String>emptyList(),
                "Repository social-preview images."));
        entries.add(AllowEntry.resourceOnly("opengraph.githubassets.com", Collections.<String>emptyList(),
                "GitHub social-preview images."));
        entries.add(AllowEntry.resourceOnly("alive.github.com", Collections.<String>emptyList(),
                "Production WebSocket channel for live notifications and check status updates."));
        entries.add(AllowEntry.resourceOnly("github-cloud.s3.amazonaws.com", Collections.<String>emptyList(),
                "Exact GitHub legacy content bucket, not a blanket Amazon S3 rule."));
        entries.add(AllowEntry.resourceOnly("github-production-repository-file-5c1aeb.s3.amazonaws.com", Collections.<String>emptyList(),
                "Exact GitHub repository-file bucket in the site CSP."));
        entries.add(AllowEntry.resourceOnly("github-production-upload-manifest-file-7fdce7.s3.amazonaws.com", Collections.<String>emptyList(),
                "Exact GitHub upload-manifest bucket in the site CSP."));
        entries.add(AllowEntry.resourceOnly("github-production-user-asset-6210df.s3.amazonaws.com", Collections.<String>emptyList(),
                "Exact GitHub issue/PR attachment bucket in the site CSP."));
        entries.add(AllowEntry.resourceOnly("github-production-repository-image-32fea6.s3.amazonaws.com", Collections.<String>emptyList(),
                "Exact GitHub repository-image bucket in the site CSP."));
        entries.add(AllowEntry.resourceOnly("github-production-release-asset-2e65be.s3.amazonaws.com", Collections.<String>emptyList(),
                "Exact GitHub release-asset bucket in the site CSP."));
        // GitHub Actions results/log storage hosts are NOT enumerated here. Their numeric endpoint
        // grows over time (0-19, then 30-49, then 100-199, ...), so they are matched by the single
        // anchored pattern in resourceHostPatterns() instead. See that method for the exact rule.
        entries.add(AllowEntry.resourceOnly("images.ctfassets.net", paths("/8aevphvgewt8/"),
                "GitHub homepage media, restricted to the GitHub Contentful space observed in its HTML."));
        entries.add(AllowEntry.resourceOnly("videos.ctfassets.net", paths("/8aevphvgewt8/"),
                "GitHub homepage media, restricted to the GitHub Contentful space observed in its HTML."));
        return Collections.unmodifiableList(entries);
    }

    /**
     * The Google sign-in hand-off. Exactly one host may become a page; everything else is an asset used
     * by Google's own pages.
     */
    public static List<AllowEntry> signInEntries() {
        List<AllowEntry> entries = new ArrayList<AllowEntry>();
        entries.add(AllowEntry.signIn("accounts.google.com", Collections.<String>emptyList(),
                "Google's account/sign-in pages - GitHub's \"Sign in with Google\" hand-off. Displayed "
                        + "in the main view or the existing guarded sign-in window. No browser escape."));
        entries.add(AllowEntry.resourceOnly("accounts.youtube.com", paths("/accounts/CheckConnection"),
                "Google identity connection-check iframe observed on the served sign-in page; not YouTube navigation."));
        // Google's static asset hosts serve their identity UI from content-hashed, opaque paths
        // (www.gstatic.com/_/mss/boq-identity/_/js/k=boq-identity.AccountsSignInUi.en.<hash>/...) and
        // the path changes with every deployment, so enumerating prefixes here is guaranteed to break
        // sign-in eventually - it did, in the Arena build. These are asset-only entries: they can be
        // fetched and embedded in an iframe, and the wrapper is never navigated to them (the DOCUMENT
        // rule in UrlPolicy).
        entries.add(AllowEntry.resourceOnly("ssl.gstatic.com", Collections.<String>emptyList(),
                "Google's static asset host: sub-resources only, never a page. No path restriction "
                        + "because the paths are content-hashed and change without notice."));
        entries.add(AllowEntry.resourceOnly("www.gstatic.com", Collections.<String>emptyList(),
                "Google's static asset host (identity UI bundles under /_/mss/, images, gsi): "
                        + "sub-resources only, never a page."));
        entries.add(AllowEntry.resourceOnly("apis.google.com", paths("/js/", "/gsi/", "/_/"),
                "Google's client-side identity/gapi scripts loaded by the sign-in hand-off."));
        entries.add(AllowEntry.resourceOnly("fonts.gstatic.com", Collections.singletonList("/s/"),
                "Web font files used by Google's pages (cosmetic only)."));
        entries.add(AllowEntry.resourceOnly("lh3.googleusercontent.com", Collections.singletonList("/a/"),
                "Account avatars shown by Google's account chooser (images only)."));
        return Collections.unmodifiableList(entries);
    }

    /** Challenge resources only; no CAPTCHA host can become the app's page. */
    public static List<AllowEntry> captchaEntries() {
        List<AllowEntry> entries = new ArrayList<AllowEntry>();
        // Google may embed reCAPTCHA during sign-in. Preserve the original frame/resource fix.
        entries.add(AllowEntry.resourceOnly("www.google.com", paths("/recaptcha/"),
                "Google sign-in challenge resources; search and all other Google paths remain blocked."));
        entries.add(AllowEntry.resourceOnly("www.recaptcha.net", paths("/recaptcha/"),
                "Google documented alternate reCAPTCHA resource host."));
        entries.add(AllowEntry.resourceOnly("octocaptcha.com", Collections.<String>emptyList(),
                "GitHub account challenge frame; documented by GitHub connectivity troubleshooting."));
        entries.add(AllowEntry.resourceOnly("github-api.arkoselabs.com", paths("/fc/", "/v2/"),
                "GitHub-specific Arkose challenge scripts, frames and verification endpoints."));
        entries.add(AllowEntry.resourceOnly("client-api.arkoselabs.com", paths("/fc/", "/v2/"),
                "Arkose client challenge assets; no permission to browse the Arkose website."));
        entries.add(AllowEntry.resourceOnly("static.captcha-delivery.com", Collections.<String>emptyList(),
                "DataDome static challenge assets observed in the signed-out browser trace; never a page."));
        entries.add(AllowEntry.resourceOnly("geo.captcha-delivery.com", paths("/captcha/", "/interstitial/"),
                "DataDome challenge frame observed on github.com/signup from this network; never a page."));
        // The bootstrap loads versioned root-level bundles, e.g. /interstitial.1.33.1.202610161.js.
        // Pinning only /c.js and /i.js produced a real blocked-script failure on the emulator. As for
        // gstatic, keep this dedicated challenge-asset host path-unrestricted but NEVER navigable.
        entries.add(AllowEntry.resourceOnly("ct.captcha-delivery.com", Collections.<String>emptyList(),
                "DataDome challenge/interstitial bootstrap and versioned JS bundles observed on signup; assets only."));
        return Collections.unmodifiableList(entries);
    }

    /**
     * Every non-PRODUCT host (including GitHub-owned ASSET hosts), in policy order. Pinned by
     * {@code AllowlistRegressionTest}: this list is the app's whole third-party surface.
     */
    public static List<String> thirdPartyHosts() {
        List<String> hosts = new ArrayList<String>();
        for (AllowEntry entry : activeEntries()) {
            if (!entry.isProduct()) {
                hosts.add(entry.host());
            }
        }
        return Collections.unmodifiableList(hosts);
    }

    /** True when this exact host may be displayed as a sign-in page in the runner-up window. */
    public static boolean isSignInHost(String canonicalHost) {
        if (canonicalHost == null) {
            return false;
        }
        for (AllowEntry entry : activeEntries()) {
            if (entry.isSignIn() && entry.host().equals(canonicalHost)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The one host pattern in this build: GitHub Actions results/log storage accounts.
     *
     * <p>The complete hostname must be {@code productionresultssa} followed by one or more ASCII
     * digits followed by {@code .blob.core.windows.net}. The match is anchored to the complete
     * hostname (see {@link HostPatternEntry#matchesHost(String)}), so look-alikes such as
     * {@code productionresultssa30.blob.core.windows.net.evil.example},
     * {@code evilproductionresultssa30.blob.core.windows.net},
     * {@code productionresultssaX.blob.core.windows.net} and
     * {@code unrelated.blob.core.windows.net} never match. Matching is case-insensitive because
     * the policy canonicalizes hosts to lower case before lookup.</p>
     *
     * <p>The pattern is resource-only: a matched host may serve sub-resources, embedded documents
     * and downloads, and may be tunnelled to on port 443 through the egress guard, but it may
     * never become the visible page. That separation is enforced by the document question in
     * {@link UrlPolicy}, not by this list.</p>
     */
    public static List<HostPatternEntry> resourceHostPatterns() {
        List<HostPatternEntry> patterns = new ArrayList<HostPatternEntry>();
        patterns.add(HostPatternEntry.resourceOnly(
                "productionresultssa<digits>.blob.core.windows.net",
                "productionresultssa[0-9]+\\.blob\\.core\\.windows\\.net",
                Collections.<String>emptyList(),
                "GitHub Actions results/log storage accounts (productionresultssaN.blob.core.windows.net); "
                        + "numeric endpoint varies and grows over time, so the host is matched by this exact "
                        + "structure rather than an enumerated list; never a page."));
        return Collections.unmodifiableList(patterns);
    }

    /** True when the canonical host is a GitHub Actions results storage host under the pattern above. */
    public static boolean isProductionResultsHost(String canonicalHost) {
        for (HostPatternEntry pattern : resourceHostPatterns()) {
            if (pattern.matchesHost(canonicalHost)) {
                return true;
            }
        }
        return false;
    }

    /** Build the policy enforced by this build. */
    public static PolicyConfig productionConfig() {
        return new PolicyConfig(PRIMARY_URL, activeEntries(), resourceHostPatterns());
    }

    // -----------------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------------

    /**
     * Merges capability groups into one entry per host, unioning their path prefixes.
     *
     * <p>Two groups can legitimately want the same host (Google's {@code www.gstatic.com} serves both
     * sign-in and reCAPTCHA assets). {@link PolicyConfig} refuses to be built from a list that names a
     * host twice (a second entry would otherwise be dead text that a reviewer could read but that is
     * never enforced), so the groups are merged first: every prefix stays reachable and the enforced set
     * is exactly what the diagnostics report prints.</p>
     */
    static List<AllowEntry> merge(List<List<AllowEntry>> groups) {
        Map<String, AllowEntry> byHost = new LinkedHashMap<String, AllowEntry>();
        for (List<AllowEntry> group : groups) {
            for (AllowEntry entry : group) {
                AllowEntry existing = byHost.get(entry.host());
                if (existing == null) {
                    byHost.put(entry.host(), entry);
                    continue;
                }
                if (existing.role() != entry.role()) {
                    throw new IllegalStateException("Conflicting allow-list roles for " + entry.host());
                }
                if (existing.pathPrefixes().isEmpty() || entry.pathPrefixes().isEmpty()) {
                    // One of the groups wants the whole host: keep it whole. Union-ing the prefixes here
                    // would silently narrow the wider entry and break whichever flow needed it.
                    byHost.put(entry.host(), new AllowEntry(entry.host(), Collections.<String>emptyList(),
                            existing.purpose(), entry.role()));
                    continue;
                }
                List<String> mergedPaths = new ArrayList<String>(existing.pathPrefixes());
                for (String path : entry.pathPrefixes()) {
                    if (!mergedPaths.contains(path)) {
                        mergedPaths.add(path);
                    }
                }
                byHost.put(entry.host(), new AllowEntry(entry.host(), mergedPaths,
                        existing.purpose(), entry.role()));
            }
        }
        return new ArrayList<AllowEntry>(byHost.values());
    }

    private static List<String> paths(String... prefixes) {
        List<String> list = new ArrayList<String>();
        Collections.addAll(list, prefixes);
        return Collections.unmodifiableList(list);
    }
}
