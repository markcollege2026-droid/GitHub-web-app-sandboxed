package app.arenawebview.locked.web;

import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.util.Log;
import android.webkit.ClientCertRequest;
import android.webkit.HttpAuthHandler;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SafeBrowsingResponse;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import app.arenawebview.locked.policy.BlockReason;
import app.arenawebview.locked.policy.PolicyMetrics;
import app.arenawebview.locked.policy.PolicyResult;

/**
 * The navigation guard.
 *
 * <p>Layers, all fail-closed:</p>
 * <ol>
 *   <li>{@link #shouldOverrideUrlLoading} - every navigation (main frame, iframe, redirect, JS
 *       {@code location}, form POST, custom scheme). Allowed URLs are handed back to the WebView by
 *       returning {@code false} so that Chromium keeps doing what it does best (POST bodies, cookies,
 *       HTTP/2, media); refused URLs are cancelled by returning {@code true}.</li>
 *   <li>{@link #shouldInterceptRequest} - every network request, including each hop of a redirect
 *       chain, XHR/fetch, images, scripts and media. A refused request is answered with an in-process
 *       empty 403 so it never reaches the network.</li>
 *   <li>{@link #onPageStarted}, {@link #onPageFinished} and {@link #doUpdateVisitedHistory} - final-URL
 *       verification. If anything is ever observed outside the policy the host is asked to reset the
 *       WebView, so a page can never remain visible on an unapproved URL.</li>
 *   <li>TLS, client-certificate and HTTP-auth handlers that always cancel.</li>
 * </ol>
 */
public class ArenaWebViewClient extends WebViewClient {

    private static final String TAG = "GitHubWebViewLocked";

    /** Refusals already written to logcat, so a busy page cannot flood the log. */
    private static final int MAX_LOGGED_REFUSALS = 200;

    private final Set<String> loggedRefusals =
            Collections.synchronizedSet(new LinkedHashSet<String>());

    private final GuardHost host;

    public ArenaWebViewClient(GuardHost host) {
        if (host == null) {
            throw new IllegalArgumentException("host");
        }
        this.host = host;
    }

    // ---------------------------------------------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------------------------------------------

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        if (request == null || request.getUrl() == null) {
            host.metrics().increment(PolicyMetrics.Counter.BLOCKED_NAVIGATIONS);
            host.metrics().recordBlock(BlockReason.NULL_URL, "", null);
            host.onNavigationBlocked("", null);
            return true;
        }
        String url = request.getUrl().toString();
        boolean mainFrame = request.isForMainFrame();

        // An iframe is judged as an embedded document, not as the app's page: a frame from an
        // asset host may render, while the main frame may only ever be a GitHub.com or explicitly
        // approved sign-in page. This is the difference between "the app can show this" and "the app can fetch this".
        PolicyResult result = mainFrame
                ? host.policy().evaluateNavigation(url)
                : host.policy().evaluateSubdocument(url);
        if (result.isAllowed()) {
            if (mainFrame) {
                host.metrics().increment(PolicyMetrics.Counter.ALLOWED_NAVIGATIONS);
            }
            // Let the WebView perform the navigation itself: returning false preserves the HTTP method
            // (POST form submissions!), request headers, cookies and any server-side redirect chain
            // that stays inside the policy.
            return false;
        }

        recordBlock(result, url, mainFrame, isRedirect(request));
        if (mainFrame) {
            host.onNavigationBlocked(url, result);
        }
        return true;
    }

    /**
     * Legacy callback. Chrome-based WebViews on API 24+ use the {@code WebResourceRequest} overload
     * above; this override exists so that the same rule applies if it is ever called.
     */
    @Override
    @SuppressWarnings("deprecation")
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        PolicyResult result = host.policy().evaluateNavigation(url);
        if (result.isAllowed()) {
            return false;
        }
        recordBlock(result, url, true, false);
        host.onNavigationBlocked(url, result);
        return true;
    }

    // ---------------------------------------------------------------------------------------------
    // Requests (every sub-resource and every redirect hop)
    // ---------------------------------------------------------------------------------------------

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        if (request == null || request.getUrl() == null) {
            host.metrics().increment(PolicyMetrics.Counter.BLOCKED_REQUESTS);
            host.metrics().recordBlock(BlockReason.NULL_URL, "", null);
            return BlockedResponses.create(null);
        }
        Uri uri = request.getUrl();
        String url = uri.toString();
        // The network layer sees every hop of every load, so it must ask the right question for each: a
        // main-frame request *is* the page (an asset host, a blob or a data URL must be refused here even
        // if an earlier hook were ever skipped); anything else is traffic inside an approved page.
        PolicyResult result = request.isForMainFrame()
                ? host.policy().evaluateNavigation(url)
                : host.policy().evaluateRequest(url);
        if (result.isAllowed()) {
            // null == "carry on normally". This is what keeps cookies, range requests, streaming media
            // and WebView's own cache working: nothing is proxied, rewritten or buffered.
            return null;
        }
        final boolean mainFrame = request.isForMainFrame();
        final boolean redirect = isRedirect(request);
        final String blockedUrl = url;
        final PolicyResult blockedResult = result;
        recordBlock(result, url, mainFrame, redirect);
        logRefusal(result, url, mainFrame);
        if (mainFrame) {
            // shouldInterceptRequest runs on a background thread; hop to the UI thread before the host
            // touches any view.
            postToUi(view, new Runnable() {
                @Override
                public void run() {
                    host.onPageLoadBlocked(blockedUrl, blockedResult);
                }
            });
        }
        return BlockedResponses.create(result);
    }

    /**
     * Writes one line per distinct refused destination to logcat.
     *
     * <p>Without this, a page whose script was refused simply behaves strangely - which is exactly how a
     * sign-in form can end up dead with nothing to see. The line names the reason, the host and the path
     * (never the query string), so a user report can be matched against the allow-list.</p>
     */
    private void logRefusal(PolicyResult result, String url, boolean mainFrame) {
        String reason = result == null || result.reason() == null ? "?" : result.reason().name();
        String host = result == null ? "" : result.canonicalHost();
        String path = pathOnly(url);
        synchronized (loggedRefusals) {
            if (loggedRefusals.size() >= MAX_LOGGED_REFUSALS || !loggedRefusals.add(reason + "|" + host + path)) {
                return;
            }
        }
        Log.i(TAG, "refused " + (mainFrame ? "document" : "sub-resource") + ": " + reason
                + " host=" + host + " path=" + path);
    }

    /** The raw path of a URL, without query or fragment, for the refusal log. */
    private static String pathOnly(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        int schemeEnd = url.indexOf("://");
        int start = schemeEnd < 0 ? 0 : schemeEnd + 3;
        int slash = url.indexOf('/', start);
        if (slash < 0) {
            return "/";
        }
        int end = url.length();
        int query = url.indexOf('?', slash);
        int fragment = url.indexOf('#', slash);
        if (query >= 0 && query < end) {
            end = query;
        }
        if (fragment >= 0 && fragment < end) {
            end = fragment;
        }
        return url.substring(slash, end);
    }

    // ---------------------------------------------------------------------------------------------
    // Final-URL verification
    // ---------------------------------------------------------------------------------------------

    @Override
    public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
        super.onPageStarted(view, url, favicon);
        verifyFinalUrl(view, url, "onPageStarted");
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        verifyFinalUrl(view, url, "onPageFinished");
        if (host.isPrimaryWebView(view)) {
            host.onPageFinished(url);
        }
    }

    @Override
    public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
        super.doUpdateVisitedHistory(view, url, isReload);
        if (!host.isPrimaryWebView(view)) {
            // Hidden popup windows never drive the visible UI; their own client handles routing.
            return;
        }
        PolicyResult result = host.policy().evaluateNavigation(url);
        if (!result.isAllowed()) {
            host.metrics().increment(PolicyMetrics.Counter.RECOVERY_RELOADS);
            host.metrics().recordBlock(result.reason(), result.canonicalHost(), url);
            // Do not let a refused URL stay in the history stack either: back navigation must only ever
            // be able to reach verified pages.
            view.clearHistory();
            host.onRecoveryRequired("history:" + (result.reason() == null ? "?" : result.reason().name()), url);
        }
    }

    private void verifyFinalUrl(WebView view, String url, String where) {
        if (url == null || url.isEmpty()) {
            return;
        }
        if (!host.isPrimaryWebView(view)) {
            return;
        }
        PolicyResult result = host.policy().evaluateNavigation(url);
        if (!result.isAllowed()) {
            host.metrics().increment(PolicyMetrics.Counter.RECOVERY_RELOADS);
            host.metrics().recordBlock(result.reason(), result.canonicalHost(), url);
            host.onRecoveryRequired(where + ":" + (result.reason() == null ? "?" : result.reason().name()), url);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Errors and other transports
    // ---------------------------------------------------------------------------------------------

    @Override
    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        if (request != null && request.isForMainFrame()) {
            String description = error == null
                    ? "unknown"
                    : (String.valueOf(error.getErrorCode()) + " " + String.valueOf(error.getDescription()));
            host.onLoadError(request.getUrl() == null ? "" : request.getUrl().toString(), description);
        }
    }

    /**
     * Certificate errors are never bypassed. Note that there is deliberately no branch that can call
     * {@code handler.proceed()}.
     */
    @Override
    public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
        host.metrics().increment(PolicyMetrics.Counter.BLOCKED_SSL_ERRORS);
        host.metrics().recordBlock(BlockReason.CAPABILITY_DISABLED,
                error == null ? "" : String.valueOf(error.getUrl()), null);
        handler.cancel();
        host.onSslErrorBlocked(error == null ? "" : String.valueOf(error.getUrl()));
    }

    @Override
    public void onReceivedClientCertRequest(WebView view, ClientCertRequest request) {
        host.metrics().increment(PolicyMetrics.Counter.BLOCKED_CLIENT_CERT);
        request.cancel();
    }

    @Override
    public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String hostName, String realm) {
        host.metrics().increment(PolicyMetrics.Counter.BLOCKED_HTTP_AUTH);
        handler.cancel();
    }

    /** Android's saved-credential login flow is never used: this wrapper has no credential store. */
    @Override
    @SuppressWarnings("deprecation")
    public void onReceivedLoginRequest(WebView view, String realm, String account, String args) {
        host.metrics().increment(PolicyMetrics.Counter.IGNORED_INCOMING_INTENTS);
    }

    @Override
    public void onSafeBrowsingHit(WebView view, WebResourceRequest request, int threatType,
                                  SafeBrowsingResponse callback) {
        host.metrics().increment(PolicyMetrics.Counter.BLOCKED_SAFE_BROWSING);
        String url = request == null || request.getUrl() == null ? "" : request.getUrl().toString();
        host.metrics().recordBlock(BlockReason.CAPABILITY_DISABLED, "", url);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // Go back to the last safe page instead of showing the unsafe one.
            callback.backToSafety(true);
        }
        host.onPageLoadBlocked(url, null);
    }

    /**
     * A lost renderer process would otherwise take the whole app down. Reporting the event as handled
     * keeps the app alive, but the WebView that lost its renderer is dead from this moment on: the
     * platform requires it to be removed and destroyed, and a {@code loadUrl} on it would only ever
     * show a blank view. So this does not "recover" the view - it hands it to the host to be replaced
     * (primary view) or closed (pop-up / sign-in window).
     */
    @Override
    public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
        host.metrics().increment(PolicyMetrics.Counter.RENDERER_GONE);
        boolean crashed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && detail != null && detail.didCrash();
        host.onRenderProcessGone(view, crashed);
        return true;
    }

    // ---------------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------------

    /** Accessor for subclasses (the hidden-popup router). */
    protected GuardHost host() {
        return host;
    }

    protected void recordBlock(PolicyResult result, String url, boolean mainFrame, boolean redirect) {
        PolicyMetrics metrics = host.metrics();
        metrics.increment(mainFrame
                ? PolicyMetrics.Counter.BLOCKED_NAVIGATIONS
                : PolicyMetrics.Counter.BLOCKED_REQUESTS);
        if (redirect) {
            metrics.increment(PolicyMetrics.Counter.BLOCKED_REDIRECTS);
        }
        metrics.recordBlock(result == null ? BlockReason.NULL_URL : result.reason(),
                result == null ? "" : result.canonicalHost(), url);
    }

    private static boolean isRedirect(WebResourceRequest request) {
        // WebResourceRequest.isRedirect() exists since API 24, which is this app's minSdk.
        try {
            return request.isRedirect();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void postToUi(WebView view, Runnable action) {
        if (view != null) {
            view.post(action);
        } else {
            action.run();
        }
    }
}
