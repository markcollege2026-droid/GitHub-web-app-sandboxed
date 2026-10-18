package app.arenawebview.locked.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pins the exact reachable hosts and their PRODUCT, ASSET, and optional SIGN_IN roles. */
public class AllowlistRegressionTest {

    private static final List<String> EXPECTED_PRODUCT_HOSTS = Collections.singletonList("github.com");
    private static final List<String> EXPECTED_RESOURCE_HOSTS = Arrays.asList(
            "github.githubassets.com",
            "opengraph.githubassets.com",
            "avatars.githubusercontent.com",
            "user-images.githubusercontent.com",
            "private-user-images.githubusercontent.com",
            "raw.githubusercontent.com",
            "github-cloud.s3.amazonaws.com",
            "api.github.com");

    @Test
    public void startPageIsGitHub() {
        assertEquals("https://github.com/", GitHubAllowlist.PRIMARY_URL);
        assertTrue(GitHubAllowlist.productionConfig().evaluateNavigation(GitHubAllowlist.PRIMARY_URL)
                .isAllowed());
    }

    @Test
    public void exactlyTheReviewedHostsAreActive() {
        assertEquals(EXPECTED_PRODUCT_HOSTS, GitHubAllowlist.FIRST_PARTY_HOSTS);
        assertEquals(EXPECTED_RESOURCE_HOSTS, GitHubAllowlist.thirdPartyHosts());

        List<String> expected = new ArrayList<String>(EXPECTED_PRODUCT_HOSTS);
        expected.addAll(EXPECTED_RESOURCE_HOSTS);
        assertEquals(expected, GitHubAllowlist.allowedHosts());
        assertEquals("no host may be listed twice", expected.size(),
                new HashSet<String>(GitHubAllowlist.allowedHosts()).size());
    }

    @Test
    public void githubComIsTheOnlyPageCapableHost() {
        PolicyConfig config = GitHubAllowlist.productionConfig();
        assertEquals(1, GitHubAllowlist.firstPartyEntries().size());
        assertEquals(1, config.entries().stream().filter(entry -> entry.role() == AllowEntry.Role.PRODUCT).count());
        assertEquals("no external identity provider is enabled by default", 0,
                config.entries().stream().filter(AllowEntry::isSignIn).count());

        assertTrue(config.isProductNavigation("https://github.com/owner/repository/issues/12"));
        assertTrue(config.isProductNavigation("https://github.com/login"));
        assertTrue(config.isProductNavigation("https://github.com/session"));
        assertFalse(config.isSignInNavigation("https://github.com/login"));
        assertFalse(GitHubAllowlist.isSignInHost("github.com"));
        assertFalse(config.evaluateNavigation("https://accounts.google.com/signin").isAllowed());
        assertFalse(config.evaluateNavigation("https://login.microsoftonline.com/").isAllowed());
    }

    @Test
    public void resourceHostsAreExactAndNeverBecomePages() {
        for (AllowEntry entry : GitHubAllowlist.resourceEntries()) {
            assertEquals(AllowEntry.Role.ASSET, entry.role());
            assertTrue(entry.isResourceOnly());
            assertFalse(entry.isProduct());
            assertFalse(entry.isSignIn());
            assertTrue("each host needs a concrete purpose: " + entry.host(), !entry.purpose().isEmpty());

            PolicyResult page = GitHubAllowlist.productionConfig().evaluateNavigation(
                    "https://" + entry.host() + "/resource");
            assertFalse(entry.host() + " must remain resource-only", page.isAllowed());
            assertEquals(entry.host(), BlockReason.DOCUMENT_NOT_ALLOWED, page.reason());
        }
    }

    @Test
    public void onlyTheReviewedGithubAssetAndApiPathsArePinned() {
        PolicyConfig config = GitHubAllowlist.productionConfig();
        AllowEntry staticAssets = config.findByHost("github.githubassets.com");
        assertEquals(Collections.singletonList("/assets/"), staticAssets.pathPrefixes());
        assertTrue(config.evaluateRequest("https://github.githubassets.com/assets/app.js").isAllowed());
        assertTrue(config.evaluateRequest("https://github.githubassets.com/assets").isAllowed());
        assertFalse(config.evaluateRequest("https://github.githubassets.com/other/app.js").isAllowed());

        AllowEntry api = config.findByHost("api.github.com");
        assertEquals(Arrays.asList("/repos/", "/orgs/", "/users/", "/user/", "/notifications/",
                "/search/", "/graphql/", "/rate_limit/"), api.pathPrefixes());
        assertTrue(config.evaluateRequest("https://api.github.com/repos/owner/repo").isAllowed());
        assertTrue(config.evaluateRequest("https://api.github.com/users/octocat").isAllowed());
        assertTrue(config.evaluateRequest("https://api.github.com/graphql").isAllowed());
        assertTrue(config.evaluateRequest("https://api.github.com/user").isAllowed());
        assertTrue(config.evaluateRequest("https://api.github.com/notifications").isAllowed());
        assertFalse(config.evaluateRequest("https://api.github.com/gists").isAllowed());
        assertFalse(config.evaluateRequest("https://api.github.com/username").isAllowed());
        assertFalse(config.evaluateRequest("https://api.github.com/notifications-extra").isAllowed());
        assertFalse(config.evaluateRequest("https://api.github.com/graphql-extra").isAllowed());
        assertFalse(config.evaluateRequest("https://api.github.com/repos%2fowner").isAllowed());
        assertFalse(config.evaluateNavigation("https://api.github.com/repos/owner/repo").isAllowed());
    }

    @Test
    public void resourceHostsWithoutStablePathsRemainExactAndResourceOnly() {
        for (String host : Arrays.asList("opengraph.githubassets.com", "avatars.githubusercontent.com",
                "user-images.githubusercontent.com", "private-user-images.githubusercontent.com",
                "raw.githubusercontent.com", "github-cloud.s3.amazonaws.com")) {
            AllowEntry entry = GitHubAllowlist.productionConfig().findByHost(host);
            assertNotNull(host, entry);
            assertTrue("dynamic/opaque image or object path remains available", entry.pathPrefixes().isEmpty());
            assertTrue(entry.isResourceOnly());
            assertTrue(GitHubAllowlist.productionConfig().evaluateRequest("https://" + host + "/opaque/path")
                    .isAllowed());
            assertFalse(GitHubAllowlist.productionConfig().evaluateNavigation("https://" + host + "/opaque/path")
                    .isAllowed());
        }
    }

    @Test
    public void mergeKeepsTheSingleEntryPerHostInvariant() {
        List<AllowEntry> union = GitHubAllowlist.merge(Arrays.asList(
                Collections.singletonList(AllowEntry.resourceOnly("merge.example", Arrays.asList("/a/"), "one")),
                Collections.singletonList(AllowEntry.resourceOnly("merge.example", Arrays.asList("/b/"), "two"))));
        assertEquals(1, union.size());
        assertEquals(Arrays.asList("/a/", "/b/"), union.get(0).pathPrefixes());

        List<AllowEntry> unrestricted = GitHubAllowlist.merge(Arrays.asList(
                Collections.singletonList(AllowEntry.resourceOnly("merge.example", Arrays.asList("/a/"), "one")),
                Collections.singletonList(AllowEntry.resourceOnly("merge.example",
                        Collections.<String>emptyList(), "two"))));
        assertEquals(1, unrestricted.size());
        assertTrue(unrestricted.get(0).pathPrefixes().isEmpty());
    }

    @Test
    public void noWildcardsOrUnreviewedGithubAndIdentityHostsAreReachable() {
        for (AllowEntry entry : GitHubAllowlist.activeEntries()) {
            String host = entry.host();
            assertFalse(host.contains("*"));
            assertFalse(host.contains("?"));
            assertFalse(host.startsWith("."));
            assertEquals(host, UrlPolicy.canonicalizeHost(host));
            assertNotNull(entry.purpose());
            assertFalse(entry.purpose().isEmpty());
        }

        String[] excluded = {
                "www.github.com", "github.com.evil.example", "evilgithub.com", "github-com.example",
                "github.io", "gist.github.com", "github.dev", "docs.github.com", "support.github.com",
                "githubcopilot.com", "api.githubcopilot.com", "codeload.github.com",
                "objects.githubusercontent.com", "collector.github.com", "githubusercontent.com",
                "accounts.google.com", "www.google.com", "www.gstatic.com", "oauth2.googleapis.com",
                "login.microsoftonline.com", "sso.okta.com", "customer.okta.com", "identity.example.com"
        };
        PolicyConfig config = GitHubAllowlist.productionConfig();
        for (String host : excluded) {
            assertFalse("must not be reachable: " + host,
                    config.evaluateNavigation("https://" + host + "/x").isAllowed());
            assertFalse("must not be reachable as a resource: " + host,
                    config.evaluateRequest("https://" + host + "/x").isAllowed());
        }
    }

    @Test
    public void policyDescriptionIsStableAndListsOnlyTheReviewedHosts() {
        String description = GitHubAllowlist.productionConfig().describe();
        assertTrue(description.contains("start=https://github.com/"));
        for (String host : GitHubAllowlist.allowedHosts()) {
            assertTrue("description must mention " + host, description.contains("allow " + host));
        }
        assertFalse(description.contains("accounts.google.com"));
    }
}
