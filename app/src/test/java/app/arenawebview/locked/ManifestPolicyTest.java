package app.arenawebview.locked;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Verifies the security-relevant parts of the manifest and of the network security config, straight from
 * the source files - the parts that decide what the app is allowed to do, and what other apps are
 * allowed to do to it.
 */
public class ManifestPolicyTest {

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    private static Document parse(java.io.File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(
                new ByteArrayInputStream(TestPaths.read(file).getBytes(StandardCharsets.UTF_8)));
    }

    private static String androidAttr(Element element, String name) {
        return element.getAttributeNS(ANDROID_NS, name);
    }

    /**
     * Network security config files use unprefixed attributes ({@code cleartextTrafficPermitted},
     * {@code src}, {@code includeSubdomains}) - unlike the manifest, where every attribute relevant to
     * the platform lives in the {@code android:} namespace.
     */
    private static String configAttr(Element element, String name) {
        return element.getAttribute(name);
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> result = new ArrayList<Element>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element && ((Element) node).getTagName().equals(tag)) {
                result.add((Element) node);
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------------------------
    // Manifest
    // -------------------------------------------------------------------------------------------

    @Test
    public void onlyInternetAndNetworkStatePermissionsAreDeclared() throws Exception {
        Document manifest = parse(TestPaths.manifest());
        List<Element> permissions = children(manifest.getDocumentElement(), "uses-permission");
        List<String> names = new ArrayList<String>();
        for (Element permission : permissions) {
            names.add(androidAttr(permission, "name"));
        }
        assertEquals(Arrays.asList(
                        "android.permission.INTERNET",
                        "android.permission.ACCESS_NETWORK_STATE"),
                names);
    }

    @Test
    public void thereIsExactlyOneExportedComponent() throws Exception {
        Document manifest = parse(TestPaths.manifest());
        Element application = children(manifest.getDocumentElement(), "application").get(0);

        // Nothing but the launcher activity may exist or be reachable from outside the app.
        assertTrue("no services allowed", children(application, "service").isEmpty());
        assertTrue("no receivers allowed", children(application, "receiver").isEmpty());
        assertTrue("no providers allowed", children(application, "provider").isEmpty());
        assertTrue("no activity aliases allowed", children(application, "activity-alias").isEmpty());

        List<Element> activities = children(application, "activity");
        assertEquals("exactly one activity", 1, activities.size());
        Element activity = activities.get(0);
        assertTrue(androidAttr(activity, "name").endsWith("MainActivity"));
        assertEquals("true", androidAttr(activity, "exported"));
        assertTrue("task affinity must be pinned to nothing (task-hijacking mitigation)",
                activity.hasAttributeNS(ANDROID_NS, "taskAffinity"));
        assertEquals("", androidAttr(activity, "taskAffinity"));

        List<Element> filters = children(activity, "intent-filter");
        assertEquals("exactly one intent filter", 1, filters.size());
        List<String> actions = new ArrayList<String>();
        for (Element action : children(filters.get(0), "action")) {
            actions.add(androidAttr(action, "name"));
        }
        List<String> categories = new ArrayList<String>();
        for (Element category : children(filters.get(0), "category")) {
            categories.add(androidAttr(category, "name"));
        }
        assertEquals(Arrays.asList("android.intent.action.MAIN"), actions);
        assertEquals(Arrays.asList("android.intent.category.LAUNCHER"), categories);
    }

    @Test
    public void webViewResizesForTheSoftwareKeyboard() throws Exception {
        Document manifest = parse(TestPaths.manifest());
        Element application = children(manifest.getDocumentElement(), "application").get(0);
        Element activity = children(application, "activity").get(0);
        assertEquals("preserve the existing WebView text-input resize behavior",
                "adjustResize", androidAttr(activity, "windowSoftInputMode"));

        String mainActivity = TestPaths.readSource("app/arenawebview/locked/MainActivity.java");
        assertTrue("the activity must retain its runtime soft-input resize setting",
                mainActivity.contains("WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE"));
    }

    @Test
    public void applicationIsLockedDown() throws Exception {
        Document manifest = parse(TestPaths.manifest());
        Element application = children(manifest.getDocumentElement(), "application").get(0);

        assertEquals("backups (and therefore cookies/session data) must be off",
                "false", androidAttr(application, "allowBackup"));
        assertEquals("cleartext HTTP must be off", "false", androidAttr(application, "usesCleartextTraffic"));
        assertEquals("@xml/network_security_config", androidAttr(application, "networkSecurityConfig"));
        assertEquals("true", androidAttr(application, "enableOnBackInvokedCallback"));
        assertEquals("", androidAttr(application, "debuggable"));
        assertFalse("no requestLegacyExternalStorage",
                "true".equals(androidAttr(application, "requestLegacyExternalStorage")));
        assertTrue("must not declare a shared user id",
                androidAttr(application, "sharedUserId").isEmpty());
    }

    // -------------------------------------------------------------------------------------------
    // Network security config
    // -------------------------------------------------------------------------------------------

    @Test
    public void networkSecurityConfigIsHttpsOnlyWithSystemAnchors() throws Exception {
        Document config = parse(TestPaths.resource("xml/network_security_config.xml"));
        Element root = config.getDocumentElement();
        assertEquals("network-security-config", root.getTagName());

        List<Element> baseConfigs = children(root, "base-config");
        assertEquals(1, baseConfigs.size());
        Element base = baseConfigs.get(0);
        assertEquals("cleartext must be refused by default",
                "false", configAttr(base, "cleartextTrafficPermitted"));

        List<Element> debugOverrides = children(root, "debug-overrides");
        assertTrue("debug builds must be as strict as release builds", debugOverrides.isEmpty());

        List<Element> anchors = children(base, "trust-anchors");
        assertEquals(1, anchors.size());
        List<Element> certificates = children(anchors.get(0), "certificates");
        assertEquals("only the system trust store may be used", 1, certificates.size());
        assertEquals("system", configAttr(certificates.get(0), "src"));

        List<Element> domainConfigs = children(root, "domain-config");
        assertFalse("the allowed hosts should be listed explicitly", domainConfigs.isEmpty());
        for (Element domainConfig : domainConfigs) {
            assertEquals("false", configAttr(domainConfig, "cleartextTrafficPermitted"));
            List<Element> domains = children(domainConfig, "domain");
            assertFalse(domains.isEmpty());
            for (Element domain : domains) {
                assertEquals("sub-domains must not be implied, every host is listed",
                        "false", configAttr(domain, "includeSubdomains"));
            }
        }
    }

    /**
     * Every host in the manifest's network config must also be in the runtime allow-list.
     *
     * <p>The config is a belt-and-braces layer (the base config already refuses cleartext and user CAs
     * everywhere), so it is allowed to be narrower than the policy - but it must never be <em>wider</em>,
     * which would mean the OS trusts a host the app's own policy would refuse.</p>
     */
    @Test
    public void networkSecurityConfigHostsAreAllAllowListed() throws Exception {
        Document config = parse(TestPaths.resource("xml/network_security_config.xml"));
        List<Element> domainConfigs = children(config.getDocumentElement(), "domain-config");
        assertFalse(domainConfigs.isEmpty());
        List<String> configHosts = new ArrayList<String>();
        for (Element domainConfig : domainConfigs) {
            for (Element domain : children(domainConfig, "domain")) {
                configHosts.add(domain.getTextContent().trim());
            }
        }

        List<String> allowedHosts = new ArrayList<String>(
                app.arenawebview.locked.policy.GitHubAllowlist.allowedHosts());
        for (String host : configHosts) {
            assertTrue("network config pins a host the policy does not allow: " + host,
                    allowedHosts.contains(host));
        }

        // GitHub.com, the only page-capable host, must be pinned explicitly.
        for (String host : app.arenawebview.locked.policy.GitHubAllowlist.FIRST_PARTY_HOSTS) {
            assertTrue("first-party host missing from the network config: " + host,
                    configHosts.contains(host));
        }
        assertEquals("GitHub.com is the only page-capable host", Arrays.asList("github.com"),
                app.arenawebview.locked.policy.GitHubAllowlist.FIRST_PARTY_HOSTS);
        assertTrue("the product page host must be pinned in the network config",
                configHosts.contains("github.com"));
    }
}
