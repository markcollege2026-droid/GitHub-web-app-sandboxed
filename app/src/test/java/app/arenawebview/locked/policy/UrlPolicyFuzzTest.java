package app.arenawebview.locked.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Property-based check of the URL policy on tens of thousands of <em>mutated</em> URLs.
 *
 * <p>The table in {@link PolicyCases} says what the policy must do for the inputs a reviewer thought of.
 * This test is for the inputs nobody thought of: it takes every URL the table knows about, splices in the
 * tokens that URL parsers are known to disagree on (backslashes, {@code @}, tabs, {@code %2e}, double
 * ports, trailing dots, fullwidth letters, embedded schemes, ...) and checks invariants that must hold
 * whatever the input looks like:</p>
 * <ol>
 *   <li>the policy never throws - every call site is a WebView callback, and an exception there kills the
 *       app, which is the one failure mode that is <em>not</em> fail-closed;</li>
 *   <li>when it allows a network URL, the host it reports is on the allow-list, the host really is the
 *       text between {@code https://} and the first delimiter, the entry's path pins hold, and the raw
 *       text contains no delimiter that a browser could read differently ({@code @}, backslash, control
 *       characters, a second colon in the authority);</li>
 *   <li>when it allows a page, the host is a product or sign-in host - never an asset host;</li>
 *   <li>the only non-https strings it ever allows are the few inert in-page forms the design permits;</li>
 *   <li>the answer is deterministic and case-insensitive in the scheme and host only.</li>
 * </ol>
 *
 * <p>The generator is seeded, so a failure reproduces exactly; the seed and the offending input are part of
 * the failure message. Keep the mutation list in sync with the fuzz section of TEST_REPORT.md.</p>
 */
public class UrlPolicyFuzzTest {

    private static final long SEED = 7L;
    private static final int ITERATIONS = 40_000;

    private static final PolicyConfig CONFIG = GitHubAllowlist.productionConfig();

    /** Strings that have no business inside a URL that the policy allows. */
    private static final String[] INJECTIONS = {
            "@", "@evil.example", "evil.example@", "\\", "\\\\", "/\\", "\\.", "\t", "\n", "\r", " ",
            "\u0000", "\u001f", "\u007f", "%00", "%0a", "%2e", "%2f", "%40", "%5c", "%3a", "%23",
            ".", "..", "./", "../", "/..", "/../", "/./", "//", ":", "::", ":443", ":80", ":8443",
            ":443:443", ":0443", ":44", "#", "?", "?@", "#@", "?//evil.example", "#//evil.example",
            ".evil.example", "evil.example.", "-", "_", "github.com.", ".github.com", "xn--",
            "\u0430", "\uff41", "\u3002", "\u00ad", "\u200b", "\ufeff",
            "javascript:", "data:", "blob:", "file:", "about:", "intent:", "https:", "http:",
            "HTTPS://", "hTTp://", "//", "///", "////", "\\\\evil.example", "/\\evil.example",
            "[::1]", "[", "]", "127.0.0.1", "0x7f.0.0.1", "2130706433", "localhost",
            "http://evil.example/", "https://evil.example/", "https://github.com.evil.example/",
    };

    private static final String[] SCHEME_FLIPS = {
            "https", "HTTPS", "Https", "http", "ftp", "javascript", "data", "blob", "file", "about",
            "intent", "content", "ws", "wss", "github", "https\t", "https ", " https", "https\\", "hxxps",
    };

    @Test
    public void mutatedUrlsNeverThrowAndNeverWidenThePolicy() {
        List<String> seeds = seedUrls(false);
        Set<String> allowedHosts = new HashSet<String>(GitHubAllowlist.allowedHosts());

        Random random = new Random(SEED);
        int allowed = 0;
        int inert = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            String url = mutate(seeds.get(random.nextInt(seeds.size())), random);
            for (UrlPolicy.TrafficClass trafficClass : UrlPolicy.TrafficClass.values()) {
                PolicyResult result;
                try {
                    result = UrlPolicy.evaluate(url, CONFIG, trafficClass);
                } catch (RuntimeException e) {
                    throw new AssertionError("policy threw for " + trafficClass + " " + visible(url)
                            + " (seed " + SEED + ", iteration " + i + ")", e);
                }
                assertNotNull(result);
                if (!result.isAllowed()) {
                    assertNotNull("a refusal must carry a reason: " + visible(url), result.reason());
                    assertFalse("a refusal must not look like a match: " + visible(url),
                            result.matchedEntry() != null && result.reason() == null);
                    continue;
                }
                allowed++;
                if (isInertInPage(url)) {
                    inert++;
                    assertTrue("inert forms are only for in-page traffic: " + visible(url),
                            trafficClass != UrlPolicy.TrafficClass.DOCUMENT
                                    || whatwgTrim(url).equalsIgnoreCase("about:blank"));
                    continue;
                }
                checkAllowedNetworkUrl(url, trafficClass, result, allowedHosts, i);
            }
        }
        // Guard against a generator that degenerates into producing only garbage: a meaningful share of
        // the mutations must still be accepted, or the invariants were never really tested.
        assertTrue("too few mutated URLs were allowed (" + allowed + "); generator or policy regressed",
                allowed > ITERATIONS / 20);
        assertTrue("inert in-page forms should appear in the sample", inert > 0);
    }

    /**
     * A missing traffic class is a caller bug, but the answer must still be a refusal, not an exception:
     * every call site is a WebView callback, and an exception there kills the app.
     */
    @Test
    public void missingTrafficClassFailsClosedWithoutThrowing() {
        for (String url : new String[]{"https://github.com/", "about:blank", "about:srcdoc",
                "blob:https://github.com/x", "data:text/html,x", null, "", "javascript:alert(1)"}) {
            PolicyResult result = UrlPolicy.evaluate(url, CONFIG, null);
            assertFalse("must refuse " + visible(url), result.isAllowed());
            assertNotNull(result.reason());
        }
        assertFalse(UrlPolicy.evaluate("https://github.com/", null, null).isAllowed());
    }

    @Test
    public void verdictsAreDeterministicAndIndependentOfSchemeAndHostCase() {
        Random random = new Random(SEED + 1);
        List<String> seeds = seedUrls(true);
        for (int i = 0; i < 5_000; i++) {
            String url = mutate(seeds.get(random.nextInt(seeds.size())), random);
            for (UrlPolicy.TrafficClass trafficClass : UrlPolicy.TrafficClass.values()) {
                PolicyResult first = UrlPolicy.evaluate(url, CONFIG, trafficClass);
                PolicyResult second = UrlPolicy.evaluate(url, CONFIG, trafficClass);
                assertEquals(visible(url), first.isAllowed(), second.isAllowed());
                assertEquals(visible(url), first.reason(), second.reason());

                // Upper-casing the whole string changes the scheme and host (case-insensitive in every
                // browser) but also the path, which is case-sensitive - so only the *verdict for an
                // allowed URL with no path pins* must survive. Restrict to that.
                if (first.isAllowed() && first.matchedEntry() != null
                        && first.matchedEntry().pathPrefixes().isEmpty()) {
                    PolicyResult upper = UrlPolicy.evaluate(url.toUpperCase(Locale.ROOT), CONFIG, trafficClass);
                    assertTrue("case of scheme/host must not matter: " + visible(url), upper.isAllowed());
                    assertEquals(first.canonicalHost(), upper.canonicalHost());
                }
            }
        }
    }

    @Test
    public void everyAllowListedHostIsReachableUnderMutationOnlyInItsOwnShape() {
        // For each entry, the plain https URL must be allowed in the class the entry's role permits, and
        // the same URL with a trailing dot, a userinfo prefix or an http scheme must be refused. This is
        // the generator-free sanity check that the invariants above are not vacuous.
        for (AllowEntry entry : CONFIG.entries()) {
            String root = entry.pathPrefixes().isEmpty() ? "/" : entry.pathPrefixes().get(0);
            String url = "https://" + entry.host() + root;
            assertTrue(url, CONFIG.evaluateRequest(url).isAllowed());
            assertEquals(url, !entry.isResourceOnly(), CONFIG.evaluateNavigation(url).isAllowed());
            assertTrue(url, CONFIG.evaluateSubdocument(url).isAllowed());

            for (String bad : new String[]{
                    "https://" + entry.host() + "." + root,
                    "https://" + entry.host() + "@evil.example" + root,
                    "https://evil.example@" + entry.host() + root,
                    "http://" + entry.host() + root,
                    "https://" + entry.host() + ":8443" + root,
                    "https://" + entry.host() + "\\@evil.example" + root,
                    "https://" + entry.host() + ".evil.example" + root,
                    "https://evil" + entry.host() + root,
                    "https:\\\\" + entry.host() + root,
            }) {
                assertFalse(bad, CONFIG.evaluateRequest(bad).isAllowed());
                assertFalse(bad, CONFIG.evaluateNavigation(bad).isAllowed());
                assertFalse(bad, CONFIG.evaluateSubdocument(bad).isAllowed());
            }
        }
    }

    // --- invariants -----------------------------------------------------------------------------------

    private static void checkAllowedNetworkUrl(String url, UrlPolicy.TrafficClass trafficClass,
                                               PolicyResult result, Set<String> allowedHosts, int iteration) {
        String where = " for " + trafficClass + " " + visible(url) + " (seed " + SEED + ", iteration "
                + iteration + ")";
        AllowEntry entry = result.matchedEntry();
        assertNotNull("an allowed network URL must name its entry" + where, entry);
        assertTrue("reported host must be allow-listed" + where, allowedHosts.contains(result.canonicalHost()));
        assertEquals("entry and reported host must agree" + where, entry.host(), result.canonicalHost());

        // The WHATWG parser (and therefore Chromium) deletes ASCII tab/newline anywhere and strips leading
        // and trailing C0 controls and spaces before it looks at a URL. The policy mirrors exactly that
        // and nothing more, so the browser view of the string is re-derived here the same way; any
        // *other* control character that survives is checked below.
        String browserView = whatwgTrim(url);
        String body;
        if (browserView.regionMatches(true, 0, "blob:", 0, 5)) {
            assertEquals("blobs are sub-resources only" + where, UrlPolicy.TrafficClass.SUBRESOURCE, trafficClass);
            body = browserView.substring(5);
        } else {
            body = browserView;
        }
        assertTrue("only https may be allowed" + where, body.regionMatches(true, 0, "https://", 0, 8));

        // Re-derive the host the way a browser would: everything up to the first '/', '?', '#' - and
        // verify that nothing in that span could make a browser disagree with the policy.
        String afterScheme = body.substring(8);
        int end = afterScheme.length();
        for (int k = 0; k < afterScheme.length(); k++) {
            char c = afterScheme.charAt(k);
            if (c == '/' || c == '?' || c == '#') {
                end = k;
                break;
            }
        }
        String authority = afterScheme.substring(0, end);
        assertTrue("authority must not contain userinfo" + where, authority.indexOf('@') < 0);
        assertTrue("authority must not contain a backslash" + where, authority.indexOf('\\') < 0);
        assertTrue("authority must not contain brackets" + where,
                authority.indexOf('[') < 0 && authority.indexOf(']') < 0);
        for (int k = 0; k < browserView.length(); k++) {
            char c = browserView.charAt(k);
            // A space in a path or query is merely percent-encoded by the browser and cannot move the
            // host, so only true control characters are banned outright; a space in the *authority* is
            // caught by the host comparison below.
            assertFalse("allowed URL must not contain control characters" + where, c < 0x20 || c == 0x7f);
        }
        String hostPart = authority;
        int colon = authority.indexOf(':');
        if (colon >= 0) {
            hostPart = authority.substring(0, colon);
            String port = authority.substring(colon + 1);
            assertTrue("only the default port may be allowed" + where, port.equals("443"));
        }
        assertEquals("host must be exactly what a browser would see" + where,
                result.canonicalHost(), hostPart.toLowerCase(Locale.ROOT));
        assertFalse("a trailing-dot host is a different origin and must not be allowed" + where,
                hostPart.endsWith("."));

        if (trafficClass == UrlPolicy.TrafficClass.DOCUMENT) {
            assertFalse("an asset host may never become the page" + where, entry.isResourceOnly());
        }

        if (!entry.pathPrefixes().isEmpty() && !browserView.regionMatches(true, 0, "blob:", 0, 5)) {
            String path = afterScheme.substring(end);
            int cut = path.length();
            for (int k = 0; k < path.length(); k++) {
                char c = path.charAt(k);
                if (c == '?' || c == '#') {
                    cut = k;
                    break;
                }
            }
            path = path.substring(0, cut);
            boolean pinned = false;
            for (String prefix : entry.pathPrefixes()) {
                String bare = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
                if (path.startsWith(prefix) || path.equals(bare)) {
                    pinned = true;
                }
            }
            assertTrue("path pins must hold" + where, pinned);
            // Only a ".." segment can climb out of a pinned prefix once the browser normalises the path
            // (a "." segment is removed in place and cannot), and a percent-escape could hide either.
            assertFalse("no '..' inside a pinned path" + where, path.contains(".."));
            assertFalse("no percent-escapes inside a pinned path" + where, path.indexOf('%') >= 0);
        }
    }

    private static boolean isInertInPage(String url) {
        String lower = whatwgTrim(url).toLowerCase(Locale.ROOT);
        return lower.startsWith("about:") || lower.startsWith("data:");
    }

    /** The WHATWG pre-parse: delete ASCII tab and newline anywhere, strip leading/trailing C0 and space. */
    private static String whatwgTrim(String url) {
        StringBuilder sb = new StringBuilder(url.length());
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c != '\t' && c != '\n' && c != '\r') {
                sb.append(c);
            }
        }
        int start = 0;
        int end = sb.length();
        while (start < end && sb.charAt(start) <= 0x20) {
            start++;
        }
        while (end > start && sb.charAt(end - 1) <= 0x20) {
            end--;
        }
        return sb.substring(start, end);
    }

    // --- generator ------------------------------------------------------------------------------------

    /** Every URL the table knows about - the base rows and the GitHub resource-host rows this build enables. */
    private static List<String> seedUrls(boolean httpsOnly) {
        List<PolicyCases.Case> table = new ArrayList<PolicyCases.Case>(PolicyCases.all());
        table.addAll(PolicyCases.withGitHubResourcesEnabled());
        List<String> seeds = new ArrayList<String>();
        for (PolicyCases.Case c : table) {
            if (c.url != null && (!httpsOnly || c.url.regionMatches(true, 0, "https://", 0, 8))) {
                seeds.add(c.url);
            }
        }
        return seeds;
    }

    private static String mutate(String seed, Random random) {
        String url = seed;
        int rounds = 1 + random.nextInt(3);
        for (int r = 0; r < rounds; r++) {
            switch (random.nextInt(9)) {
                case 0: {
                    // Insert an injection token at a random position.
                    int at = random.nextInt(url.length() + 1);
                    url = url.substring(0, at) + pick(INJECTIONS, random) + url.substring(at);
                    break;
                }
                case 1: {
                    // Replace the scheme.
                    int colon = url.indexOf(':');
                    if (colon > 0) {
                        url = pick(SCHEME_FLIPS, random) + url.substring(colon);
                    }
                    break;
                }
                case 2: {
                    // Flip the case of a random character.
                    if (!url.isEmpty()) {
                        int at = random.nextInt(url.length());
                        char c = url.charAt(at);
                        char flipped = Character.isUpperCase(c) ? Character.toLowerCase(c) : Character.toUpperCase(c);
                        url = url.substring(0, at) + flipped + url.substring(at + 1);
                    }
                    break;
                }
                case 3: {
                    // Delete a random character.
                    if (url.length() > 1) {
                        int at = random.nextInt(url.length());
                        url = url.substring(0, at) + url.substring(at + 1);
                    }
                    break;
                }
                case 4: {
                    // Duplicate a random span.
                    if (url.length() > 2) {
                        int a = random.nextInt(url.length());
                        int b = Math.min(url.length(), a + 1 + random.nextInt(6));
                        url = url.substring(0, b) + url.substring(a, b) + url.substring(b);
                    }
                    break;
                }
                case 5: {
                    // Percent-encode a random character.
                    if (!url.isEmpty()) {
                        int at = random.nextInt(url.length());
                        char c = url.charAt(at);
                        if (c < 0x80) {
                            url = url.substring(0, at) + String.format(Locale.ROOT, "%%%02X", (int) c)
                                    + url.substring(at + 1);
                        }
                    }
                    break;
                }
                case 6: {
                    // Append an injection token (tail attacks: trailing dot, fragment, second URL).
                    url = url + pick(INJECTIONS, random);
                    break;
                }
                case 7: {
                    // Prepend junk (leading whitespace/control, scheme confusion).
                    url = pick(new String[]{" ", "\t", "\n", "\u0000", "javascript:", "//", "\\", "x"}, random) + url;
                    break;
                }
                default: {
                    // Splice in a second seed URL (nested-URL confusion).
                    int at = random.nextInt(url.length() + 1);
                    url = url.substring(0, at) + seed + url.substring(at);
                    break;
                }
            }
        }
        if (url.length() > 4_000) {
            url = url.substring(0, 4_000);
        }
        return url;
    }

    private static String pick(String[] options, Random random) {
        return options[random.nextInt(options.length)];
    }

    /** Make control characters visible in failure messages. */
    private static String visible(String url) {
        if (url == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                sb.append(String.format(Locale.ROOT, "<%02X>", (int) c));
            } else if (c > 0x7e) {
                sb.append(String.format(Locale.ROOT, "<U+%04X>", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
