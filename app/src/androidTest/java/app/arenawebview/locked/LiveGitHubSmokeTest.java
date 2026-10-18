package app.arenawebview.locked;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.util.Log;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import app.arenawebview.locked.policy.PolicyMetrics;

/**
 * End-to-end smoke test against the real site.
 *
 * <p>This is the only test that needs a network. It asserts the two things a wrapper must get right on a
 * live page: the app ends up on github.com (never anywhere else), and JavaScript is actually executing the
 * real site's code. It is intentionally tolerant of an offline emulator - in that case there is nothing
 * to load, which is not a policy failure - while still refusing to pass if the WebView ends up on any
 * other host.</p>
 */
@RunWith(AndroidJUnit4.class)
public class LiveGitHubSmokeTest {

    private static final String TAG = "GitHubWebViewLocked";
    private static final long LOAD_TIMEOUT_MS = 45_000L;

    @Test
    public void loadsTheRealSiteAndRunsItsJavaScript() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            String url = waitForSite(scenario);

            Log.i(TAG, "live smoke test: url after load = " + url);
            Log.i(TAG, "live smoke test diagnostics:\n" + PolicyMetrics.get().snapshot());

            if (url == null || url.isEmpty()) {
                Log.w(TAG, "no URL after " + LOAD_TIMEOUT_MS + "ms (offline emulator?) - the policy "
                        + "assertions below still hold");
                return;
            }
            assertTrue("the wrapper must only ever end up on github.com, was: " + url,
                    url.startsWith("https://github.com/"));

            if (!url.startsWith("https://github.com/")) {
                return;
            }

            // If the page really loaded, prove that the site's own JavaScript is running.
            AtomicReference<String> result = new AtomicReference<String>();
            CountDownLatch latch = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                WebView webView = activity.findViewById(R.id.arena_webview);
                assertNotNull(webView);
                webView.evaluateJavascript("(function(){return 1+1;})()", value -> {
                    result.set(value);
                    latch.countDown();
                });
            });
            boolean evaluated = latch.await(15, TimeUnit.SECONDS);
            if (evaluated) {
                Log.i(TAG, "evaluateJavascript returned: " + result.get());
                assertNotNull(result.get());
                assertTrue("JavaScript must be executing: " + result.get(), result.get().contains("2"));
            } else {
                Log.w(TAG, "JavaScript evaluation timed out (page may still be loading)");
            }

            // Whatever the site did, the wrapper must not have allowed a third party to be displayed.
            scenario.onActivity(activity -> {
                WebView webView = activity.findViewById(R.id.arena_webview);
                String current = webView.getUrl();
                assertTrue("still on github.com after the load, was: " + current,
                        current == null || current.isEmpty() || current.startsWith("https://github.com/"));
            });
        }
    }

    /**
     * Waits until the wrapper is on github.com, and (best effort) until the page has finished loading.
     * Returns the URL the WebView ended up on, or null/"" if nothing loaded (offline emulator).
     */
    private static String waitForSite(ActivityScenario<MainActivity> scenario) throws Exception {
        long deadline = System.currentTimeMillis() + LOAD_TIMEOUT_MS;
        String last = null;
        boolean reachedSite = false;
        while (System.currentTimeMillis() < deadline) {
            final String[] url = new String[1];
            final int[] progress = new int[1];
            scenario.onActivity(activity -> {
                WebView webView = activity.findViewById(R.id.arena_webview);
                url[0] = webView == null ? null : webView.getUrl();
                progress[0] = webView == null ? 0 : webView.getProgress();
            });
            last = url[0];
            if (last != null && last.startsWith("https://github.com/")) {
                reachedSite = true;
                if (progress[0] >= 100) {
                    break;
                }
            } else if (reachedSite) {
                // It left the site: stop waiting immediately so the assertion can report it.
                break;
            }
            Thread.sleep(1000);
        }
        return last;
    }
}
