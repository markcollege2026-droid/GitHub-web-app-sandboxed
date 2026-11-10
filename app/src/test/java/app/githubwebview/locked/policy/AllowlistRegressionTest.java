package app.githubwebview.locked.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pins the allow-list.
 *
 * <p>The reachable set of hosts is a security decision, so it must not be able to change silently: any
 * edit to {@link GitHubAllowlist} that widens it (a new host, a "convenience" wildcard, an extra
 * third-party entry) fails here and forces the change to be deliberate and visible in review.</p>
 *
 * <p>The Google sign-in and CAPTCHA groups are enabled in this build, and every host in them is pinned
 * below - including the two facts that matter most: {@code accounts.google.com} is the <em>only</em>
 * third-party host that may become a page, and every other third-party host is asset-only (some
 * additionally path-scoped).</p>
 */
public class AllowlistRegressionTest {

    private static final List<String> EXPECTED_FIRST_PARTY_HOSTS = Arrays.asList(
            "github.com",
            "www.github.com");

    @Test
    public void startPageIsGitHubCom() {
        assertEquals("https://github.com/", GitHubAllowlist.PRIMARY_URL);
        assertTrue(GitHubAllowlist.productionConfig().evaluateNavigation(GitHubAllowlist.PRIMARY_URL)
                .isAllowed());
    }

    @Test
    public void firstPartyHostsAreExactlyTheReviewedSet() {
        assertEquals(EXPECTED_FIRST_PARTY_HOSTS, GitHubAllowlist.FIRST_PARTY_HOSTS);

        // The enforced set is exactly "the reviewed first-party hosts" plus "the reviewed third-party
        // hosts": nothing is dropped, nothing is duplicated, and no entry can exist outside those two
        // lists - a host that is not in this assertion cannot be reached by the app.
        List<String> expectedEnforced = new ArrayList<String>(EXPECTED_FIRST_PARTY_HOSTS);
        expectedEnforced.addAll(GitHubAllowlist.thirdPartyHosts());
        assertEquals("the whole enforced allow-list", expectedEnforced, GitHubAllowlist.allowedHosts());

        Set<String> unique = new LinkedHashSet<String>(GitHubAllowlist.allowedHosts());
        assertEquals("no host may be listed twice", GitHubAllowlist.allowedHosts().size(), unique.size());
    }

    private static final List<String> EXPECTED_SERVICE_HOSTS = Arrays.asList(
            "github.githubassets.com",
            "api.github.com",
            "avatars.githubusercontent.com",
            "private-avatars.githubusercontent.com",
            "identicons.github.com",
            "user-images.githubusercontent.com",
            "private-user-images.githubusercontent.com",
            "secured-user-images.githubusercontent.com",
            "camo.githubusercontent.com",
            "raw.githubusercontent.com",
            "media.githubusercontent.com",
            "objects.githubusercontent.com",
            "objects-origin.githubusercontent.com",
            "release-assets.githubusercontent.com",
            "codeload.github.com",
            "uploads.github.com",
            "viewscreen.githubusercontent.com",
            "notebooks.githubusercontent.com",
            "repository-images.githubusercontent.com",
            "opengraph.githubassets.com",
            "alive.github.com",
            "github-cloud.s3.amazonaws.com",
            "github-production-repository-file-5c1aeb.s3.amazonaws.com",
            "github-production-upload-manifest-file-7fdce7.s3.amazonaws.com",
            "github-production-user-asset-6210df.s3.amazonaws.com",
            "github-production-repository-image-32fea6.s3.amazonaws.com",
            "github-production-release-asset-2e65be.s3.amazonaws.com",
            "images.ctfassets.net",
            "videos.ctfassets.net");

    @Test
    public void theThirdPartyHostsAreExactlyTheReviewedSet() {
        List<String> expected = new ArrayList<String>(EXPECTED_SERVICE_HOSTS);
        expected.addAll(Arrays.asList(
                "accounts.google.com", "accounts.youtube.com", "ssl.gstatic.com", "www.gstatic.com", "apis.google.com",
                "fonts.gstatic.com", "lh3.googleusercontent.com", "www.google.com", "www.recaptcha.net",
                "octocaptcha.com", "github-api.arkoselabs.com", "client-api.arkoselabs.com",
                "static.captcha-delivery.com", "geo.captcha-delivery.com", "ct.captcha-delivery.com"));
        assertEquals("whole non-PRODUCT surface, including GitHub-owned resources",
                expected, GitHubAllowlist.thirdPartyHosts());
        List<String> serviceHosts = new ArrayList<String>();
        for (AllowEntry entry : GitHubAllowlist.serviceEntries()) {
            assertEquals(AllowEntry.Role.ASSET, entry.role());
            serviceHosts.add(entry.host());
        }
        assertEquals(EXPECTED_SERVICE_HOSTS, serviceHosts);
    }

    @Test
    public void signInAndCaptchaGroupsAreEnabledWithTheReviewedSwitches() {
        assertTrue("sign-in with a Google account is enabled in this build",
                GitHubAllowlist.ENABLE_GOOGLE_SIGN_IN);
        assertTrue("CAPTCHA widgets are enabled in this build", GitHubAllowlist.ENABLE_CAPTCHA_WIDGETS);
    }

    @Test
    public void exactlyOneThirdPartyHostMayEverBecomeAPage() {
        PolicyConfig config = GitHubAllowlist.productionConfig();
        List<String> documentCapableThirdParties = Arrays.asList("accounts.google.com");
        for (AllowEntry entry : GitHubAllowlist.activeEntries()) {
            if (entry.isProduct()) {
                continue;
            }
            boolean documentCapable = !entry.isResourceOnly();
            assertEquals("unexpected document-capable third party: " + entry.host(),
                    documentCapable, documentCapableThirdParties.contains(entry.host()));
            assertEquals("unexpected role for " + entry.host(),
                    documentCapable ? AllowEntry.Role.SIGN_IN : AllowEntry.Role.ASSET, entry.role());
        }
        // ... and the sign-in host is the one the flow actually uses.
        assertTrue(GitHubAllowlist.isSignInHost("accounts.google.com"));
        assertFalse(GitHubAllowlist.isSignInHost("www.google.com"));
        assertFalse(GitHubAllowlist.isSignInHost("github.com"));
        assertTrue(config.isSignInNavigation("https://accounts.google.com/signin/v2/identifier"));
        assertFalse(config.isSignInNavigation("https://github.com/"));
        assertTrue(config.isProductNavigation("https://github.com/markcollege2026-droid"));
        assertFalse(config.isProductNavigation("https://accounts.google.com/signin"));
    }

    @Test
    public void everyThirdPartyAssetEntryIsResourceOnlyAndReviewableAtItsPathScope() {
        // Everything third-party is asset-only: none of these hosts can ever be the app's page.
        for (AllowEntry entry : GitHubAllowlist.activeEntries()) {
            if (entry.isProduct() || entry.isSignIn()) {
                continue;
            }
            assertTrue("third-party entry must be resource only (never a page): " + entry.host(),
                    entry.isResourceOnly());
            for (String prefix : entry.pathPrefixes()) {
                assertTrue("prefix must start with '/': " + prefix, prefix.startsWith("/"));
            }
        }

        // Path restrictions are the rule; the hosts below are the reviewed exceptions, and they are
        // exceptions for one reason: their paths are content-hashed, repository-scoped or otherwise
        // opaque and change without notice. Google serves its identity UI from content-hashed paths
        // that change with every deployment - any prefix list broke sign-in in the Arena build (see
        // TEST_REPORT.md) - and GitHub's asset/content hosts follow the same pattern. Widening this
        // set must be a deliberate edit here as well.
        List<String> deliberatelyUnrestricted = new ArrayList<String>(EXPECTED_SERVICE_HOSTS);
        deliberatelyUnrestricted.remove("images.ctfassets.net");
        deliberatelyUnrestricted.remove("videos.ctfassets.net");
        deliberatelyUnrestricted.addAll(Arrays.asList("ssl.gstatic.com", "www.gstatic.com", "octocaptcha.com", "static.captcha-delivery.com", "ct.captcha-delivery.com"));
        for (AllowEntry entry : GitHubAllowlist.activeEntries()) {
            if (entry.isProduct() || entry.isSignIn() || entry.isPathRestricted()) {
                continue;
            }
            {
                assertTrue("a path-unrestricted third-party host must be a reviewed exception: " + entry.host(),
                        deliberatelyUnrestricted.contains(entry.host()));
                assertTrue("a path-unrestricted host must still be asset-only: " + entry.host(),
                        entry.isResourceOnly());
            }
        }
        for (String host : deliberatelyUnrestricted) {
            assertTrue("the reviewed exception list must match reality: " + host,
                    GitHubAllowlist.thirdPartyHosts().contains(host));
        }
    }

    /**
     * Two capability groups may want the same host, and a host may only appear once in the policy
     * ({@code findByHost} returns the first match), so the merged entry must carry every path. The
     * mechanism is exercised by the synthetic cases below; the Google asset host keeps its wide entry
     * because it was produced by exactly this merge in the Arena build (sign-in assets plus widget
     * bundles).
     */
    @Test
    public void mergedEntriesKeepEveryPathPrefix() {
        PolicyConfig config = GitHubAllowlist.productionConfig();
        List<String> hosts = GitHubAllowlist.allowedHosts();
        assertEquals("a host must never be listed twice: " + hosts,
                hosts.size(), new java.util.HashSet<String>(hosts).size());

        // The Google identity UI lives at content-hashed paths under the same hosts whatever group
        // asked for them: whichever entry is wider must survive the merge, or a flow silently loses
        // its assets - which is exactly the class of bug this whole change is about.
        for (String path : Arrays.asList("/accounts/t.js", "/gsi/c.js",
                "/_/mss/boq-identity/_/js/k=boq-identity.AccountsSignInUi.en.x/y")) {
            assertTrue("merged path must stay reachable: " + path,
                    config.evaluateRequest("https://www.gstatic.com" + path).isAllowed());
        }
        assertTrue("the wider (unrestricted) entry must win the merge",
                config.findByHost("www.gstatic.com").pathPrefixes().isEmpty());

        // The merge itself: two restricted groups union their prefixes, and an unrestricted entry stays
        // unrestricted instead of being narrowed by the other group.
        List<AllowEntry> union = GitHubAllowlist.merge(Arrays.asList(
                Arrays.asList(AllowEntry.resourceOnly("merge.example", Arrays.asList("/a/"), "one")),
                Arrays.asList(AllowEntry.resourceOnly("merge.example", Arrays.asList("/b/"), "two"))));
        assertEquals(Arrays.asList("/a/", "/b/"), union.get(0).pathPrefixes());

        List<AllowEntry> widened = GitHubAllowlist.merge(Arrays.asList(
                Arrays.asList(AllowEntry.resourceOnly("merge.example", Arrays.asList("/a/"), "one")),
                Arrays.asList(AllowEntry.resourceOnly("merge.example",
                        java.util.Collections.<String>emptyList(), "two"))));
        assertTrue("an unrestricted group must not be narrowed by a restricted one",
                widened.get(0).pathPrefixes().isEmpty());
        assertEquals("and the host must not be duplicated", 1, widened.size());
    }

    @Test
    public void capabilityGroupsHaveOnlyTheirReviewedRoles() {
        // The groups themselves describe what would be reachable if enabled; the *active* set is what
        // matters, and it is derived from the switches. Assert the groups are well-formed so that
        // flipping a switch back cannot accidentally enable something unreviewed.
        for (AllowEntry entry : GitHubAllowlist.signInEntries()) {
            assertTrue("sign-in group must be pinned to one document host",
                    entry.isSignIn() || entry.isResourceOnly());
        }
        for (AllowEntry entry : GitHubAllowlist.captchaEntries()) {
            assertTrue("CAPTCHA group must be asset-only", entry.isResourceOnly());
        }
        for (AllowEntry entry : GitHubAllowlist.serviceEntries()) {
            assertTrue("GitHub service group must be asset-only", entry.isResourceOnly());
        }
    }

    @Test
    public void noEntryUsesWildcardsOrBroaderMatches() {
        for (AllowEntry entry : GitHubAllowlist.activeEntries()) {
            String host = entry.host();
            assertFalse("wildcards are not supported: " + host, host.contains("*"));
            assertFalse("wildcards are not supported: " + host, host.contains("?"));
            assertFalse("no leading dots: " + host, host.startsWith("."));
            assertEquals("entries must already be canonical: " + host,
                    host, UrlPolicy.canonicalizeHost(host));
            assertNotNull(entry.purpose());
            assertFalse("every entry must document why it is reachable", entry.purpose().isEmpty());
        }
    }

    @Test
    public void excludedHostsAreNotReachable() {
        String[] excluded = {
                // GitHub properties that are NOT part of the product surface and stay unreachable:
                "docs.github.com",
                "support.github.com",
                "gist.github.com",
                "pages.github.com",
                "raw.github.com",
                "status.github.com",
                "community.github.com",
                "desktop.github.com",
                "cli.github.com",
                "skills.github.com",
                "stars.github.com",
                "marketplace.github.com",
                "npm.pkg.github.com",
                "maven.pkg.github.com",
                "collector.github.com",
                "api.githubcopilot.com",
                "www.githubstatus.com",
                "github.blog",
                "www.github.blog",
                "gh.io",
                "username.github.io",
                "github.io",
                // Look-alike domains:
                "github.co",
                "git-hub.com",
                "githubcom.com",
                "github.com.evil.example",
                "mygithub.com",
                "github.net",
                // Other cloud buckets are NOT implied by the narrow Actions results-storage pattern:
                "unapproved.s3.amazonaws.com",
                "other.blob.core.windows.net",
                "productionresultssa20.blob.core.windows.net",
                // Google hosts that are NOT part of the sign-in flow and must stay unreachable:
                "mail.google.com",
                "drive.google.com",
                "docs.google.com",
                "play.google.com",
                "translate.google.com",
                "myaccount.google.com",
                "oauth2.googleapis.com",
                "www.googleapis.com",
                "googleusercontent.com",
                "lh4.googleusercontent.com",
                "ogs.google.com",
                "accounts.google.com.evil.example",
                // DataDome product surfaces outside the pinned challenge paths:
                "js.datadome.co",
                "api-js.datadome.co",
                "captcha-delivery.com",
        };
        PolicyConfig config = GitHubAllowlist.productionConfig();
        for (String host : excluded) {
            PolicyResult result = config.evaluateNavigation("https://" + host + "/x");
            assertFalse("must not be reachable: " + host, result.isAllowed());
        }
    }


    @Test
    public void pathPinsAndInputFixExceptionsAreExplicit() {
        PolicyConfig config = GitHubAllowlist.productionConfig();
        assertEquals(Arrays.asList("/accounts/CheckConnection"),
                config.findByHost("accounts.youtube.com").pathPrefixes());
        assertFalse(config.evaluateNavigation("https://accounts.youtube.com/accounts/CheckConnection").isAllowed());
        assertFalse(config.evaluateRequest("https://accounts.youtube.com/watch?v=x").isAllowed());
        assertFalse(config.evaluateRequest("https://www.youtube.com/watch?v=x").isAllowed());
        assertEquals(Arrays.asList("/recaptcha/"), config.findByHost("www.google.com").pathPrefixes());
        assertEquals(Arrays.asList("/fc/", "/v2/"), config.findByHost("github-api.arkoselabs.com").pathPrefixes());
        assertEquals(Arrays.asList("/fc/", "/v2/"), config.findByHost("client-api.arkoselabs.com").pathPrefixes());
        assertEquals(Arrays.asList("/captcha/", "/interstitial/"), config.findByHost("geo.captcha-delivery.com").pathPrefixes());
        assertTrue("versioned root-level DataDome bundles must not be repinned",
                config.findByHost("ct.captcha-delivery.com").pathPrefixes().isEmpty());
        for (String host : Arrays.asList("images.ctfassets.net", "videos.ctfassets.net")) {
            assertEquals(Arrays.asList("/8aevphvgewt8/"), config.findByHost(host).pathPrefixes());
            assertFalse(config.evaluateRequest("https://" + host + "/another-space/image.png").isAllowed());
        }
        // The upstream email overlay fix: never narrow hashed identity bundles back to /accounts/.
        for (String host : Arrays.asList("www.gstatic.com", "ssl.gstatic.com")) {
            String bundle = "https://" + host + "/_/mss/boq-identity/_/js/k=boq-identity.AccountsSignInUi.en.hash/x";
            assertTrue(config.evaluateRequest(bundle).isAllowed());
            assertTrue(config.evaluateSubdocument(bundle).isAllowed());
            assertFalse(config.evaluateNavigation(bundle).isAllowed());
        }
        assertFalse(config.evaluateRequest("https://www.google.com/search?q=x").isAllowed());
        assertFalse(config.evaluateRequest("https://github-api.arkoselabs.com/marketing").isAllowed());
        assertFalse(config.evaluateRequest("https://geo.captcha-delivery.com/other").isAllowed());
    }

    @Test
    public void arenaAndUnapprovedProductSitesAreNotReachableAsResourcesEither() {
        PolicyConfig config = GitHubAllowlist.productionConfig();
        for (String host : Arrays.asList("arena.ai", "auth.arena.ai", "cdn.arena.ai", "gist.github.com",
                "example.github.io", "collector.github.com", "api.githubcopilot.com", "appleid.apple.com",
                "company.okta.com", "alive-staging.github.com", "example.s3.amazonaws.com")) {
            assertFalse(host, config.evaluateNavigation("https://" + host + "/").isAllowed());
            assertFalse(host, config.evaluateSubdocument("https://" + host + "/").isAllowed());
            assertFalse(host, config.evaluateRequest("https://" + host + "/").isAllowed());
            assertFalse(host, config.evaluateTunnel(host, 443).isAllowed());
        }
    }

    @Test
    public void describeIsStableAndReadable() {
        String description = GitHubAllowlist.productionConfig().describe();
        assertTrue(description.contains("start=https://github.com/"));
        for (String host : EXPECTED_FIRST_PARTY_HOSTS) {
            assertTrue("description must mention " + host, description.contains("allow " + host));
        }
        assertTrue("description must mention the results storage pattern",
                description.contains("productionresultssa<digits>.blob.core.windows.net"));
    }

    // -----------------------------------------------------------------------------------------
    // GitHub Actions results storage pattern (productionresultssaN.blob.core.windows.net)
    // -----------------------------------------------------------------------------------------

    @Test
    public void resultsStoragePatternIsTheSingleResourceOnlyPattern() {
        java.util.List<HostPatternEntry> patterns = GitHubAllowlist.resourceHostPatterns();
        assertEquals(1, patterns.size());
        HostPatternEntry pattern = patterns.get(0);
        assertEquals("productionresultssa<digits>.blob.core.windows.net", pattern.description());
        assertTrue(pattern.isResourceOnly());
        assertEquals(AllowEntry.Role.ASSET, pattern.role());
        assertTrue("the pattern must be path-unrestricted like the exact entries it replaces",
                pattern.pathPrefixes().isEmpty());
        assertFalse(pattern.purpose().isEmpty());
        // The production policy carries exactly this pattern, and no exact entry duplicates it.
        PolicyConfig config = GitHubAllowlist.productionConfig();
        assertEquals(patterns, config.hostPatterns());
        for (AllowEntry entry : config.entries()) {
            assertFalse("no exact entry may duplicate the pattern host family: " + entry.host(),
                    entry.host().startsWith("productionresultssa"));
        }
    }

    @Test
    public void resultsStoragePatternAcceptsEveryNumericEndpointAsResourceOnly() {
        PolicyConfig config = GitHubAllowlist.productionConfig();
        String[] accepted = {
                "productionresultssa0.blob.core.windows.net",
                "productionresultssa19.blob.core.windows.net",
                "productionresultssa30.blob.core.windows.net",
                "productionresultssa49.blob.core.windows.net",
                "productionresultssa100.blob.core.windows.net",
                "productionresultssa199.blob.core.windows.net",
                "productionresultssa200.blob.core.windows.net",
                "productionresultssa9999.blob.core.windows.net",
        };
        for (String host : accepted) {
            String url = "https://" + host + "/actions-results/artifact.zip?sig=x";
            assertTrue(host + " must be a results storage host", GitHubAllowlist.isProductionResultsHost(host));
            assertTrue(host + " as sub-resource", config.evaluateRequest(url).isAllowed());
            assertTrue(host + " as embedded document", config.evaluateSubdocument(url).isAllowed());
            assertTrue(host + " as tunnel", config.evaluateTunnel(host, 443).isAllowed());
            PolicyResult navigation = config.evaluateNavigation(url);
            assertFalse(host + " must never become a page", navigation.isAllowed());
            assertEquals(host, BlockReason.DOCUMENT_NOT_ALLOWED, navigation.reason());
            assertFalse(host + " must not be a product page", config.isProductNavigation(url));
            assertFalse(host + " must not be a sign-in page", config.isSignInNavigation(url));
            AllowEntry matched = config.findByHost(host);
            assertNotNull(host, matched);
            assertTrue(host + " matched entry must be resource-only", matched.isResourceOnly());
            assertEquals(host, matched.host());
        }
    }

    @Test
    public void resultsStoragePatternMatchesCaseInsensitively() {
        PolicyConfig config = GitHubAllowlist.productionConfig();
        assertTrue(config.evaluateRequest("https://PRODUCTIONRESULTSSA30.BLOB.CORE.WINDOWS.NET/x").isAllowed());
        assertTrue(GitHubAllowlist.isProductionResultsHost("PRODUCTIONRESULTSSA30.BLOB.CORE.WINDOWS.NET"));
        PolicyResult navigation = config.evaluateNavigation(
                "https://PRODUCTIONRESULTSSA30.BLOB.CORE.WINDOWS.NET/x");
        assertFalse(navigation.isAllowed());
        assertEquals(BlockReason.DOCUMENT_NOT_ALLOWED, navigation.reason());
    }

    @Test
    public void resultsStoragePatternRejectsLookAlikes() {
        PolicyConfig config = GitHubAllowlist.productionConfig();
        String[] rejected = {
                "productionresultssa30.blob.core.windows.net.evil.example",
                "evilproductionresultssa30.blob.core.windows.net",
                "productionresultssaX.blob.core.windows.net",
                "productionresultssa.blob.core.windows.net",
                "productionresultssa30.blob.core.windows.net.",
                "unrelated.blob.core.windows.net",
                "productionresultssa30.blob.core.windows.net.evil.com",
                "productionresultssa-30.blob.core.windows.net",
                "productionresultssa30a.blob.core.windows.net",
                "productionresultssa30.blob.core.windows.netx",
                "productionresultssa30.blob.core.windows.ne",
                "productionresultssa30.blob.core.windows.net:8443",
        };
        for (String host : rejected) {
            assertFalse(host, GitHubAllowlist.isProductionResultsHost(host));
            String url = "https://" + host + "/x";
            assertFalse(host + " as sub-resource", config.evaluateRequest(url).isAllowed());
            assertFalse(host + " as embedded document", config.evaluateSubdocument(url).isAllowed());
            assertFalse(host + " as navigation", config.evaluateNavigation(url).isAllowed());
            // Tunnel check uses the bare host without a port suffix.
            String bareHost = host.contains(":") ? host.substring(0, host.indexOf(':')) : host;
            assertFalse(host + " as tunnel", config.evaluateTunnel(bareHost, 443).isAllowed());
        }
        // The exact rejected reasons for the four cases named in the task.
        assertEquals(BlockReason.HOST_NOT_ALLOWED,
                config.evaluateRequest("https://productionresultssa30.blob.core.windows.net.evil.example/x").reason());
        assertEquals(BlockReason.HOST_NOT_ALLOWED,
                config.evaluateRequest("https://evilproductionresultssa30.blob.core.windows.net/x").reason());
        assertEquals(BlockReason.HOST_NOT_ALLOWED,
                config.evaluateRequest("https://productionresultssaX.blob.core.windows.net/x").reason());
        assertEquals(BlockReason.HOST_NOT_ALLOWED,
                config.evaluateRequest("https://unrelated.blob.core.windows.net/x").reason());
    }

    @Test
    public void resultsStoragePatternIsNotABroadAzurePermission() {
        PolicyConfig config = GitHubAllowlist.productionConfig();
        for (String host : new String[]{
                "blob.core.windows.net",
                "core.windows.net",
                "windows.net",
                "productionresultssa30.blob.core.windows.net.evil.example",
                "other.blob.core.windows.net",
                "productionresults.blob.core.windows.net",
                "productionresultssa30.blob.core.windows.net.attacker.example"}) {
            assertFalse(host, config.evaluateRequest("https://" + host + "/x").isAllowed());
            assertFalse(host, config.evaluateTunnel(host, 443).isAllowed());
        }
    }
}
