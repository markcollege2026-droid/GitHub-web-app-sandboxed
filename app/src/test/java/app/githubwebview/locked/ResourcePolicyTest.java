package app.githubwebview.locked;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Resource files, checked before the Android build has to check them.
 *
 * <p>Why this exists: a resource file is only really validated by {@code aapt2}, which runs deep inside
 * the Android build - so a typo in {@code res/values/strings.xml} fails the build minutes later, in a
 * toolchain the developer may not even have locally. This test is the cheap pre-flight: it parses every
 * XML file under {@code res/} and applies the two rules that actually break {@code aapt2}: an
 * <b>unescaped apostrophe or double quote inside a string resource</b> (Google's sign-in copy produced
 * exactly that failure in CI) and a <b>duplicate resource name</b> in one type.</p>
 *
 * <p>It is deliberately stricter than "it parses": an unescaped apostrophe is valid XML, so XML parsing
 * alone would happily accept the file that {@code aapt2} rejects.</p>
 */
public class ResourcePolicyTest {

    /** Collapses whitespace so a failure message shows the offending text on one line. */
    private static String oneLine(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }

    @Test
    public void everyResourceFileIsWellFormedXml() throws Exception {
        List<String> failures = new ArrayList<String>();
        for (File file : xmlFiles(new File(TestPaths.moduleDir(), "src/main/res"))) {
            try {
                xmlBuilder().parse(file);
            } catch (Exception e) {
                failures.add(file.getName() + ": " + e.getMessage());
            }
        }
        assertTrue("resource XML that does not parse: " + failures, failures.isEmpty());
    }

    @Test
    public void stringResourcesAreEscapedForAapt2() throws Exception {
        List<String> offenders = new ArrayList<String>();
        for (File file : valueFiles()) {
            Document document = xmlBuilder().parse(file);
            NodeList strings = document.getElementsByTagName("string");
            for (int i = 0; i < strings.getLength(); i++) {
                Element element = (Element) strings.item(i);
                String name = element.getAttribute("name");
                String value = element.getTextContent();
                String unescaped = unescapedDelimiter(value);
                if (unescaped != null) {
                    offenders.add(file.getName() + " / " + name + ": unescaped " + unescaped
                            + " in \"" + oneLine(value) + "\" (write \\" + unescaped + ")");
                }
            }
        }
        assertTrue("aapt2 rejects these string resources:\n  " + String.join("\n  ", offenders),
                offenders.isEmpty());
    }

    @Test
    public void idsAreUniqueAcrossEveryValuesFile() throws Exception {
        Set<String> seen = new LinkedHashSet<String>();
        List<String> duplicates = new ArrayList<String>();
        int total = 0;
        for (File file : valueFiles()) {
            Document document = xmlBuilder().parse(file);
            NodeList items = document.getElementsByTagName("item");
            for (int i = 0; i < items.getLength(); i++) {
                Element element = (Element) items.item(i);
                if (!"id".equals(element.getAttribute("type"))) {
                    continue;
                }
                total++;
                String name = element.getAttribute("name");
                if (!seen.add(name)) {
                    duplicates.add(name + " (in " + file.getName() + ")");
                }
            }
        }
        assertTrue("duplicate id resources: " + duplicates, duplicates.isEmpty());
        assertTrue("the app should still declare its view ids", total >= 4);
    }

    /** The ids the activity and its tests look up by name must exist as resources. */
    @Test
    public void theIdsTheCodeUsesAreDeclared() throws Exception {
        File ids = TestPaths.resource("values/ids.xml");
        Document document = xmlBuilder().parse(ids);
        Set<String> declared = new LinkedHashSet<String>();
        NodeList items = document.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            declared.add(((Element) items.item(i)).getAttribute("name"));
        }
        for (String required : new String[]{
                "github_webview", "status_overlay", "fullscreen_container",
                "auth_window", "auth_window_card", "auth_window_content", "auth_window_close"}) {
            assertTrue("missing id: " + required + " (declared: " + declared + ")",
                    declared.contains(required));
        }
    }

    /**
     * The network security config and the policy are wired to each other.
     *
     * <p>The config mirrors the hosts that may be <b>pages</b> (GitHub's own hosts plus Google's sign-in
     * host): a host the app can display has to be pinned there too, so a future page-capable host cannot
     * be added to the allow-list while the network config silently keeps trusting only the old set. The
     * asset-only entries (Google's static assets, the CAPTCHA widgets) are deliberately *not* pinned -
     * they are covered by the base config, and pinning them would widen the trust anchors for no gain.
     * The other direction (every pinned host is allow-listed) is asserted by {@code ManifestPolicyTest}.</p>
     */
    @Test
    public void everyPageCapableHostIsPinnedInTheNetworkSecurityConfig() throws Exception {
        Document config = xmlBuilder().parse(
                TestPaths.resource("xml/network_security_config.xml"));
        Set<String> pinned = new LinkedHashSet<String>();
        NodeList hosts = config.getElementsByTagName("domain");
        for (int i = 0; i < hosts.getLength(); i++) {
            pinned.add(hosts.item(i).getTextContent().trim());
        }
        List<String> missing = new ArrayList<String>();
        List<String> pageHosts = new ArrayList<String>();
        for (app.githubwebview.locked.policy.AllowEntry entry
                : app.githubwebview.locked.policy.GitHubAllowlist.activeEntries()) {
            if (entry.isResourceOnly()) {
                continue;
            }
            pageHosts.add(entry.host());
            if (!pinned.contains(entry.host())) {
                missing.add(entry.host());
            }
        }
        assertTrue("page-capable hosts that are not pinned in the network security config: " + missing,
                missing.isEmpty());
        // The sign-in host is the whole reason this build pins a third party at all.
        assertTrue("accounts.google.com must be pinned while sign-in is enabled",
                pageHosts.contains("accounts.google.com") && pinned.contains("accounts.google.com"));
        assertEquals("no duplicate <domain> entries", pinned.size(), hosts.getLength());
    }

    // ---------------------------------------------------------------------------------------------

    /** Returns the offending delimiter ({@code '} or {@code "}) when it is not backslash-escaped. */
    private static String unescapedDelimiter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\'' && c != '"') {
                continue;
            }
            boolean escaped = i > 0 && value.charAt(i - 1) == '\\';
            if (!escaped) {
                return String.valueOf(c);
            }
        }
        return null;
    }

    private static List<File> valueFiles() {
        File dir = new File(TestPaths.moduleDir(), "src/main/res/values");
        File[] files = dir.listFiles();
        assertTrue("no values directory at " + dir, files != null && files.length > 0);
        List<File> xml = new ArrayList<File>();
        for (File file : files) {
            if (file.getName().endsWith(".xml")) {
                xml.add(file);
            }
        }
        return xml;
    }

    private static List<File> xmlFiles(File dir) {
        List<File> out = new ArrayList<File>();
        collect(dir, out);
        return out;
    }

    private static void collect(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collect(child, out);
            } else if (child.getName().endsWith(".xml")) {
                out.add(child);
            }
        }
    }

    private static DocumentBuilder xmlBuilder() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }
}
