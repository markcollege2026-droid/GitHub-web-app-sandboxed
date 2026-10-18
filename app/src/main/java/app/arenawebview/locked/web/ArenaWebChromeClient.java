package app.arenawebview.locked.web;

import android.content.Context;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.GeolocationPermissions;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

import java.util.ArrayList;
import java.util.List;

import app.arenawebview.locked.BuildConfig;
import app.arenawebview.locked.policy.PolicyMetrics;

/**
 * Chrome-level guard: pop-ups, JavaScript dialogs, permission prompts, file pickers and full-screen
 * video.
 *
 * <p>Two rules drive every decision here:</p>
 * <ol>
 *   <li>Nothing may create a second visible surface. {@code window.open()}/{@code target="_blank"} is
 *       served by a hidden WebView whose URL is re-validated and then (if allowed) loaded in the single
 *       visible WebView - so the user never leaves the app's one WebView and a pop-up can never
 *       escape it.</li>
 *   <li>Nothing may hand work to another Android app. The file picker is refused (it would launch
 *       DocumentsUI), camera/microphone and geolocation prompts are refused until a build opts in
 *       (see README).</li>
 * </ol>
 */
public class ArenaWebChromeClient extends WebChromeClient {

    private static final String TAG = "GitHubWebViewLocked";

    /** A page that opens more hidden windows than this is refused outright (memory/DoS guard). */
    private static final int MAX_LIVE_POPUPS = 3;

    private final Context context;
    private final GuardHost host;
    private final List<WebView> livePopups = new ArrayList<WebView>();

    public ArenaWebChromeClient(Context context, GuardHost host) {
        if (context == null || host == null) {
            throw new IllegalArgumentException("context and host are required");
        }
        this.context = context;
        this.host = host;
    }

    // ---------------------------------------------------------------------------------------------
    // Pop-ups
    //
    // Two kinds of window come out of onCreateWindow:
    //   * an ordinary window.open()/target=_blank, whose client re-routes the URL into the single
    //     visible WebView and destroys the window (nothing new can appear), and
    //   * a separately approved sign-in hand-off, which may be shown as an in-app window when the
    //     provider's flow requires window.opener/postMessage.
    // The window starts hidden in both cases; only an explicitly approved sign-in case is ever attached
    // to a view hierarchy, and only by the activity (PopupRouterClient asks the host to show it).
    // ---------------------------------------------------------------------------------------------

    @Override
    public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
        PolicyMetrics metrics = host.metrics();
        if (isDialog) {
            // Dialog-style windows (share sheets etc.) are refused outright.
            metrics.increment(PolicyMetrics.Counter.BLOCKED_POPUPS);
            host.onPopupBlocked("dialog", null);
            host.onMessage("Pop-up blocked");
            return false;
        }
        if (resultMsg == null || !(resultMsg.obj instanceof WebView.WebViewTransport)) {
            metrics.increment(PolicyMetrics.Counter.BLOCKED_POPUPS);
            host.onPopupBlocked("no-transport", null);
            return false;
        }
        pruneDeadPopups();
        if (livePopups.size() >= MAX_LIVE_POPUPS) {
            metrics.increment(PolicyMetrics.Counter.BLOCKED_POPUPS);
            host.onPopupBlocked("too-many-windows", null);
            host.onMessage("Pop-up blocked (too many open windows)");
            return false;
        }

        final WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
        final WebView popup = new WebView(context);
        WebViewHardening.applyHardenedSettings(popup.getSettings());
        WebViewHardening.applyCookiePolicy(context, popup);
        livePopups.add(popup);
        popup.setWebViewClient(new PopupRouterClient(host, popup, new Runnable() {
            @Override
            public void run() {
                synchronized (livePopups) {
                    livePopups.remove(popup);
                }
                host.onPopupClosed(popup);
            }
        }));
        transport.setWebView(popup);
        // Handing the hidden WebView over lets Chromium tell us which URL the new window wanted; it is
        // never attached to any view hierarchy, so it can never be seen. A Message without a target
        // handler cannot be answered, which is Chromium's problem to survive, not a reason to crash.
        try {
            resultMsg.sendToTarget();
        } catch (RuntimeException e) {
            metrics.increment(PolicyMetrics.Counter.BLOCKED_POPUPS);
            metrics.recordBlock(app.arenawebview.locked.policy.BlockReason.CAPABILITY_DISABLED,
                    "", "window.open without a usable transport message");
            host.onPopupBlocked("unusable-transport", null);
            onCloseWindow(popup);
            return false;
        }
        return true;
    }

    @Override
    public void onCloseWindow(WebView window) {
        if (window == null) {
            return;
        }
        synchronized (livePopups) {
            livePopups.remove(window);
        }
        // The host tears down any in-app sign-in window that was showing this WebView, then the WebView
        // itself is destroyed.
        host.onPopupClosed(window);
        try {
            window.setWebViewClient(new android.webkit.WebViewClient());
            window.destroy();
        } catch (Throwable ignored) {
            // ignore: the window must simply be gone
        }
    }

    /** Called from the activity's {@code onDestroy}. */
    public void destroyPopups() {
        List<WebView> copy;
        synchronized (livePopups) {
            copy = new ArrayList<WebView>(livePopups);
            livePopups.clear();
        }
        for (WebView popup : copy) {
            try {
                popup.setWebViewClient(new android.webkit.WebViewClient());
                popup.destroy();
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    private void pruneDeadPopups() {
        synchronized (livePopups) {
            for (int i = livePopups.size() - 1; i >= 0; i--) {
                WebView popup = livePopups.get(i);
                if (popup == null) {
                    livePopups.remove(i);
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // JavaScript dialogs (kept working: they are in-app dialogs, not escapes)
    // ---------------------------------------------------------------------------------------------

    @Override
    public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
        host.onJsAlert(url, message, result);
        return true;
    }

    @Override
    public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
        host.onJsConfirm(url, message, result);
        return true;
    }

    @Override
    public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
                              JsPromptResult result) {
        host.onJsPrompt(url, message, defaultValue, result);
        return true;
    }

    // ---------------------------------------------------------------------------------------------
    // Capabilities that would leave the app or add sensors
    // ---------------------------------------------------------------------------------------------

    @Override
    public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
        host.metrics().increment(PolicyMetrics.Counter.BLOCKED_GEOLOCATION);
        if (callback != null) {
            callback.invoke(origin, false, false);
        }
        host.onGeolocationDenied(origin);
    }

    @Override
    public void onPermissionRequest(PermissionRequest request) {
        host.metrics().increment(PolicyMetrics.Counter.BLOCKED_MEDIA_PERMISSIONS);
        if (request != null) {
            String origin = request.getOrigin() == null ? "" : request.getOrigin().toString();
            String[] resources = request.getResources();
            try {
                request.deny();
            } catch (Throwable t) {
                Log.w(TAG, "deny() failed", t);
            }
            host.onMediaPermissionDenied(origin, resources);
        }
    }

    /**
     * The system file picker is refused: it launches another app (DocumentsUI/Camera) and would hand a
     * local file to the WebView, both of which are outside this wrapper's contract. The callback must
     * still be completed or the page's file input stays in a stuck "pending" state.
     */
    @Override
    public boolean onShowFileChooser(WebView webView, android.webkit.ValueCallback<android.net.Uri[]> filePathCallback,
                                     FileChooserParams fileChooserParams) {
        host.metrics().increment(PolicyMetrics.Counter.BLOCKED_FILE_CHOOSERS);
        host.onFileChooserBlocked();
        host.onMessage("File upload is disabled in this app");
        if (filePathCallback != null) {
            try {
                filePathCallback.onReceiveValue(null);
            } catch (Throwable t) {
                Log.w(TAG, "file chooser callback failed", t);
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------
    // Full-screen video (needed for streaming playback) - stays inside the app
    // ---------------------------------------------------------------------------------------------

    @Override
    public void onShowCustomView(View view, CustomViewCallback callback) {
        if (view == null) {
            if (callback != null) {
                callback.onCustomViewHidden();
            }
            return;
        }
        host.onFullscreenRequested(view, callback);
    }

    @Override
    public void onHideCustomView() {
        host.onFullscreenExitRequested();
    }

    // ---------------------------------------------------------------------------------------------
    // Diagnostics
    // ---------------------------------------------------------------------------------------------

    @Override
    public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
        if (BuildConfig.DEBUG && consoleMessage != null) {
            Log.d(TAG, "console: " + consoleMessage.message()
                    + " @" + consoleMessage.sourceId() + ":" + consoleMessage.lineNumber());
        }
        return true;
    }
}
