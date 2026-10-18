package app.arenawebview.locked.web;

import android.util.Log;
import android.view.View;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

import app.arenawebview.locked.policy.PolicyConfig;
import app.arenawebview.locked.policy.PolicyMetrics;
import app.arenawebview.locked.policy.PolicyResult;

/**
 * Default no-op {@link GuardHost}. Keeps {@code MainActivity} small and lets tests implement only the
 * callbacks they care about. Every no-op here is a <em>safe</em> no-op: it never loads anything and
 * never opens another app.
 */
public abstract class BaseGuardHost implements GuardHost {

    private static final String TAG = "GitHubWebViewLocked";

    @Override
    public boolean isPrimaryWebView(WebView view) {
        return true;
    }

    @Override
    public void loadVerifiedDestination(String url, String source) {
        Log.w(TAG, "loadVerifiedDestination ignored (no host): " + source);
    }

    @Override
    public void onNavigationBlocked(String url, PolicyResult result) {
        Log.i(TAG, "blocked navigation: " + (result == null ? "?" : result.describe()));
    }

    @Override
    public void onPageLoadBlocked(String url, PolicyResult result) {
        Log.i(TAG, "blocked page load: " + (result == null ? "?" : result.describe()));
    }

    @Override
    public void onLoadError(String url, String description) {
        Log.i(TAG, "load error: " + description);
    }

    @Override
    public void onPageFinished(String url) {
        // Informational only.
    }

    @Override
    public void onSslErrorBlocked(String url) {
        Log.i(TAG, "TLS error refused");
    }

    @Override
    public void onRecoveryRequired(String reason, String offendingUrl) {
        Log.i(TAG, "recovery required: " + reason);
    }

    @Override
    public void onRenderProcessGone(WebView view, boolean crashed) {
        // Safe no-op: nothing is loaded into the dead view. A real host replaces or closes it.
        Log.w(TAG, "renderer gone (" + (crashed ? "crashed" : "killed") + "); no host to replace the view");
    }

    @Override
    public void onPopupRouted(String url) {
        Log.i(TAG, "popup routed into main WebView");
    }

    @Override
    public void onPopupBlocked(String url, PolicyResult result) {
        Log.i(TAG, "popup blocked: " + result.describe());
    }

    @Override
    public void onSignInWindowRequested(WebView popup, String url) {
        // no-op: a test double that does not care about sign-in windows
    }

    @Override
    public void onSignInWindowClosed(String reason) {
        // no-op
    }

    @Override
    public void onSignInWindowNavigated(String url) {
        // no-op
    }

    @Override
    public void onPopupClosed(WebView popup) {
        // nothing to do
    }

    @Override
    public void onDownloadBlocked(String url, String mimeType) {
        Log.i(TAG, "download blocked");
    }

    @Override
    public void onFileChooserBlocked() {
        Log.i(TAG, "file chooser blocked");
    }

    @Override
    public void onMediaPermissionDenied(String origin, String[] resources) {
        Log.i(TAG, "media permission denied");
    }

    @Override
    public void onGeolocationDenied(String origin) {
        Log.i(TAG, "geolocation denied");
    }

    @Override
    public void onJsAlert(String url, String message, JsResult result) {
        result.cancel();
    }

    @Override
    public void onJsConfirm(String url, String message, JsResult result) {
        result.cancel();
    }

    @Override
    public void onJsPrompt(String url, String message, String defaultValue, JsPromptResult result) {
        result.cancel();
    }

    @Override
    public void onFullscreenRequested(View view, WebChromeClient.CustomViewCallback callback) {
        callback.onCustomViewHidden();
    }

    @Override
    public void onFullscreenExitRequested() {
        // nothing to do
    }

    @Override
    public void onMessage(String message) {
        Log.i(TAG, message);
    }

    // policy() and metrics() are intentionally left abstract: a host must always say which policy it
    // enforces and where the counters go. Everything else above is already fail-closed by default.
}
