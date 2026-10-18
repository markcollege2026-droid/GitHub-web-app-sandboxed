package app.arenawebview.locked.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Verifies the URL policy against the case table in {@link PolicyCases}.
 *
 * <p>Every row is a claim about behaviour, so this test is the evidence for statements like "redirects
 * to a third party are refused" or "a Cyrillic 'а' look-alike is refused": if the implementation and the
 * table ever disagree, the build fails.</p>
 *
 * <p>The shipping policy is pinned by {@link PolicyCases#all()} and the explicitly approved in-page
 * resource-host cases in {@link PolicyCases#withGitHubResourcesEnabled()}.</p>
 */
@RunWith(Parameterized.class)
public class UrlPolicyTest {

    private static final PolicyConfig PRODUCTION = GitHubAllowlist.productionConfig();

    private final PolicyCases.Case testCase;

    public UrlPolicyTest(PolicyCases.Case testCase) {
        this.testCase = testCase;
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> parameters() {
        List<Object[]> rows = new ArrayList<Object[]>();
        for (PolicyCases.Case testCase : PolicyCases.all()) {
            rows.add(new Object[]{testCase});
        }
        for (PolicyCases.Case testCase : PolicyCases.withGitHubResourcesEnabled()) {
            rows.add(new Object[]{testCase});
        }
        return rows;
    }

    @Test
    public void matchesExpectedOutcome() {
        PolicyResult result = UrlPolicy.evaluate(testCase.url, PRODUCTION, testCase.trafficClass);
        assertNotNull("policy must always return a result", result);
        if (testCase.allowed) {
            assertTrue(testCase.name + ": expected ALLOW for " + testCase.url + " but got "
                    + result.describe(), result.isAllowed());
        } else {
            assertFalse(testCase.name + ": expected BLOCK for " + testCase.url
                    + " but the policy allowed it", result.isAllowed());
            assertEquals(testCase.name + ": wrong reason for " + testCase.url,
                    testCase.reason, result.reason());
        }
    }

    /** A block must always carry a reason: the app shows it and the report records it. */
    @Test
    public void everyRefusalCarriesAReason() {
        if (testCase.allowed) {
            return;
        }
        PolicyResult result = UrlPolicy.evaluate(testCase.url, PRODUCTION, testCase.trafficClass);
        assertNotNull(result.reason());
        assertNotNull(result.reason().humanMessage());
        assertNotNull(result.reason().category());
        assertFalse(result.reason().humanMessage().isEmpty());
    }

    /** Allowed navigations must report the canonical host they resolved to. */
    @Test
    public void allowedDocumentReportsItsHost() {
        if (!testCase.allowed || testCase.trafficClass != UrlPolicy.TrafficClass.DOCUMENT) {
            return;
        }
        PolicyResult result = UrlPolicy.evaluate(testCase.url, PRODUCTION, testCase.trafficClass);
        if (testCase.url != null && testCase.url.startsWith("https")) {
            assertTrue("host must be canonical and lower case: " + result.canonicalHost(),
                    result.canonicalHost().equals(result.canonicalHost().toLowerCase(java.util.Locale.ROOT)));
            assertTrue("host must be on the allow-list: " + result.canonicalHost(),
                    GitHubAllowlist.allowedHosts().contains(result.canonicalHost()));
            assertNotNull(result.matchedEntry());
        }
    }

    /**
     * The general fail-closed property, independent of the table: a hostile or unverifiable URL must
     * never be allowed, for either traffic class.
     */
    @Test
    public void hostileUrlsAreNeverAllowed() {
        String[] hostile = {
                "https://evil.example/",
                "http://github.com/",
                "//github.com/",
                "javascript:alert(1)",
                "intent://x/#Intent;end",
                "file:///data/data/app.arenawebview.locked/databases/webview.db",
                "https://github.com@evil.example/",
                "https://github.com.evil.example/",
                "https://\u0430github.com/",
                "https://github.com%2e.evil.example/",
                "https://evil.example\\@github.com/",
                "https://github.com:80443/",
                "https://127.0.0.1/",
                "https://[::1]/",
                "https://sub.github.com/",
                "https://mail.github.com/",
                "https://xn--gthub-5va.example/",
                "data:text/html,<script>1</script>",
                "blob:https://evil.example/x",
        };
        for (String url : hostile) {
            for (UrlPolicy.TrafficClass trafficClass : UrlPolicy.TrafficClass.values()) {
                if (trafficClass == UrlPolicy.TrafficClass.SUBRESOURCE && url.startsWith("data:")) {
                    // data: is inert and is deliberately allowed for sub-resources; it is refused as a
                    // document, which the table asserts separately.
                    continue;
                }
                PolicyResult result = UrlPolicy.evaluate(url, PRODUCTION, trafficClass);
                assertFalse("must never be allowed (" + trafficClass + "): " + url, result.isAllowed());
            }
        }
    }

    /** Resource hosts can render in-page but can never replace the GitHub page. */
    @Test
    public void resourceHostsAreNeverReachableAsTopLevelDocuments() {
        for (AllowEntry entry : GitHubAllowlist.resourceEntries()) {
            String url = "https://" + entry.host() + (entry.pathPrefixes().isEmpty()
                    ? "/resource" : entry.pathPrefixes().get(0) + "resource");
            assertTrue("resource role should allow an embedded document: " + url,
                    PRODUCTION.evaluateSubdocument(url).isAllowed());
            assertFalse("resource role must never become a page: " + url,
                    PRODUCTION.evaluateNavigation(url).isAllowed());
            assertEquals(BlockReason.DOCUMENT_NOT_ALLOWED, PRODUCTION.evaluateNavigation(url).reason());
        }
        assertTrue("GitHub login remains a first-party page",
                PRODUCTION.evaluateNavigation("https://github.com/login").isAllowed());
        assertFalse("external identity hand-offs remain blocked unless explicitly reviewed",
                PRODUCTION.evaluateNavigation("https://accounts.google.com/signin").isAllowed());
    }

    @Test
    public void missingPolicyFailsClosed() {
        assertFalse(UrlPolicy.evaluate("https://github.com/", null, UrlPolicy.TrafficClass.DOCUMENT).isAllowed());
    }

    /** Host canonicalisation is exposed for the allow-list itself; check the strict rules directly. */
    @Test
    public void hostCanonicalisationIsStrict() {
        assertEquals("github.com", UrlPolicy.canonicalizeHost("GITHUB.COM"));
        assertEquals("www.github.com", UrlPolicy.canonicalizeHost("www.github.com"));

        String[] invalid = {
                "", ".", "..", "github", "github.", "github..com", ".github.com", "-github.com", "github.com-",
                "github.com%2e", "github_ai.example", "127.0.0.1", "2130706433", "0x7f.0.0.1",
                "\u0430github.com", "github\u3002com", "github.com ", " github.com", "github.com:443", "[::1]",
                "github.1", "github.a",
                // A trailing dot is a different browser origin, not a spelling of the same host.
                "github.com.", "GITHUB.COM.", "www.github.com.", "github.com..",
        };
        for (String host : invalid) {
            org.junit.Assert.assertNull("must be rejected: '" + host + "'",
                    UrlPolicy.canonicalizeHost(host));
        }
        // A punycode label canonicalises (it is valid ASCII DNS), but it can never match an allow-list
        // entry, which is why it is refused by the policy rather than by the canonicaliser.
        assertEquals("xn--gthub-5va.example", UrlPolicy.canonicalizeHost("xn--gthub-5va.example"));
    }

    /** Sanity check that the table is actually being exercised. */
    @Test
    public void tableIsNotEmpty() {
        assertTrue(PolicyCases.all().size() > 100);
    }
}
