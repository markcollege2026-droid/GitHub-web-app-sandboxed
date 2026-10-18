package app.arenawebview.locked.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * The tunnel decision is the one the egress proxy makes for every connection the WebView opens, including
 * WebSockets. It has to be at least as strict as the navigation decision on the host, and stricter on the
 * port: there is exactly one, 443, and no path dimension because a tunnel has no path.
 */
public class TunnelPolicyTest {

    private static final PolicyConfig POLICY = GitHubAllowlist.productionConfig();

    @Test
    public void everyAllowListedHostMayBeTunnelledOnPort443() {
        for (AllowEntry entry : GitHubAllowlist.activeEntries()) {
            PolicyResult result = POLICY.evaluateTunnel(entry.host(), 443);
            assertTrue(entry.host() + " -> " + result.describe(), result.isAllowed());
            assertEquals(entry.host(), result.canonicalHost());
            assertEquals(entry.host(), result.matchedEntry().host());
            assertEquals("connect://" + entry.host() + ":443", result.rawUrl());
        }
    }

    @Test
    public void onlyPort443IsEverAllowed() {
        int[] ports = {1, 80, 8080, 8443, 442, 444, 4443, 65535, 0, -1, 70000};
        for (int port : ports) {
            PolicyResult result = POLICY.evaluateTunnel("github.com", port);
            assertFalse("github.com:" + port, result.isAllowed());
            assertEquals("github.com:" + port, BlockReason.PORT_NOT_ALLOWED, result.reason());
            assertEquals("github.com", result.canonicalHost());
        }
    }

    @Test
    public void hostIsLowerCasedAndNothingElse() {
        assertTrue(POLICY.evaluateTunnel("GITHUB.COM", 443).isAllowed());
        assertEquals("github.com", POLICY.evaluateTunnel("GITHUB.COM", 443).canonicalHost());
        assertEquals(BlockReason.INVALID_HOST, POLICY.evaluateTunnel("github.com.", 443).reason());
        assertEquals(BlockReason.INVALID_HOST, POLICY.evaluateTunnel(" github.com", 443).reason());
        assertEquals(BlockReason.INVALID_HOST, POLICY.evaluateTunnel("github.com ", 443).reason());
        assertEquals(BlockReason.INVALID_HOST, POLICY.evaluateTunnel("github.com/", 443).reason());
        assertEquals(BlockReason.INVALID_HOST, POLICY.evaluateTunnel("github.com:443", 443).reason());
        assertEquals(BlockReason.INVALID_HOST, POLICY.evaluateTunnel("github%2ecom", 443).reason());
        assertEquals(BlockReason.INVALID_HOST, POLICY.evaluateTunnel("github.com\r\nX: y", 443).reason());
    }

    @Test
    public void hostsOutsideTheListAreRefusedWhateverTheirShape() {
        List<String> hosts = Arrays.asList(
                "evil.example", "github.com.evil.example", "evilgithub.com", "github.com-evil.example",
                "www.github.com.evil.example", "xn--gthub-5va.example", "google.com", "google.co.il",
                "mail.google.com", "accounts.google.com.evil.example", "www.googleapis.com",
                "oauth2.googleapis.com", "gstatic.com", "recaptcha.net", "fonts.googleapis.com",
                "cdn.github.com.example", "github.com.localhost", "github.com.local");
        for (String host : hosts) {
            PolicyResult result = POLICY.evaluateTunnel(host, 443);
            assertFalse(host, result.isAllowed());
            assertEquals(host, BlockReason.HOST_NOT_ALLOWED, result.reason());
        }
    }

    @Test
    public void addressesAndLocalNamesAreNeverTunnelled() {
        assertEquals(BlockReason.IP_LITERAL_HOST, POLICY.evaluateTunnel("[::1]", 443).reason());
        assertEquals(BlockReason.IP_LITERAL_HOST, POLICY.evaluateTunnel("[2606:4700::1]", 443).reason());
        assertEquals(BlockReason.IP_LITERAL_HOST, POLICY.evaluateTunnel("github.com]", 443).reason());
        assertEquals(BlockReason.INVALID_HOST, POLICY.evaluateTunnel("127.0.0.1", 443).reason());
        assertEquals(BlockReason.INVALID_HOST, POLICY.evaluateTunnel("10.0.0.1", 443).reason());
        assertEquals(BlockReason.INVALID_HOST, POLICY.evaluateTunnel("0x7f000001", 443).reason());
        assertEquals(BlockReason.INVALID_HOST, POLICY.evaluateTunnel("2130706433", 443).reason());
        assertEquals(BlockReason.INVALID_HOST, POLICY.evaluateTunnel("localhost", 443).reason());
        assertEquals(BlockReason.INVALID_HOST, POLICY.evaluateTunnel("metadata", 443).reason());
        assertEquals(BlockReason.HOST_NOT_ALLOWED, POLICY.evaluateTunnel("metadata.google.internal", 443).reason());
    }

    @Test
    public void userInfoAndNonAsciiAreRefusedBeforeAnyLookup() {
        assertEquals(BlockReason.USERINFO_PRESENT, POLICY.evaluateTunnel("github.com@evil.example", 443).reason());
        assertEquals(BlockReason.USERINFO_PRESENT, POLICY.evaluateTunnel("user:pass@github.com", 443).reason());
        assertEquals(BlockReason.NON_ASCII_HOST, POLICY.evaluateTunnel("\u0430github.com", 443).reason());
        assertEquals(BlockReason.NON_ASCII_HOST, POLICY.evaluateTunnel("github.com\u3002", 443).reason());
        assertEquals(BlockReason.NON_ASCII_HOST, POLICY.evaluateTunnel("\uff41github.com", 443).reason());
    }

    @Test
    public void emptyAndNullHostsAreRefused() {
        assertEquals(BlockReason.EMPTY_HOST, POLICY.evaluateTunnel("", 443).reason());
        assertEquals(BlockReason.EMPTY_HOST, POLICY.evaluateTunnel(null, 443).reason());
        assertEquals("connect://:443", POLICY.evaluateTunnel(null, 443).rawUrl());
        PolicyResult noConfig = UrlPolicy.evaluateTunnel("github.com", 443, null);
        assertFalse(noConfig.isAllowed());
        assertEquals(BlockReason.INVALID_HOST, noConfig.reason());
    }

    @Test
    public void refusedResultsCarryEnoughForTheDiagnosticsReport() {
        PolicyResult result = POLICY.evaluateTunnel("evil.example", 443);
        assertEquals("evil.example", result.canonicalHost());
        assertEquals("connect://evil.example:443", result.rawUrl());
        assertNotNull(result.describe());
        assertTrue(result.describe().contains("HOST_NOT_ALLOWED"));
    }

    /**
     * On every URL in the shared case table, the tunnel decision for the URL's host is never looser than
     * the navigation decision: whenever the tunnel would refuse the host, the navigation refuses the URL;
     * and whenever the navigation refuses for a host reason, so does the tunnel.
     */
    @Test
    public void tunnelDecisionIsNeverLooserThanTheNavigationDecisionOnTheHost() {
        List<PolicyCases.Case> cases = new java.util.ArrayList<PolicyCases.Case>(PolicyCases.all());
        cases.addAll(PolicyCases.withGitHubResourcesEnabled());
        int compared = 0;
        for (PolicyCases.Case c : cases) {
            String host = hostOf(c.url);
            if (host == null) {
                continue;
            }
            PolicyResult navigation = POLICY.evaluateNavigation(c.url);
            PolicyResult tunnel = POLICY.evaluateTunnel(host, 443);
            compared++;
            if (!tunnel.isAllowed()) {
                assertFalse("tunnel refused " + host + " but navigation allowed " + c.url, navigation.isAllowed());
            } else if (!navigation.isAllowed()) {
                BlockReason reason = navigation.reason();
                assertTrue(c.url + " -> " + navigation.describe() + " but the tunnel allowed " + host,
                        reason == BlockReason.PORT_NOT_ALLOWED || reason.category() != BlockReason.Category.HOST);
            }
        }
        assertTrue("the case table should give plenty of comparisons, got " + compared, compared > 100);
    }

    /**
     * A naive host extraction for the table rows: enough to compare decisions, deliberately not the policy's.
     * Tabs and newlines are removed first because that is what the browser does before it connects, so the
     * host the proxy sees never contains them.
     */
    private static String hostOf(String raw) {
        if (raw == null) {
            return null;
        }
        String url = raw.replace("\t", "").replace("\n", "").replace("\r", "");
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return null;
        }
        String rest = url.substring(scheme + 3);
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#' || c == '\\') {
                end = i;
                break;
            }
        }
        String authority = rest.substring(0, end);
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        if (authority.startsWith("[")) {
            return authority;
        }
        int colon = authority.lastIndexOf(':');
        return colon >= 0 ? authority.substring(0, colon) : authority;
    }
}
