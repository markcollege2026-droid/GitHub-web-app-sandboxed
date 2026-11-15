package app.githubwebview.locked.policy;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.githubwebview.locked.TestPaths;

/**
 * Keeps {@code docs/DOMAINS.md} honest.
 *
 * <p>That file is what a person reads to learn which hosts the app can reach. It went stale once: the
 * twenty numbered GitHub Actions storage hosts were replaced in code by one anchored host pattern, and
 * the table kept listing the twenty hosts (and a total of 66) for a month. This test compares every
 * row of the tables with the configuration that is actually enforced - host, role and path scope -
 * so the document cannot drift from the code again without failing the build.</p>
 *
 * <p>It does not decide what should be reachable; {@link AllowlistRegressionTest} pins that.</p>
 */
public class DomainsDocTest {

    /** A table row: | `host` | ROLE | path scope | ... */
    private static final Pattern ROW = Pattern.compile(
            "^\\|\\s*`([^`]+)`\\s*\\|\\s*(PRODUCT|SIGN_IN|ASSET)\\s*\\|\\s*([^|]*?)\\s*\\|.*$");

    @Test
    public void domainsDocListsExactlyTheEnforcedHostsRolesAndPathScopes() throws Exception {
        Map<String, String> enforced = new TreeMap<String, String>();
        for (AllowEntry entry : GitHubAllowlist.activeEntries()) {
            enforced.put(entry.host(), entry.role().name() + " | " + scope(entry.pathPrefixes()));
        }
        for (HostPatternEntry pattern : GitHubAllowlist.resourceHostPatterns()) {
            enforced.put(pattern.description(),
                    pattern.role().name() + " | " + scope(pattern.pathPrefixes()));
        }

        String text = TestPaths.read(domainsDoc());
        Map<String, String> documented = new TreeMap<String, String>();
        List<String> problems = new ArrayList<String>();
        for (String line : text.split("\n")) {
            Matcher row = ROW.matcher(line.trim());
            if (!row.matches()) {
                continue;
            }
            if (documented.put(row.group(1), row.group(2) + " | " + row.group(3).trim()) != null) {
                problems.add("listed twice in DOMAINS.md: " + row.group(1));
            }
        }

        for (Map.Entry<String, String> entry : enforced.entrySet()) {
            String found = documented.get(entry.getKey());
            if (found == null) {
                problems.add("enforced but not documented: " + entry.getKey());
            } else if (!found.equals(entry.getValue())) {
                problems.add(entry.getKey() + " is enforced as [" + entry.getValue()
                        + "] but documented as [" + found + "]");
            }
        }
        for (String host : documented.keySet()) {
            if (!enforced.containsKey(host)) {
                problems.add("documented but not enforced: " + host);
            }
        }
        assertTrue("docs/DOMAINS.md differs from the enforced allow-list: " + problems, problems.isEmpty());

        int exact = GitHubAllowlist.activeEntries().size();
        assertTrue("docs/DOMAINS.md must state the real total (" + exact + " exact hosts)",
                text.contains("**" + exact + " exact hosts:**"));
    }

    private static String scope(List<String> prefixes) {
        if (prefixes.isEmpty()) {
            return "Any path";
        }
        StringBuilder scope = new StringBuilder();
        for (String prefix : prefixes) {
            if (scope.length() > 0) {
                scope.append(", ");
            }
            scope.append('`').append(prefix).append('`');
        }
        return scope.toString();
    }

    private static File domainsDoc() throws Exception {
        // moduleDir() may be ".../app/." - resolve it before stepping up to the repository root.
        File file = new File(TestPaths.moduleDir().getCanonicalFile().getParentFile(), "docs/DOMAINS.md");
        if (!file.isFile()) {
            throw new IllegalStateException("Missing " + file);
        }
        return file;
    }
}
