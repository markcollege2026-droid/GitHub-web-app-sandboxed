package app.arenawebview.locked.web;

import android.view.View;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

import app.arenawebview.locked.policy.PolicyConfig;
import app.arenawebview.locked.policy.PolicyMetrics;
import app.arenawebview.locked.policy.PolicyResult;

/**
 * Everything the guard clients need from the activity, expressed as an interface so that the clients
 * can be driven by an instrumented test without an activity (and so the activity stays the only place
 * that touches the UI).
 */
public interface GuardHost {

    /** Active policy. */
    PolicyConfig policy();

    /** Metrics sink. */
    PolicyMetrics metrics();

    /** True when {@code view} is the one and only visible WebView (as opposed to a hidden popup window). */
    boolean isPrimaryWebView(WebView view);

    /**
     * Load a URL that has already been verified, into the single primary WebView. Implementations must
     * re-verify and must be the only place in the app that calls {@code WebView.loadUrl}.
     */
    void loadVerifiedDestination(String url, String source);

    /**
     * A navigation was refused before it started (main frame, iframe, redirect, custom scheme, form
     * POST). The previously loaded page is still on screen, so this is informational.
     */
    void onNavigationBlocked(String url, PolicyResult result);

    /**
     * A main-frame <em>request</em> was refused at the network layer, which means the page itself could
     * not load. The host must make sure the user cannot be looking at unverified content.
     */
    void onPageLoadBlocked(String url, PolicyResult result);

    /** The main frame failed to load for a non-policy reason (no network, TLS, DNS, ...). */
    void onLoadError(String url, String description);

    /**
     * The primary WebView finished loading a page that passed verification. Informational: the host uses
     * it to end a user-requested reload (pull-to-refresh spinner); nothing security-relevant hangs off it.
     */
    void onPageFinished(String url);

    /** A TLS error was refused (the app never proceeds through a certificate error). */
    void onSslErrorBlocked(String url);

    /** The WebView ended up somewhere it must not be; the host must reset it. */
    void onRecoveryRequired(String reason, String offendingUrl);

    /**
     * The renderer process behind {@code view} is gone - it crashed or the system killed it for memory.
     *
     * <p>This is different from {@link #onRecoveryRequired}: the WebView object itself is now dead and
     * the platform contract is that it must be removed from the view hierarchy and destroyed; calling
     * {@code loadUrl} on it does nothing useful. The host must therefore <em>replace</em> the primary
     * WebView (and start it on the verified start page) or <em>close</em> the pop-up/sign-in window it
     * belonged to. Never load anything into {@code view}.</p>
     *
     * @param view    the WebView whose renderer died
     * @param crashed true when the renderer crashed, false when the system reclaimed it (both are handled
     *                the same way; the flag exists for the log)
     */
    void onRenderProcessGone(WebView view, boolean crashed);

    /** A popup/window.open target passed the policy and was re-routed into the single WebView. */
    void onPopupRouted(String url);

    /** A popup/window.open target was refused before any window could be shown. */
    void onPopupBlocked(String url, PolicyResult result);

    /** A popup window was torn down. */
    void onPopupClosed(WebView popup);

    /**
     * A pop-up turned out to be an explicitly approved sign-in hand-off and must be shown as a real
     * window <em>inside this app</em>, still driven by Chromium.
     *
     * <p>This exists for providers whose OAuth flow returns its result through
     * {@code window.opener}/postMessage. Re-loading the URL in the main WebView - which is what happens
     * to every other pop-up - would break that contract, so the window is shown instead. It is
     * <b>never</b> handed to a system browser and it is policed by exactly the same policy as the main
     * view: it may only display sign-in hosts and GitHub.com pages.</p>
     *
     * @param popup the WebView Chromium created for the window; the host must attach it and must destroy
     *              it when the window closes
     * @param url   the verified sign-in URL that window is about to load
     */
    void onSignInWindowRequested(WebView popup, String url);

    /** A sign-in window closed (either {@code window.close()} by the page or the user closing it). */
    void onSignInWindowClosed(String reason);

    /**
     * A sign-in window navigated to another allowed URL of the hand-off. Informational: it exists so the
     * steps of a sign-in are visible in the log when someone debugs a login that does not complete.
     */
    void onSignInWindowNavigated(String url);

    /** A download was refused (nothing is ever handed to a download manager or browser). */
    void onDownloadBlocked(String url, String mimeType);

    /** The page asked to open a local file picker; refused because it would leave the app. */
    void onFileChooserBlocked();

    /** Camera/microphone request refused. */
    void onMediaPermissionDenied(String origin, String[] resources);

    /** Geolocation request refused. */
    void onGeolocationDenied(String origin);

    void onJsAlert(String url, String message, JsResult result);

    void onJsConfirm(String url, String message, JsResult result);

    void onJsPrompt(String url, String message, String defaultValue, JsPromptResult result);

    void onFullscreenRequested(View view, WebChromeClient.CustomViewCallback callback);

    void onFullscreenExitRequested();

    /** Transient, in-app message for the user. */
    void onMessage(String message);
}
