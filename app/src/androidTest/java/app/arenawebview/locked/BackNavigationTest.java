package app.arenawebview.locked;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.IntentFilter;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import app.arenawebview.locked.policy.PolicyMetrics;

/**
 * Back navigation stays inside the app.
 *
 * <p>Asserted properties:</p>
 * <ul>
 *   <li>a back gesture never starts another activity (nothing is launched, no intent is fired);</li>
 *   <li>the first press never closes the app (so a stray tap cannot lose a conversation);</li>
 *   <li>pressing back repeatedly does close the app (the user is never trapped);</li>
 *   <li>after every press the WebView is still on an approved URL - back can never expose a page the
 *       policy refused.</li>
 * </ul>
 */
@RunWith(AndroidJUnit4.class)
public class BackNavigationTest {

    @Test
    public void backNeverLeavesTheApp() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Instrumentation.ActivityMonitor monitor =
                instrumentation.addMonitor(new IntentFilter(Intent.ACTION_VIEW), null, false);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            long blockedIntentsBefore = PolicyMetrics.get().count(PolicyMetrics.Counter.BLOCKED_EXTERNAL_INTENTS);

            boolean finished = false;
            int presses = 0;
            while (!finished && presses < 6) {
                presses++;
                final boolean[] state = new boolean[2];
                final String[] url = new String[1];
                scenario.onActivity(activity -> {
                    activity.handleBackPressed();
                    state[0] = activity.isFinishing();
                    WebView webView = activity.findViewById(R.id.arena_webview);
                    url[0] = webView == null ? null : webView.getUrl();
                });
                finished = state[0];
                if (!finished) {
                    String current = url[0];
                    assertTrue("back must never expose a different site: " + current,
                            current == null || current.isEmpty() || current.startsWith("https://github.com/"));
                    Thread.sleep(150);
                }
            }

            assertFalse("one back press must not be enough to close the app", presses == 1);
            assertTrue("back must eventually close the app instead of trapping the user", finished);
            assertEquals("back must never start another activity", blockedIntentsBefore,
                    PolicyMetrics.get().count(PolicyMetrics.Counter.BLOCKED_EXTERNAL_INTENTS));
        } finally {
            instrumentation.removeMonitor(monitor);
        }
        assertEquals("no activity may be launched by a back gesture", 0, monitor.getHits());
    }

    /** The legacy callback routes to the same handler, so an OEM that still calls it behaves identically. */
    @Test
    @SuppressWarnings("deprecation")
    public void legacyBackCallbackIsAlsoSafe() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                activity.onBackPressed();
                assertFalse("the first press must not close the app", activity.isFinishing());
                WebView webView = activity.findViewById(R.id.arena_webview);
                String url = webView == null ? null : webView.getUrl();
                assertTrue("the WebView must stay on an approved URL: " + url,
                        url == null || url.isEmpty() || url.startsWith("https://github.com/"));
            });
        }
    }
}
