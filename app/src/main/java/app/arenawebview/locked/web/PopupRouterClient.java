package app.arenawebview.locked.web;

import android.os.Handler;
import android.os.Looper;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;

import app.arenawebview.locked.policy.PolicyMetrics;
import app.arenawebview.locked.policy.PolicyResult;

/**
 * Client attached to the WebView that Android creates for {@code window.open()} and
 * {@code target="_blank"}.
 *
 * <p>Every pop-up is decided here, and there are exactly three outcomes:</p>
 * <ol>
 *   <li><b>Ordinary allowed target</b> (for example, a GitHub.com page opened with
 *       {@code target="_blank"}): the URL is handed to the single visible WebView and the extra window is
 *       destroyed. Nothing new can appear on screen.</li>
 *   <li><b>Explicitly approved sign-in hand-off</b>: the window is <em>kept</em> and handed to the host,
 *       which shows it as an in-app sign-in window. This exceptional path exists only for providers whose
 *       flow needs {@code window.opener}/postMessage; it stays inside this app under the same policy and
 *       request interception (this class extends {@link ArenaWebViewClient}).</li>
 *   <li><b>Anything else</b>: refused, counted and reported; the window is destroyed.</li>
 * </ol>
 *
 * <p>The sign-in window is fenced in: it may display only the explicitly approved sign-in pages and
 * GitHub.com return pages. Every other allowed URL (a first-party page that is not part of a sign-in, an
 * asset host, or anything outside the hand-off) is routed to the single visible WebView or refused, so a
 * sign-in window can never become a general-purpose browser.</p>
 *
 * <p>The roles are checked on <em>every</em> navigation, not just the first one, so a promoted window
 * cannot later walk somewhere the policy would refuse.</p>
 */
public final class PopupRouterClient extends ArenaWebViewClient {

    private final WebView popup;
    private final Runnable onClosed;
    private volatile boolean closed;
    private boolean promotedToSignInWindow;

    public PopupRouterClient(GuardHost host, WebView popup, Runnable onClosed) {
        super(host);
        this.popup = popup;
        this.onClosed = onClosed;
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        if (request == null || request.getUrl() == null) {
            finish();
            return true;
        }
        return handle(request.getUrl().toString(), request.isForMainFrame());
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        // The legacy callback carries no frame information; treating it as a main frame is the
        // conservative reading (Chromium uses the WebResourceRequest overload above on every API level
        // this app supports).
        return handle(url, true);
    }

    private boolean handle(String url, boolean mainFrame) {
        GuardHost host = host();
        if (url == null || url.isEmpty()) {
            finish();
            return true;
        }
        if (isInertPopupLocation(url)) {
            // window.open() with no URL, or a script writing into a blank popup: keep the window alive
            // so a subsequent real navigation can still be routed, but never display anything.
            return false;
        }
        // The same rule as the main client: a main frame may only ever be a GitHub.com page or an
        // explicitly approved sign-in page, while a document embedded in this window (an iframe) is
        // judged as an embedded document. Resource-only hosts may serve frames but cannot take over the
        // window.
        PolicyResult result = mainFrame
                ? host.policy().evaluateNavigation(url)
                : host.policy().evaluateSubdocument(url);
        if (!result.isAllowed()) {
            if (mainFrame) {
                host.metrics().increment(PolicyMetrics.Counter.BLOCKED_POPUPS);
            } else {
                host.metrics().increment(PolicyMetrics.Counter.BLOCKED_REQUESTS);
            }
            host.metrics().recordBlock(result.reason(), result.canonicalHost(), url);
            if (mainFrame) {
                host.onPopupBlocked(url, result);
            }
            // A refused sub-document only loses its frame: tearing down the whole window would end a
            // sign-in because some non-essential frame on the provider's page was refused. A refused *navigation*
            // (main frame) still ends the window - it has nowhere legitimate left to go.
            if (mainFrame || !promotedToSignInWindow) {
                finish();
            }
            return true;
        }

        if (!mainFrame) {
            // An embedded document inside this window: let it load in place. It cannot become the app's
            // page and it cannot take the window anywhere.
            return false;
        }

        // --- main frame: only an approved sign-in step or a GitHub.com page may live here ------------
        boolean signInStep = host.policy().isSignInNavigation(url);
        boolean sitePage = host.policy().isProductNavigation(url);
        if (promotedToSignInWindow ? (signInStep || sitePage) : signInStep) {
            // The sign-in hand-off. The window has to stay alive when the approved provider returns its
            // result through window.opener/postMessage - a flow that only works if the window exists.
            //
            // Two kinds of navigation belong to that hand-off and both stay in this window:
            //   1. explicitly approved sign-in pages, and
            //   2. the return hop to a GitHub.com callback page, where the site can read the result and
            //      close the window itself with window.close().
            if (!promotedToSignInWindow) {
                promotedToSignInWindow = true;
                host.metrics().increment(PolicyMetrics.Counter.SIGN_IN_WINDOWS);
                host.onSignInWindowRequested(popup, url);
            } else {
                host.onSignInWindowNavigated(url);
            }
            return false;
        }

        // Any other allowed page: keep the single-view guarantee - hand it to the one visible WebView and
        // discard the throwaway window Chromium created. A URL the policy allows but that is not part of
        // this hand-off can never gain a window of its own.
        host.metrics().increment(PolicyMetrics.Counter.ROUTED_POPUPS);
        host.onPopupRouted(url);
        host.loadVerifiedDestination(url, "popup");
        finish();
        return true;
    }

    /** about:blank, data: and blob: are the inert bootstrap locations of a brand new window. */
    private static boolean isInertPopupLocation(String url) {
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("about:blank")
                || lower.startsWith("data:")
                || lower.startsWith("blob:");
    }

    /** True once this window was promoted to a visible in-app sign-in window. */
    public boolean isSignInWindow() {
        return promotedToSignInWindow;
    }

    /**
     * A pop-up whose renderer died is simply over. It is deregistered and destroyed like any other
     * finished window; if it was the sign-in window the host closes that window through the usual
     * {@code onPopupClosed} route. Nothing is ever reloaded into it, and the primary view is untouched.
     */
    @Override
    public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
        host().metrics().increment(PolicyMetrics.Counter.RENDERER_GONE);
        finish();
        return true;
    }

    /**
     * Tear this hidden window down and tell whoever created it.
     *
     * <p>Two separate things have to happen, in this order:</p>
     * <ol>
     *   <li><b>Deregister now.</b> The creator is told immediately, so the window stops occupying one of
     *       the chrome client's live-window slots and the host stops tracking it. It is a plain callback,
     *       safe to run inside a navigation callback, and it has to run synchronously: a window that is
     *       only released "later" is a window that can be counted against the app's pop-up limit
     *       indefinitely.</li>
     *   <li><b>Destroy after the callback returns.</b> Destroying a {@code WebView} from inside its own
     *       navigation callback is not safe, so the destruction is posted to the main looper - <em>not</em>
     *       through {@link WebView#post(Runnable)}, which a detached view merely queues until something
     *       attaches it. Nothing ever attaches a hidden pop-up, so that route would have left every hidden
     *       window alive.</li>
     * </ol>
     */
    private void finish() {
        if (closed) {
            return;
        }
        closed = true;
        if (onClosed != null) {
            onClosed.run();
        }
        Runnable destroy = new Runnable() {
            @Override
            public void run() {
                try {
                    popup.setWebViewClient(new android.webkit.WebViewClient());
                    popup.destroy();
                } catch (Throwable ignored) {
                    // A dead WebView is not an error here; it must simply never come back.
                }
            }
        };
        new Handler(Looper.getMainLooper()).post(destroy);
    }
}
