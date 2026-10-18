package app.arenawebview.locked;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import app.arenawebview.locked.policy.PolicyMetrics;

/**
 * The strongest end-to-end property the wrapper claims: <b>the app never displays a page from a domain
 * that is not on the allow-list, even if app code loads one directly.</b>
 *
 * <p>To prove it, this test deliberately bypasses the app's own guard
 * ({@code MainActivity.loadVerifiedDestination}) and calls {@code WebView.loadUrl} with a third-party
 * URL - the worst case, exactly what a future edit or a compromise of the guard would look like. The
 * request layer must refuse it in-process, and if anything were ever to load, the watchdog/final-URL
 * verification must take it away again. Either way the third-party page must not be visible.</p>
 */
@RunWith(AndroidJUnit4.class)
public class WatchdogRecoveryTest {

    @Test
    public void thirdPartyLoadIsRefusedAndNeverShown() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                WebView webView = activity.findViewById(R.id.arena_webview);
                assertNotNull(webView);
                // Deliberately unguarded load, i.e. the app's own policy is bypassed on purpose.
                webView.loadUrl("https://evil.example/definitely-not-allowed");
            });

            // Give the request layer, the page hooks and the 3-second watchdog time to act.
            Thread.sleep(4000);

            scenario.onActivity(activity -> {
                WebView webView = activity.findViewById(R.id.arena_webview);
                View overlay = activity.findViewById(R.id.status_overlay);
                boolean overlayCoversTheWebView = overlay != null && overlay.getVisibility() == View.VISIBLE;
                String url = webView.getUrl();

                boolean showingThirdParty =
                        url != null && !url.isEmpty() && !url.startsWith("https://github.com/") && !overlayCoversTheWebView;
                assertTrue("the app must never display " + url + " (overlay visible: "
                        + overlayCoversTheWebView + ")", !showingThirdParty);
            });
        }

        assertTrue("the refused request must be recorded",
                PolicyMetrics.get().recordedBlockCount() >= 1);
        boolean recordedEvilHost = false;
        for (PolicyMetrics.BlockEvent event : PolicyMetrics.get().recentBlocks()) {
            if (event.url().contains("evil.example")) {
                recordedEvilHost = true;
                break;
            }
        }
        assertTrue("the refused destination must appear in the diagnostics report", recordedEvilHost);
    }

    /** Every URL the wrapper is asked to load internally is checked again before it is used. */
    @Test
    public void internalLoadsAreCheckedAgain() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                WebView webView = activity.findViewById(R.id.arena_webview);
                String before = webView.getUrl();

                long blockedBefore = PolicyMetrics.get().count(PolicyMetrics.Counter.BLOCKED_NAVIGATIONS);
                // loadVerifiedDestination is the single funnel for internal loads; a hostile URL must be
                // refused there without touching the WebView.
                activity.loadVerifiedDestination("https://evil.example/", "test");
                assertTrue("an internal load of a third-party URL must be refused",
                        PolicyMetrics.get().count(PolicyMetrics.Counter.BLOCKED_NAVIGATIONS) > blockedBefore);

                String after = webView.getUrl();
                assertTrue("the WebView must not have been navigated by the refused internal load",
                        after == null || before == null || after.equals(before)
                                || after.startsWith("https://github.com/"));
            });
        }
    }
}
