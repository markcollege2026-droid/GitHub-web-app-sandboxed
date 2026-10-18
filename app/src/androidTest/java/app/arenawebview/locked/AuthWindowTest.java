package app.arenawebview.locked;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import app.arenawebview.locked.policy.PolicyMetrics;

/**
 * The in-app sign-in window.
 *
 * <p>The source retains an in-app window capability for an explicitly approved sign-in hand-off that
 * requires {@code window.opener}. The production GitHub allow-list enables no external sign-in host;
 * these UI lifecycle tests use a synthetic provider URL only to verify that, if a provider is separately
 * approved, its window remains an in-app child view, can be closed, and is covered by the watchdog.</p>
 */
@RunWith(AndroidJUnit4.class)
public class AuthWindowTest {

    private static final String SIGN_IN_URL = "https://login.identity.example/start";

    @Test
    public void signInWindowHostsTheWebViewAndCanBeClosed() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            final WebView[] holder = new WebView[1];
            scenario.onActivity(activity -> {
                WebView popup = new WebView(activity);
                holder[0] = popup;
                activity.onSignInWindowRequested(popup, SIGN_IN_URL);

                View window = activity.findViewById(R.id.auth_window);
                assertNotNull("the sign-in window must exist in the view hierarchy", window);
                assertEquals("the sign-in window must be visible", View.VISIBLE, window.getVisibility());

                ViewGroup content = activity.findViewById(R.id.auth_window_content);
                assertEquals("the sign-in WebView must be hosted inside the window", 1,
                        content.getChildCount());
                assertEquals("the hosted view must be the pop-up WebView", popup, content.getChildAt(0));

                // It is a child of this activity's own hierarchy - no second activity was started.
                assertNotNull("the window must be attached to the activity", window.getRootView());
            });

            // The user can always close it.
            scenario.onActivity(activity -> {
                Button close = activity.findViewById(R.id.auth_window_close);
                assertNotNull(close);
                assertTrue("the close button must react", close.performClick());

                View window = activity.findViewById(R.id.auth_window);
                assertEquals("the window must be hidden after closing", View.GONE, window.getVisibility());
                ViewGroup content = activity.findViewById(R.id.auth_window_content);
                assertEquals("the WebView must be detached when the window closes", 0,
                        content.getChildCount());
            });
        }
    }

    @Test
    public void backClosesTheSignInWindowBeforeAnythingElse() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                WebView popup = new WebView(activity);
                activity.onSignInWindowRequested(popup, SIGN_IN_URL);
                assertEquals(View.VISIBLE, activity.findViewById(R.id.auth_window).getVisibility());

                activity.handleBackPressed();

                assertEquals("back must close the sign-in window", View.GONE,
                        activity.findViewById(R.id.auth_window).getVisibility());
                assertFalse("back must not finish the activity while the site is open",
                        activity.isFinishing());
            });
        }
    }

    /** Repeated navigation inside the hand-off must not re-parent the WebView or stack windows. */
    @Test
    public void repeatedRequestsReuseTheSameWindow() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                WebView popup = new WebView(activity);
                activity.onSignInWindowRequested(popup, SIGN_IN_URL);
                activity.onSignInWindowRequested(popup, "https://login.identity.example/password");

                ViewGroup content = activity.findViewById(R.id.auth_window_content);
                assertEquals("the same window must be reused, not stacked", 1, content.getChildCount());
                assertEquals(popup, content.getChildAt(0));
                assertEquals(View.VISIBLE, activity.findViewById(R.id.auth_window).getVisibility());
            });
        }
    }

    @Test
    public void noSignInWindowIsOpenAfterStartup() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                View window = activity.findViewById(R.id.auth_window);
                assertNotNull(window);
                assertEquals("the sign-in window must start hidden", View.GONE, window.getVisibility());

                ViewGroup content = activity.findViewById(R.id.auth_window_content);
                assertEquals("nothing may be hosted before a sign-in starts", 0, content.getChildCount());
            });
        }
    }

    /**
     * A sign-in window is never invisible in the diagnostics: the counter is part of the report the user
     * can copy, next to every other guard.
     */
    @Test
    public void signInWindowsAreReportedInTheDiagnostics() {
        PolicyMetrics metrics = PolicyMetrics.get();
        metrics.increment(PolicyMetrics.Counter.SIGN_IN_WINDOWS);
        String report = metrics.snapshot();
        assertTrue("the report must account for sign-in windows: " + report,
                report.contains(PolicyMetrics.Counter.SIGN_IN_WINDOWS.name()));
    }
}
