package app.arenawebview.locked.policy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import app.arenawebview.locked.TestPaths;

/**
 * Keeps the policy layer honest.
 *
 * <p>Two properties make the security core testable and reviewable, and both are enforced here:</p>
 * <ol>
 *   <li>No Android APIs. The whole decision process runs on a plain JVM, which is why the case table can
 *       cover hundreds of hostile inputs in a couple of seconds on every build.</li>
 *   <li>No {@code java.net.URI}/{@code URL} parsing. Those classes follow RFC 2396/3986 rules that differ
 *       from what Chromium does with the same string, and a parser differential is exactly how WebView
 *       wrappers get bypassed. The policy therefore does its own, deliberately stricter, string
 *       analysis.</li>
 * </ol>
 */
public class PolicyPurityTest {

    @Test
    public void policySourcesUseNoAndroidApis() throws Exception {
        for (File file : policySources()) {
            String source = TestPaths.read(file);
            assertFalse(file.getName() + " must not import Android classes",
                    source.contains("import android."));
            assertFalse(file.getName() + " must not reference Android classes",
                    source.contains("android.content.") || source.contains("android.webkit.")
                            || source.contains("android.net.") || source.contains("android.util."));
        }
    }

    @Test
    public void policySourcesDoNotUseJavaNetUrlParsing() throws Exception {
        for (File file : policySources()) {
            String source = TestPaths.read(file);
            assertFalse(file.getName() + " must not rely on java.net.URI",
                    source.contains("java.net.URI") || source.contains("new URI("));
            assertFalse(file.getName() + " must not rely on java.net.URL",
                    source.contains("java.net.URL") || source.contains("new URL("));
        }
    }

    @Test
    public void policySourcesDoNotLogOrPersistAnything() throws Exception {
        for (File file : policySources()) {
            String source = TestPaths.read(file);
            assertFalse(file.getName() + " must not log", source.contains("Log."));
            assertFalse(file.getName() + " must not touch the file system",
                    source.contains("FileOutputStream") || source.contains("SharedPreferences"));
        }
    }

    @Test
    public void policySourcesExist() throws Exception {
        assertTrue(policySources().size() >= 8);
    }

    private static List<File> policySources() {
        File dir = new File(TestPaths.mainSourcesDir(), "app/arenawebview/locked/policy");
        assertTrue("missing " + dir, dir.isDirectory());
        List<File> sources = new ArrayList<File>();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().endsWith(".java")) {
                    sources.add(file);
                }
            }
        }
        return sources;
    }
}
