package app.arenawebview.locked.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The configuration object is the one place where a mistake in the allow-list could pass unnoticed, so
 * its constructor has to refuse anything that would make the enforced policy differ from the readable
 * one.
 */
public class PolicyConfigTest {

    private static final AllowEntry PRODUCT = AllowEntry.anyPath("github.com", "test");
    private static final AllowEntry AUTH = AllowEntry.anyPath("auth.github.com", "test");

    @Test
    public void aHostListedTwiceIsAProgrammingErrorAndFailsAtConstruction() {
        // The second entry has a narrower path pin. Had the constructor accepted this, the first entry
        // would have won every lookup and the pin would have been dead text in the source.
        AllowEntry wide = AllowEntry.resourceOnly("www.gstatic.com", Collections.<String>emptyList(), "a");
        AllowEntry narrow = AllowEntry.resourceOnly("www.gstatic.com", Arrays.asList("/recaptcha/"), "b");
        try {
            new PolicyConfig("https://github.com/", Arrays.asList(PRODUCT, wide, narrow));
            fail("duplicate host must be refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("www.gstatic.com"));
        }
        // The same host object listed twice is just as wrong.
        try {
            new PolicyConfig("https://github.com/", Arrays.asList(PRODUCT, AUTH, PRODUCT));
            fail("duplicate host must be refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("github.com"));
        }
    }

    @Test
    public void nullEntriesAreRefused() {
        try {
            new PolicyConfig("https://github.com/", Arrays.asList(PRODUCT, null));
            fail("null entry must be refused");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void emptyConfigurationsAreRefused() {
        try {
            new PolicyConfig("https://github.com/", Collections.<AllowEntry>emptyList());
            fail("an empty allow-list must be refused");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            new PolicyConfig("", Arrays.asList(PRODUCT));
            fail("an empty primary URL must be refused");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            new PolicyConfig(null, Arrays.asList(PRODUCT));
            fail("a null primary URL must be refused");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void thePrimaryUrlMustPassItsOwnPolicy() {
        for (String bad : new String[]{"http://github.com/", "https://evil.example/", "https://github.com./",
                "https://auth.github.com@evil.example/", "about:blank"}) {
            try {
                new PolicyConfig(bad, Arrays.asList(PRODUCT, AUTH));
                fail("primary URL must be refused: " + bad);
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("primaryUrl"));
            }
        }
        // An asset-only host is not a page, so it cannot be the start page either.
        try {
            new PolicyConfig("https://www.gstatic.com/",
                    Arrays.asList(AllowEntry.resourceOnly("www.gstatic.com", null, "x")));
            fail("an asset host cannot be the start page");
        } catch (IllegalStateException expected) {
            // ok
        }
    }

    @Test
    public void lookupIsExactAndCaseSensitiveOnTheCanonicalForm() {
        PolicyConfig config = new PolicyConfig("https://github.com/", Arrays.asList(PRODUCT, AUTH));
        assertSame(PRODUCT, config.findByHost("github.com"));
        assertSame(AUTH, config.findByHost("auth.github.com"));
        // findByHost is only ever handed canonical hosts; anything else is simply absent.
        assertNull(config.findByHost("GITHUB.COM"));
        assertNull(config.findByHost("github.com."));
        assertNull(config.findByHost("www.github.com"));
        assertNull(config.findByHost(""));
        assertNull(config.findByHost(null));
    }

    @Test
    public void entriesAreCopiedAndReadOnly() {
        List<AllowEntry> source = new java.util.ArrayList<AllowEntry>(Arrays.asList(PRODUCT, AUTH));
        PolicyConfig config = new PolicyConfig("https://github.com/", source);
        source.clear();
        assertEquals(2, config.entries().size());
        try {
            config.entries().add(PRODUCT);
            fail("entries must be read-only");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
        assertEquals("github.com", config.entries().get(0).host());
        assertEquals("auth.github.com", config.entries().get(1).host());
    }

    @Test
    public void theProductionConfigurationHasNoDuplicateHostsAndNamesEveryGroup() {
        PolicyConfig production = GitHubAllowlist.productionConfig();
        assertNotNull(production);
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (AllowEntry entry : production.entries()) {
            assertTrue("host appears twice: " + entry.host(), seen.add(entry.host()));
            assertFalse("purpose must be recorded for " + entry.host(), entry.purpose().isEmpty());
        }
        assertEquals(GitHubAllowlist.PRIMARY_URL, production.primaryUrl());
        assertTrue(production.evaluateNavigation(production.primaryUrl()).isAllowed());
    }
}
