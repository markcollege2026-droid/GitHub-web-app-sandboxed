package app.githubwebview.locked.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Collections;

/**
 * The anchored pattern for GitHub Actions results storage hosts, in isolation.
 *
 * <p>The whole point of the pattern is that the complete hostname must match: a prefix, one or
 * more digits, and the exact storage suffix. Every look-alike that merely contains the pattern
 * text must fail.</p>
 */
public class HostPatternEntryTest {

    private static final HostPatternEntry PATTERN = GitHubAllowlist.resourceHostPatterns().get(0);

    @Test
    public void acceptsEveryNumericEndpoint() {
        for (String host : new String[]{
                "productionresultssa0.blob.core.windows.net",
                "productionresultssa9.blob.core.windows.net",
                "productionresultssa19.blob.core.windows.net",
                "productionresultssa30.blob.core.windows.net",
                "productionresultssa49.blob.core.windows.net",
                "productionresultssa100.blob.core.windows.net",
                "productionresultssa199.blob.core.windows.net",
                "productionresultssa200.blob.core.windows.net",
                "productionresultssa123456.blob.core.windows.net"}) {
            assertTrue(host, PATTERN.matchesHost(host));
        }
    }

    @Test
    public void matchesCaseInsensitively() {
        assertTrue(PATTERN.matchesHost("PRODUCTIONRESULTSSA30.BLOB.CORE.WINDOWS.NET"));
        assertTrue(PATTERN.matchesHost("ProductionResultsSa30.Blob.Core.Windows.Net"));
    }

    @Test
    public void rejectsLookAlikes() {
        for (String host : new String[]{
                "productionresultssa30.blob.core.windows.net.evil.example",
                "productionresultssa30.blob.core.windows.net.evil.com",
                "evilproductionresultssa30.blob.core.windows.net",
                "productionresultssaX.blob.core.windows.net",
                "productionresultssa.blob.core.windows.net",
                "productionresultssa30a.blob.core.windows.net",
                "productionresultssa-30.blob.core.windows.net",
                "productionresultssa30.blob.core.windows.net.",
                "productionresultssa30.blob.core.windows.netx",
                "productionresultssa30.blob.core.windows.ne",
                "unrelated.blob.core.windows.net",
                "blob.core.windows.net",
                "productionresultssa30.blob.core.windows.net:443",
                "productionresultssa30.blob.core.windows.net/path",
                "",
                null}) {
            assertFalse(String.valueOf(host), PATTERN.matchesHost(host));
        }
    }

    @Test
    public void aMatchSynthesizesAResourceOnlyExactEntry() {
        AllowEntry entry = PATTERN.toEntry("productionresultssa30.blob.core.windows.net");
        assertNotNull(entry);
        assertEquals("productionresultssa30.blob.core.windows.net", entry.host());
        assertTrue(entry.isResourceOnly());
        assertFalse(entry.isProduct());
        assertFalse(entry.isSignIn());
        assertTrue(entry.pathPrefixes().isEmpty());
    }

    @Test
    public void patternConstructionIsValidated() {
        try {
            HostPatternEntry.resourceOnly("", "x", Collections.<String>emptyList(), "p");
            fail("empty description must be refused");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            HostPatternEntry.resourceOnly("x", "", Collections.<String>emptyList(), "p");
            fail("empty regex must be refused");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            HostPatternEntry.resourceOnly("x", "x", Collections.singletonList("no-slash"), "p");
            fail("path prefix without a slash must be refused");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void describeNamesThePatternAndItsRole() {
        String description = PATTERN.describe();
        assertTrue(description.contains("productionresultssa<digits>.blob.core.windows.net"));
        assertTrue(description.contains("resource-only"));
    }
}
