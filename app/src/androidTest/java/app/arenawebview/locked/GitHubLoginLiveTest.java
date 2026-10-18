package app.arenawebview.locked;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.util.Log;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import app.arenawebview.locked.policy.GitHubAllowlist;
import app.arenawebview.locked.policy.PolicyMetrics;

/**
 * Best-effort live smoke test for GitHub's first-party login page.
 *
 * <p>This deliberately does not submit credentials, activate an IME, or attempt a real sign-in. If the
 * live page loads, it checks that the username and password controls are present and that the username
 * field retains its expected type, autocomplete, and form action. This is a DOM-level smoke test only;
 * it does not claim to verify keyboard/IME rendering, authentication success, CAPTCHA, or organization
 * SSO behavior.</p>
 *
 * <p>The test is offline-tolerant: if the emulator cannot reach GitHub, it records that fact and returns.
 * A live run that does load the page must stay on the allowed GitHub.com origin.</p>
 */
@RunWith(AndroidJUnit4.class)
public class GitHubLoginLiveTest {

    private static final String TAG = "GitHubWebViewLocked";
    private static final long LOAD_TIMEOUT_MS = 45_000L;
    private static final String LOGIN_URL = "https://github.com/login";

    @Test
    public void githubLoginPageAndInputControlsAreReachableWithoutCredentialSubmission() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> activity.loadVerifiedDestination(LOGIN_URL, "test:github-login"));
            String url = waitForLoad(scenario);
            Log.i(TAG, "GitHub login probe: url=" + url);

            if (url == null || url.isEmpty()) {
                Log.w(TAG, "GitHub login probe: nothing loaded within " + LOAD_TIMEOUT_MS
                        + "ms (offline emulator or unreachable endpoint?)");
                return;
            }
            assertTrue("the page must remain on GitHub.com, was: " + url,
                    url.startsWith("https://github.com/"));

            String probe = evaluate(scenario, """
                    (function () {
                      var login = document.querySelector('#login_field');
                      var password = document.querySelector('#password');
                      var form = login && login.form;
                      return JSON.stringify({
                        title: document.title,
                        loginPresent: !!login,
                        loginType: login ? login.type : '',
                        loginName: login ? login.name : '',
                        loginAutocomplete: login ? login.autocomplete : '',
                        passwordPresent: !!password,
                        passwordType: password ? password.type : '',
                        formAction: form ? form.action : '',
                        readyState: document.readyState
                      });
                    })()
                    """);
            Log.i(TAG, "GitHub login DOM probe: " + probe);
            assertNotNull("the page DOM must be inspectable", probe);
            assertTrue("the login form controls must be present: " + probe,
                    probe.contains("\\\"loginPresent\\\":true")
                            && probe.contains("\\\"passwordPresent\\\":true"));
            assertTrue("the username must be an editable text control: " + probe,
                    probe.contains("\\\"loginType\\\":\\\"text\\\"")
                            && probe.contains("\\\"loginName\\\":\\\"login\\\""));
            assertTrue("the form must post to GitHub's same-origin session handler: " + probe,
                    probe.contains("github.com/session"));

            List<String> refused = refusedAllowListedHosts();
            assertTrue("an active allow-listed host must not be blocked while GitHub login loads: " + refused,
                    refused.isEmpty());
        }
    }

    private static List<String> refusedAllowListedHosts() {
        List<String> refused = new ArrayList<String>();
        for (PolicyMetrics.BlockEvent event : PolicyMetrics.get().recentBlocks()) {
            String host = event.host();
            if (!host.isEmpty() && GitHubAllowlist.thirdPartyHosts().contains(host)) {
                refused.add(event.describe());
            }
        }
        return refused;
    }

    private static String waitForLoad(ActivityScenario<MainActivity> scenario) throws Exception {
        long deadline = System.currentTimeMillis() + LOAD_TIMEOUT_MS;
        String last = null;
        while (System.currentTimeMillis() < deadline) {
            final String[] url = new String[1];
            final int[] progress = new int[1];
            scenario.onActivity(activity -> {
                WebView webView = activity.findViewById(R.id.arena_webview);
                url[0] = webView == null ? null : webView.getUrl();
                progress[0] = webView == null ? 0 : webView.getProgress();
            });
            if (url[0] != null && !url[0].isEmpty()) {
                last = url[0];
                if (progress[0] >= 100) {
                    break;
                }
            }
            Thread.sleep(1000);
        }
        return last;
    }

    private static String evaluate(ActivityScenario<MainActivity> scenario, String script) throws Exception {
        AtomicReference<String> result = new AtomicReference<String>();
        CountDownLatch latch = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            WebView webView = activity.findViewById(R.id.arena_webview);
            if (webView == null) {
                latch.countDown();
                return;
            }
            webView.evaluateJavascript(script, value -> {
                result.set(value);
                latch.countDown();
            });
        });
        if (!latch.await(20, TimeUnit.SECONDS)) {
            Log.w(TAG, "GitHub login DOM evaluation timed out");
        }
        return result.get();
    }
}
