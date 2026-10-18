package app.arenawebview.locked.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.webkit.WebView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.arenawebview.locked.policy.AllowEntry;
import app.arenawebview.locked.policy.GitHubAllowlist;
import app.arenawebview.locked.policy.PolicyConfig;
import app.arenawebview.locked.policy.PolicyMetrics;

/**
 * Pop-ups ({@code window.open()}, {@code target="_blank"}, {@code isDialog} windows).
 *
 * <p>The wrapper's promise is that no pop-up can escape: Chromium is handed a hidden WebView, the URL
 * that window wanted is re-validated, an allowed URL is loaded in the single visible WebView and
 * anything else is refused. A third-party sign-in window is only possible when an exact SIGN_IN host is
 * explicitly present in the policy; the production GitHub build has no such host. The synthetic provider
 * in the focused tests below verifies the existing hand-off capability without approving a real provider.</p>
 */
@RunWith(AndroidJUnit4.class)
public class PopupBlockingTest {

    private static final PolicyConfig POLICY = GitHubAllowlist.productionConfig();

    /** A test-only exact SIGN_IN host; it is not part of the production GitHub allow-list. */
    private static PolicyConfig policyWithApprovedTestSignIn() {
        List<AllowEntry> entries = new ArrayList<AllowEntry>(GitHubAllowlist.activeEntries());
        entries.add(AllowEntry.signIn("login.identity.example", Collections.<String>emptyList(),
                "synthetic provider for popup-routing tests only"));
        return new PolicyConfig(GitHubAllowlist.PRIMARY_URL, entries);
    }

    /** The routing decision itself: a hidden window that wanted a third-party URL must be refused. */
    @Test
    public void popupToThirdPartyIsRefusedAndNeverAttached() {
        final RecordingGuardHost host = new RecordingGuardHost(POLICY);
        final WebView[] holder = new WebView[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                WebView popup = new WebView(InstrumentationRegistry.getInstrumentation().getTargetContext());
                holder[0] = popup;
                PopupRouterClient client = new PopupRouterClient(host, popup, null);

                assertTrue("the hidden window must never load the target itself",
                        client.shouldOverrideUrlLoading(popup, FakeWebResourceRequest.navigation("https://evil.example/")));

                // ... and requests made from inside it are policed as well.
                assertNotNull(client.shouldInterceptRequest(popup, FakeWebResourceRequest.subresource("https://evil.example/tracker.js")));
                assertNull(client.shouldInterceptRequest(popup, FakeWebResourceRequest.subresource("https://github.githubassets.com/assets/x.js")));
            }
        });

        assertEquals(1, host.metrics().count(PolicyMetrics.Counter.BLOCKED_POPUPS));
        assertEquals("https://evil.example/", host.lastPopupBlocked);
        assertNull("nothing may be routed into the main WebView", host.lastLoadedUrl);
        assertNull("a pop-up must never be attached to a view hierarchy", holder[0].getParent());
    }

    /** A pop-up that targets the site itself is loaded in the one visible WebView, not in a new window. */
    @Test
    public void popupToTheSiteIsReroutedIntoTheSingleWebView() {
        final RecordingGuardHost host = new RecordingGuardHost(POLICY);
        final WebView[] holder = new WebView[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                WebView popup = new WebView(InstrumentationRegistry.getInstrumentation().getTargetContext());
                holder[0] = popup;
                PopupRouterClient client = new PopupRouterClient(host, popup, null);

                assertTrue(client.shouldOverrideUrlLoading(popup,
                        FakeWebResourceRequest.navigation("https://github.com/leaderboard")));
            }
        });

        assertEquals(1, host.metrics().count(PolicyMetrics.Counter.ROUTED_POPUPS));
        assertEquals("a site pop-up must not become a sign-in window", 0,
                host.metrics().count(PolicyMetrics.Counter.SIGN_IN_WINDOWS));
        assertEquals("https://github.com/leaderboard", host.lastPopupRouted);
        assertEquals("https://github.com/leaderboard", host.lastLoadedUrl);
        assertEquals("popup", host.lastSource);
        assertNull(holder[0].getParent());
    }

    /** A blank pop-up is left alone (scripts can still write into it) but nothing is ever displayed. */
    @Test
    public void blankPopupIsNotNavigated() {
        final RecordingGuardHost host = new RecordingGuardHost(POLICY);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                WebView popup = new WebView(InstrumentationRegistry.getInstrumentation().getTargetContext());
                PopupRouterClient client = new PopupRouterClient(host, popup, null);
                assertFalse(client.shouldOverrideUrlLoading(popup, FakeWebResourceRequest.navigation("about:blank")));
            }
        });
        assertEquals(0, host.metrics().count(PolicyMetrics.Counter.ROUTED_POPUPS));
        assertNull(host.lastLoadedUrl);
    }

    /**
     * A pop-up that is a sign-in hand-off is <b>kept alive</b> and handed to the host to be shown in the
     * app's own sign-in window: the OAuth flow returns its result through {@code window.opener}, which
     * only works if the window still exists. Nothing is routed into the main WebView in this case.
     */
    @Test
    public void popupToASignInHostIsKeptAndShownInApp() {
        final RecordingGuardHost host = new RecordingGuardHost(policyWithApprovedTestSignIn());
        final WebView[] holder = new WebView[1];
        final boolean[] stillUsable = new boolean[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                WebView popup = new WebView(InstrumentationRegistry.getInstrumentation().getTargetContext());
                holder[0] = popup;
                PopupRouterClient client = new PopupRouterClient(host, popup, null);

                // false == "let this window navigate itself", i.e. the window is not cancelled.
                assertFalse("the sign-in window must be allowed to load the URL itself",
                        client.shouldOverrideUrlLoading(popup, FakeWebResourceRequest.navigation(
                                "https://login.identity.example/oauth2/authorize?client_id=x&redirect_uri=y")));
                assertTrue("the router must know the window is a sign-in window", client.isSignInWindow());

                // The hand-off continues inside the same window: a second approved provider page stays there.
                assertFalse(client.shouldOverrideUrlLoading(popup, FakeWebResourceRequest.navigation(
                        "https://login.identity.example/signin")));

                // Touching a WebView is only legal on the thread that created it, so the liveness check
                // has to happen here - asserting it from the test thread is itself an error.
                stillUsable[0] = popup.getSettings() != null;
            }
        });

        assertEquals(1, host.metrics().count(PolicyMetrics.Counter.SIGN_IN_WINDOWS));
        assertEquals("https://login.identity.example/oauth2/authorize?client_id=x&redirect_uri=y",
                host.lastSignInWindowUrl);
        assertNull("a sign-in window must not be routed into the main WebView", host.lastLoadedUrl);
        assertEquals("nothing may be counted as a routed popup", 0,
                host.metrics().count(PolicyMetrics.Counter.ROUTED_POPUPS));
        assertTrue("the window must still be alive (a destroyed WebView cannot report its URL), "
                + "which is what the OAuth flow needs to post its result back to the opening page",
                stillUsable[0]);
    }

    /**
     * The return hop of the hand-off stays in the window. After the provider authenticates the user it sends
     * the window back to GitHub.com's callback URL, whose page reads the result and closes the window with
     * {@code window.close()}. Cancelling or moving that hop would break the flow the window exists for, so
     * it must stay - and it must not be counted as a second sign-in window.
     */
    @Test
    public void theReturnHopBackToTheSiteStaysInTheSignInWindow() {
        final RecordingGuardHost host = new RecordingGuardHost(policyWithApprovedTestSignIn());
        final WebView[] holder = new WebView[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                WebView popup = new WebView(InstrumentationRegistry.getInstrumentation().getTargetContext());
                holder[0] = popup;
                PopupRouterClient client = new PopupRouterClient(host, popup, null);

                assertFalse(client.shouldOverrideUrlLoading(popup, FakeWebResourceRequest.navigation(
                        "https://login.identity.example/oauth2/authorize?client_id=x&redirect_uri=y")));
                assertFalse("the callback page must load in the window that opened the flow",
                        client.shouldOverrideUrlLoading(popup, FakeWebResourceRequest.navigation(
                                "https://github.com/auth/callback?code=abc123")));
            }
        });

        assertEquals("one window, not two", 1, host.metrics().count(PolicyMetrics.Counter.SIGN_IN_WINDOWS));
        assertEquals(0, host.metrics().count(PolicyMetrics.Counter.ROUTED_POPUPS));
        assertNull("the callback page must not be pulled into the main WebView", host.lastLoadedUrl);
        assertEquals("https://github.com/auth/callback?code=abc123", host.lastSignInWindowNavigated);
    }

    /**
     * The window is fenced to the hand-off: once a URL is refused, it is refused for the sign-in window
     * too. An asset host can never be displayed as a page (its role says so), and no third party can ever
     * be reached from the window - there is no "sign-in exception" that widens the policy itself.
     */
    @Test
    public void aPromotedSignInWindowStillRefusesEverythingOutsideTheHandOff() {
        final RecordingGuardHost host = new RecordingGuardHost(policyWithApprovedTestSignIn());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                WebView popup = new WebView(InstrumentationRegistry.getInstrumentation().getTargetContext());
                PopupRouterClient client = new PopupRouterClient(host, popup, null);

                assertFalse(client.shouldOverrideUrlLoading(popup, FakeWebResourceRequest.navigation(
                        "https://login.identity.example/signin")));

                assertTrue("an asset host must stay an asset even inside the sign-in window",
                        client.shouldOverrideUrlLoading(popup, FakeWebResourceRequest.navigation(
                                "https://github.githubassets.com/assets/app.js")));
                assertTrue("no third party may be reached from the sign-in window",
                        client.shouldOverrideUrlLoading(popup, FakeWebResourceRequest.navigation(
                                "https://evil.example/steal")));
            }
        });

        assertEquals(2, host.metrics().count(PolicyMetrics.Counter.BLOCKED_POPUPS));
        assertNull(host.lastLoadedUrl);
        assertEquals(1, host.metrics().count(PolicyMetrics.Counter.SIGN_IN_WINDOWS));
    }


    /**
     * An approved provider page may embed supporting resources. Those are
     * embedded documents, not navigations, and they must load in place - judging them as "the window
     * navigating somewhere" would either cancel them or drag them into the main WebView.
     */
    @Test
    public void embeddedFramesInsideTheSignInWindowLoadInPlace() {
        final RecordingGuardHost host = new RecordingGuardHost(policyWithApprovedTestSignIn());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                WebView popup = new WebView(InstrumentationRegistry.getInstrumentation().getTargetContext());
                PopupRouterClient client = new PopupRouterClient(host, popup, null);

                assertFalse(client.shouldOverrideUrlLoading(popup, FakeWebResourceRequest.navigation(
                        "https://login.identity.example/signin")));

                // A frame the sign-in page needs: allowed as an embedded document, never routed elsewhere.
                assertFalse(client.shouldOverrideUrlLoading(popup, FakeWebResourceRequest.subframe(
                        "https://github.githubassets.com/assets/sign-in-frame.js")));
                assertFalse(client.shouldOverrideUrlLoading(popup, FakeWebResourceRequest.subframe(
                        "https://avatars.githubusercontent.com/u/583231")));
            }
        });
        assertEquals("an embedded frame must never be pulled into the main WebView", 0,
                host.metrics().count(PolicyMetrics.Counter.ROUTED_POPUPS));
        assertNull(host.lastLoadedUrl);
        assertEquals(1, host.metrics().count(PolicyMetrics.Counter.SIGN_IN_WINDOWS));
    }

    /**
     * A refused frame on the sign-in page costs that frame and nothing else. Tearing the window down
     * because a non-essential frame on the provider's page was refused would end the sign-in for no reason.
     */
    @Test
    public void aRefusedFrameInsideTheSignInWindowDoesNotEndTheHandOff() {
        final RecordingGuardHost host = new RecordingGuardHost(policyWithApprovedTestSignIn());
        final WebView[] holder = new WebView[1];
        final boolean[] closed = new boolean[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                WebView popup = new WebView(InstrumentationRegistry.getInstrumentation().getTargetContext());
                holder[0] = popup;
                PopupRouterClient client = new PopupRouterClient(host, popup, new Runnable() {
                    @Override
                    public void run() {
                        closed[0] = true;
                    }
                });
                assertFalse(client.shouldOverrideUrlLoading(popup, FakeWebResourceRequest.navigation(
                        "https://login.identity.example/signin")));

                assertTrue("an unlisted frame must be refused",
                        client.shouldOverrideUrlLoading(popup, FakeWebResourceRequest.subframe(
                                "https://evil.example/ad")));

                assertTrue("the hand-off must survive a refused frame", client.isSignInWindow());
                assertFalse("the window must not be torn down", closed[0]);
            }
        });
        assertEquals(1, host.metrics().count(PolicyMetrics.Counter.BLOCKED_REQUESTS));
        assertEquals("a refused frame is not a refused pop-up", 0,
                host.metrics().count(PolicyMetrics.Counter.BLOCKED_POPUPS));
    }

    /**
     * {@code window.close()} is how the hand-off ends: the page inside the window calls it, and the app
     * must hear about it (that is what hides and destroys the in-app window). It is delivered by the
     * chrome client, which is exactly why the sign-in window is given one.
     */
    @Test
    public void thePageCanCloseItsOwnSignInWindow() {
        final RecordingGuardHost host = new RecordingGuardHost(POLICY);
        final WebView[] holder = new WebView[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                WebView popup = new WebView(InstrumentationRegistry.getInstrumentation().getTargetContext());
                holder[0] = popup;
                ArenaWebChromeClient chrome = new ArenaWebChromeClient(
                        InstrumentationRegistry.getInstrumentation().getTargetContext(), host);
                popup.setWebChromeClient(chrome);
                chrome.onCloseWindow(popup);
            }
        });
        assertTrue("the host must be told the window closed: " + host.events(),
                host.events().contains("popup closed"));
    }

    /** A resource-only GitHub service host may never be displayed as a page. */
    @Test
    public void aGitHubAssetHostMayNeverBecomeTheVisibleDocument() {
        final RecordingGuardHost host = new RecordingGuardHost(POLICY);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                WebView popup = new WebView(InstrumentationRegistry.getInstrumentation().getTargetContext());
                PopupRouterClient client = new PopupRouterClient(host, popup, null);

                assertTrue("an asset host must not be shown as a page",
                        client.shouldOverrideUrlLoading(popup, FakeWebResourceRequest.navigation(
                                "https://github.githubassets.com/assets/app.js")));
            }
        });
        assertEquals(1, host.metrics().count(PolicyMetrics.Counter.BLOCKED_POPUPS));
        assertNull(host.lastLoadedUrl);
    }

    /**
     * Structural check of {@code onCreateWindow}: it claims the request (so no window is created by the
     * platform), hands Chromium a WebView that is never attached anywhere, and destroys it when its
     * client is done.
     */
    @Test
    public void onCreateWindowHandsOverAHiddenWebView() {
        final RecordingGuardHost host = new RecordingGuardHost(POLICY);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                android.content.Context context =
                        InstrumentationRegistry.getInstrumentation().getTargetContext();
                WebView primary = new WebView(context);
                ArenaWebChromeClient chrome = new ArenaWebChromeClient(context, host);

                // Chromium always hands over a Message that has a target handler; a bare
                // Message.obtain() would make sendToTarget() throw, so mirror the real shape.
                Message message = new android.os.Handler(android.os.Looper.getMainLooper())
                        .obtainMessage();
                WebView.WebViewTransport transport;
                try {
                    // WebViewTransport is a public type but not a public constructor; reflection keeps
                    // this test compiling on every SDK while still exercising the real code path.
                    Constructor<WebView.WebViewTransport> constructor =
                            WebView.WebViewTransport.class.getDeclaredConstructor(WebView.class);
                    constructor.setAccessible(true);
                    transport = constructor.newInstance(primary);
                } catch (Throwable t) {
                    Assume.assumeNoException("WebViewTransport is not constructible on this SDK", t);
                    return;
                }
                message.obj = transport;

                boolean handled = chrome.onCreateWindow(primary, false, true, message);

                assertTrue("the app must handle window creation itself", handled);
                WebView popup = transport.getWebView();
                assertNotNull("a hidden WebView must have been handed to Chromium", popup);
                assertNull("the hidden window must not be attached to any view hierarchy", popup.getParent());
                // The hidden window gets the same hardened configuration as the visible one, and is
                // policed by PopupRouterClient (asserted directly in the tests above).
                assertTrue("the site must keep working in the hidden window",
                        popup.getSettings().getJavaScriptEnabled());
                assertFalse(popup.getSettings().getAllowFileAccess());
                assertFalse(popup.getSettings().getAllowUniversalAccessFromFileURLs());
            }
        });
    }

    /** Dialog-style windows are refused before any window object exists. */
    @Test
    public void dialogPopupsAreRefused() {
        final RecordingGuardHost host = new RecordingGuardHost(POLICY);
        final boolean[] handled = new boolean[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                ArenaWebChromeClient chrome = new ArenaWebChromeClient(
                        InstrumentationRegistry.getInstrumentation().getTargetContext(), host);
                handled[0] = chrome.onCreateWindow(null, true, true, Message.obtain());
            }
        });
        assertFalse(handled[0]);
        assertEquals(1, host.metrics().count(PolicyMetrics.Counter.BLOCKED_POPUPS));
    }

    /**
     * A hidden window is released the moment its decision is made - <b>immediately</b>, even though it was
     * never attached to a window. A detached {@code WebView} suspends its own posted runnables until
     * something attaches it, and nothing ever attaches a hidden pop-up, so a release that went through
     * {@code WebView.post()} would never run: the window would survive, keep a whole renderer alive and keep
     * occupying one of the chrome client's live-window slots forever.
     */
    @Test
    public void hiddenWindowsAreReleasedImmediately() {
        final RecordingGuardHost host = new RecordingGuardHost(POLICY);
        final boolean[] closed = new boolean[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                WebView popup = new WebView(InstrumentationRegistry.getInstrumentation().getTargetContext());
                PopupRouterClient client = new PopupRouterClient(host, popup, new Runnable() {
                    @Override
                    public void run() {
                        closed[0] = true;
                    }
                });
                // A refused target...
                assertTrue(client.shouldOverrideUrlLoading(popup, FakeWebResourceRequest.navigation(
                        "https://evil.example/")));
                assertTrue("a refused window must be released at once, not queued on a view that is never attached", closed[0]);
                assertNull(popup.getParent());

                // ...and an allowed target that was re-routed into the visible WebView.
                closed[0] = false;
                WebView other = new WebView(InstrumentationRegistry.getInstrumentation().getTargetContext());
                PopupRouterClient routed = new PopupRouterClient(host, other, new Runnable() {
                    @Override
                    public void run() {
                        closed[0] = true;
                    }
                });
                assertTrue(routed.shouldOverrideUrlLoading(other, FakeWebResourceRequest.navigation(
                        "https://github.com/leaderboard")));
                assertTrue("a routed window must be released at once, not queued on a view that is never attached", closed[0]);
            }
        });
        assertEquals("https://github.com/leaderboard", host.lastLoadedUrl);
    }

    /**
     * Twelve pop-ups in a row: every one of them is decided and released, so the app's own cap on
     * simultaneous windows (three) never turns into a permanent "too many open windows" state. Before the
     * release was made synchronous this test failed on the fourth window.
     */
    @Test
    public void finishedWindowsDoNotPileUpAgainstTheOpenWindowLimit() {
        final RecordingGuardHost host = new RecordingGuardHost(POLICY);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
                WebView primary = new WebView(context);
                ArenaWebChromeClient chrome = new ArenaWebChromeClient(context, host);
                for (int i = 0; i < 12; i++) {
                    WebView.WebViewTransport transport = transportFor(primary);
                    if (transport == null) {
                        return; // WebViewTransport is not constructible on this SDK; covered elsewhere
                    }
                    Message message = new Handler(Looper.getMainLooper()).obtainMessage();
                    message.obj = transport;

                    assertTrue("window " + i + " must be created (earlier windows must have been released)",
                            chrome.onCreateWindow(primary, false, true, message));
                    WebView popup = transport.getWebView();
                    assertNotNull("a window must have been handed over", popup);
                    assertNull("the window must stay hidden", popup.getParent());
                    // The page inside the new window navigates to an allowed site page: routed into the
                    // single visible WebView, window released.
                    assertTrue(popup.getWebViewClient().shouldOverrideUrlLoading(popup,
                            FakeWebResourceRequest.navigation("https://github.com/thread/" + i)));
                }
            }
        });
        assertEquals("every pop-up must have been routed", 12,
                host.metrics().count(PolicyMetrics.Counter.ROUTED_POPUPS));
        assertEquals("no pop-up may be refused because an earlier one leaked", 0,
                host.metrics().count(PolicyMetrics.Counter.BLOCKED_POPUPS));
    }

    /** A {@code WebViewTransport} to hand to {@code onCreateWindow}, or null when the SDK forbids it. */
    private static WebView.WebViewTransport transportFor(WebView target) {
        try {
            Constructor<WebView.WebViewTransport> constructor =
                    WebView.WebViewTransport.class.getDeclaredConstructor(WebView.class);
            constructor.setAccessible(true);
            return constructor.newInstance(target);
        } catch (Throwable t) {
            return null;
        }
    }
}
