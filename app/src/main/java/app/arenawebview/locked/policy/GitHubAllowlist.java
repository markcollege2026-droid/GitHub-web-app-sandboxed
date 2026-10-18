package app.arenawebview.locked.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single, compiled-in source of truth for the GitHub wrapper's reachable hosts.
 *
 * <p>There is no remote or user-editable allow-list and no wildcard matching. GitHub.com is the only
 * host that may become the app's visible page. The exact supporting hosts below have the {@code ASSET}
 * role: they may serve the stated in-page resources but can never become a top-level document, redirect
 * destination, or pop-up destination. {@link AllowlistRegressionTest} pins this complete set.</p>
 *
 * <p>The roles are intentionally not inferred from host ownership. GitHub-owned does not mean
 * navigable: an avatar, raw-content, API, or static-asset host remains resource-only. GitHub account
 * authentication is served on {@code github.com} itself, so this build has no third-party sign-in page
 * and does not enable Google's account hosts. An organization-specific SAML identity provider is not a
 * universal GitHub sign-in requirement; such a hand-off remains blocked unless a separately reviewed,
 * exact provider host is explicitly added as a {@link AllowEntry.Role#SIGN_IN} entry.</p>
 */
public final class GitHubAllowlist {

    /** The only page the app ever opens on its own. */
    public static final String PRIMARY_URL = "https://github.com/";

    /** Exact hosts whose entries have the PRODUCT role. */
    public static final List<String> FIRST_PARTY_HOSTS;

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

    /** The complete set of exact host names this build accepts, in a stable order. */
    public static List<String> allowedHosts() {
        List<String> hosts = new ArrayList<String>();
        for (AllowEntry entry : activeEntries()) {
            hosts.add(entry.host());
        }
        return Collections.unmodifiableList(hosts);
    }

    /** The only page-capable entry. All normal GitHub login pages and callbacks use this host. */
    public static List<AllowEntry> firstPartyEntries() {
        List<AllowEntry> entries = new ArrayList<AllowEntry>();
        entries.add(AllowEntry.anyPath("github.com",
                "GitHub's web application, including repository pages, login, issues, pull requests, "
                        + "Actions, and browser-based file editing."));
        return Collections.unmodifiableList(entries);
    }

    /**
     * Exact GitHub service hosts needed for the captured website pages and in-page repository content.
     *
     * <p>All entries are {@code ASSET} and therefore cannot be navigated to as the app's page. Path
     * prefixes are used where the observed path structure is stable. Dynamic avatar, uploaded-image,
     * raw-file, Open Graph, and S3 object keys are intentionally not path-pinned; their unpredictable
     * IDs are still fenced to one exact host and the resource-only role.</p>
     */
    public static List<AllowEntry> resourceEntries() {
        List<AllowEntry> entries = new ArrayList<AllowEntry>();
        entries.add(AllowEntry.resourceOnly("github.githubassets.com", paths("/assets/"),
                "GitHub's observed JavaScript, CSS, fonts, and static images under /assets/."));
        entries.add(AllowEntry.resourceOnly("opengraph.githubassets.com", Collections.<String>emptyList(),
                "GitHub-generated repository/social-preview images; opaque digest and repository paths."));
        entries.add(AllowEntry.resourceOnly("avatars.githubusercontent.com", Collections.<String>emptyList(),
                "GitHub user and organization avatars; opaque numeric/image paths."));
        entries.add(AllowEntry.resourceOnly("user-images.githubusercontent.com", Collections.<String>emptyList(),
                "GitHub-hosted user attachments and images displayed in repositories, issues, and pull requests."));
        entries.add(AllowEntry.resourceOnly("private-user-images.githubusercontent.com",
                Collections.<String>emptyList(),
                "GitHub-hosted private-repository attachments; signed image paths are opaque."));
        entries.add(AllowEntry.resourceOnly("raw.githubusercontent.com", Collections.<String>emptyList(),
                "Raw repository content when embedded by a GitHub page; never a top-level destination."));
        entries.add(AllowEntry.resourceOnly("github-cloud.s3.amazonaws.com", Collections.<String>emptyList(),
                "Exact GitHub-linked legacy content bucket referenced by the live website; in-page resources only."));
        entries.add(AllowEntry.resourceOnly("api.github.com", paths(
                        "/repos/", "/orgs/", "/users/", "/user/", "/notifications/", "/search/",
                        "/graphql/", "/rate_limit/"),
                "GitHub REST/GraphQL data used by in-page repository, issue, pull-request, and Actions views; "
                        + "approved API paths only."));
        return Collections.unmodifiableList(entries);
    }

    /** The entries actually enforced by this build. */
    public static List<AllowEntry> activeEntries() {
        List<AllowEntry> entries = new ArrayList<AllowEntry>(firstPartyEntries());
        entries.addAll(resourceEntries());
        return Collections.unmodifiableList(entries);
    }

    /** Every non-product host this build can reach, in policy order. */
    public static List<String> thirdPartyHosts() {
        List<String> hosts = new ArrayList<String>();
        for (AllowEntry entry : activeEntries()) {
            if (!entry.isProduct()) {
                hosts.add(entry.host());
            }
        }
        return Collections.unmodifiableList(hosts);
    }

    /** True only if an explicitly reviewed sign-in role exists for this exact host. */
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

    /** Build the immutable policy enforced by this build. */
    public static PolicyConfig productionConfig() {
        return new PolicyConfig(PRIMARY_URL, activeEntries());
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers retained from the Arena implementation so capability groups can be reviewed and merged
    // without changing PolicyConfig's one-entry-per-host invariant.
    // ---------------------------------------------------------------------------------------------

    /**
     * Merge capability groups into one entry per exact host, unioning path prefixes. Entries with
     * conflicting roles are rejected; an unrestricted path entry stays unrestricted if another group
     * also needs the host.
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
