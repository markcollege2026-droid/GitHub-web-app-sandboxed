package app.githubwebview.locked;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.app.NotificationManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebHistoryItem;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

import app.githubwebview.locked.net.BackgroundDownloadService;
import app.githubwebview.locked.net.DownloadStateStore;
import app.githubwebview.locked.net.FileDownloads;
import app.githubwebview.locked.policy.GitHubAllowlist;
import app.githubwebview.locked.policy.BackNavigator;
import app.githubwebview.locked.policy.NavigationStateTracker;
import app.githubwebview.locked.policy.PolicyConfig;
import app.githubwebview.locked.policy.PolicyMetrics;
import app.githubwebview.locked.policy.PolicyResult;
import app.githubwebview.locked.ui.AuthWindow;
import app.githubwebview.locked.ui.PullToRefreshFrame;
import app.githubwebview.locked.ui.StatusOverlay;
import app.githubwebview.locked.web.GitHubWebChromeClient;
import app.githubwebview.locked.web.GitHubWebViewClient;
import app.githubwebview.locked.web.EgressGuard;
import app.githubwebview.locked.web.GuardHost;
import app.githubwebview.locked.web.WebViewHardening;

/**
 * The whole app: one WebView showing github.com, and the guards that keep it there.
 *
 * <p>Invariants this class maintains (also asserted by the tests):</p>
 * <ul>
 *   <li>{@link #loadVerifiedDestination(String, String)} is the <b>only</b> place that calls
 *       {@code WebView.loadUrl} for the visible WebView, and it re-checks the policy first.</li>
 *   <li>{@code startActivity}/{@code startActivityForResult} are overridden to refuse: this app never
 *       hands control to another application, not even for a file picker or a share sheet. Downloads are
 *       fetched and saved <em>by the app itself</em> through {@link FileDownloads}, which uses the same
 *       loopback egress guard as the WebView.</li>
 *   <li>Incoming intents are ignored completely - the activity has no deep-link handling, so another
 *       app cannot steer it.</li>
 *   <li>Every connection the WebView opens - including WebSockets - goes through an in-app loopback
 *       proxy that only tunnels to allow-listed hosts on port 443; nothing is loaded until that guard
 *       is confirmed installed.</li>
 *   <li>A watchdog re-checks the visible URL on every resume and every 3 s after; an actual
 *       unauthorized top-level navigation is replaced by the last verified approved page (not
 *       blindly by the start page), while a download redirect to a resource-only storage host
 *       during a download in progress is not a navigation and never resets the page.</li>
 *   <li>Pull down from the top edge of the page (or press F5 / Ctrl+R) to reload it; the URL is verified
 *       again before the reload.</li>
 *   <li>Back never leaves the app: it goes back through verified history, falls back to the start page,
 *       and otherwise asks for a second press before closing.</li>
 * </ul>
 */
public class MainActivity extends Activity implements GuardHost, FileDownloads.Listener {

    private static final String TAG = "GitHubWebViewLocked";

    /**
     * The watchdog is the backstop behind the per-navigation checks ({@code onPageStarted},
     * {@code onPageFinished}, {@code doUpdateVisitedHistory} - which also covers {@code pushState}). Those
     * fire on every commit, so the timer only has to catch what no callback reports; it also runs once
     * immediately on every resume. 3 s keeps it a safety net rather than a busy loop.
     */
    private static final long WATCHDOG_INTERVAL_MS = 3000L;
    /** A user-requested reload that has not finished by then is simply no longer shown as "refreshing". */
    private static final long REFRESH_SPINNER_TIMEOUT_MS = 20_000L;
    private static final long EXIT_BACK_WINDOW_MS = 2500L;
    private static final long MESSAGE_THROTTLE_MS = 900L;

    private final PolicyConfig policy = GitHubAllowlist.productionConfig();
    private final PolicyMetrics metrics = PolicyMetrics.get();
    /**
     * Last verified approved page plus download state. The watchdog and every recovery path return
     * to {@link NavigationStateTracker#recoveryUrl()} (the page the user was viewing), never
     * blindly to the start page, and a download redirect to a resource-only storage host is never
     * recorded as a page and never triggers a reset while the download is in progress.
     */
    private final NavigationStateTracker navigationTracker =
            new NavigationStateTracker(policy, policy.primaryUrl());

    private FrameLayout root;
    private PullToRefreshFrame pullToRefresh;
    /** A verified load asked for while the egress guard was still starting; replayed by beginSession. */
    private String deferredUrl;
    private String deferredSource;
    private WebView webView;
    private GitHubWebChromeClient chromeClient;
    private StatusOverlay overlay;
    private Runnable refreshSpinnerTimeout;

    /**
     * The in-app window a sign-in hand-off is shown in (Google's account pages). Null-hosted most of the
     * time; created once with the view hierarchy. {@link #signInWindowWebView} is the pop-up WebView
     * currently living inside it.
     */
    private AuthWindow authWindow;
    private WebView signInWindowWebView;

    /**
     * Chrome-level guard for the sign-in window. It has to be a {@code WebChromeClient} of its own for two
     * reasons: {@code window.close()} - which the returning OAuth page calls to finish the hand-off - is
     * only delivered to a chrome client, and a pop-up must not get the platform's default dialogs or
     * permission prompts.
     */
    private GitHubWebChromeClient signInWindowChromeClient;

    private FrameLayout fullscreenContainer;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;

    private Handler handler;
    private Runnable watchdogTask;
    private boolean resumed;

    /** The one downloader. Guarded by the same policy and the same loopback proxy as the WebView. */
    /** Activity-facing facade; it delegates accepted work to BackgroundDownloadService and owns no worker. */
    private FileDownloads downloads;
    private AlertDialog downloadDialog;
    private ProgressBar downloadProgressBar;
    private TextView downloadProgressText;
    private BroadcastReceiver downloadStateReceiver;
    private boolean downloadReceiverRegistered;
    private boolean pendingNotificationPermission;
    private PendingDownload pendingDownload;
    private long lastRenderedDownloadUpdate;
    private static final int REQUEST_POST_NOTIFICATIONS = 7431;

    private static final class PendingDownload {
        final String url, disposition, mime;
        final long length;
        PendingDownload(String url, String disposition, String mime, long length) {
            this.url = url; this.disposition = disposition; this.mime = mime; this.length = length;
        }
    }

    private long lastExitBackPressMs;
    private long lastMessageMs;
    private String lastMessage;

    // ---------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "start with policy:\n" + policy.describe());

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        WebViewHardening.installServiceWorkerGuard(policy, metrics);
        recordIncomingIntent(getIntent());

        handler = new Handler(Looper.getMainLooper());
        DownloadStateStore.recoverInterrupted(getApplicationContext());
        downloads = new FileDownloads(this, policy, metrics, handler, this,
                new FileDownloads.BackgroundStarter() {
                    @Override public boolean start(String url, String disposition, String mime, long length) {
                        return startBackgroundDownload(url, disposition, mime, length);
                    }
                    @Override public void cancel() {
                        BackgroundDownloadService.cancel(MainActivity.this);
                    }
                });

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setFitsSystemWindows(true);
        pullToRefresh = new PullToRefreshFrame(this, new PullToRefreshFrame.Listener() {
            @Override
            public void onRefreshRequested() {
                reloadCurrentPage("pull-to-refresh");
            }
        });
        root.addView(pullToRefresh, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        attachPrimaryWebView();

        overlay = new StatusOverlay(this, new StatusOverlay.Listener() {
            @Override
            public void onReloadRequested() {
                if (!EgressGuard.isReady()) {
                    // The network guard could not be installed earlier; try again before loading anything.
                    startSession(null);
                    return;
                }
                loadVerifiedDestination(policy.primaryUrl(), "overlay-reload");
            }

            @Override
            public void onCopyDiagnosticsRequested() {
                copyDiagnosticsToClipboard();
            }

            @Override
            public void onDismissRequested() {
                overlay.hide();
            }
        });
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        authWindow = new AuthWindow(this, new AuthWindow.Listener() {
            @Override
            public void onCloseRequested() {
                closeSignInWindow("user");
            }
        });
        root.addView(authWindow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        applyWindowInsetsPadding();
        setContentView(root);
        registerBackCallback();

        // Nothing is loaded until every connection of this process is known to go through the in-app
        // egress guard. The WebView stays blank (about:blank needs no network) until then.
        startSession(savedInstanceState);
    }

    /**
     * Install the egress guard, then start the session: a verified restored history if there is one,
     * the start page otherwise. If the guard cannot be installed, nothing is loaded and the overlay says
     * why - the fail-closed outcome.
     */
    private void startSession(final Bundle savedInstanceState) {
        EgressGuard.install(policy, metrics, new EgressGuard.Callback() {
            @Override
            public void onReady(int port) {
                if (isFinishing() || isDestroyed() || webView == null) {
                    return;
                }
                beginSession(savedInstanceState);
            }

            @Override
            public void onUnavailable(String reason) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                deferredUrl = null;
                deferredSource = null;
                overlay.showError(getString(R.string.egress_unavailable, reason));
            }
        });
    }

    /** Runs once the egress guard is ready: restore a saved session if there is one, then load. */
    private void beginSession(Bundle savedInstanceState) {
        WebBackForwardList restoredHistory = null;
        if (savedInstanceState != null) {
            try {
                restoredHistory = webView.restoreState(savedInstanceState);
            } catch (Throwable t) {
                Log.w(TAG, "could not restore WebView state", t);
            }
        }
        // The restored history came from a Bundle, and a Bundle is not a trusted source: verify the entry
        // the WebView is about to show *now*, synchronously, rather than letting it render for up to one
        // watchdog interval first. The watchdog still re-checks it on its first tick.
        boolean restored = restoredHistory != null && verifyRestoredSession(restoredHistory);

        String url = deferredUrl;
        String source = deferredSource;
        deferredUrl = null;
        deferredSource = null;
        if (url != null) {
            // A load that was asked for while the guard was still starting; it is verified again here.
            loadVerifiedDestination(url, source);
        } else if (!restored) {
            loadVerifiedDestination(policy.primaryUrl(), "startup");
        } else {
            Log.i(TAG, "restored WebView state (verified), watchdog re-checks on its first tick");
        }
    }

    /**
     * Create the single primary WebView and put it at the bottom of the view stack - inside the
     * pull-to-refresh container, below the status overlay and the sign-in window. Called once from
     * {@link #onCreate} and again whenever the renderer behind the primary view dies (see
     * {@link #onRenderProcessGone}). This is the only place that assigns {@link #webView} a non-null value.
     */
    private void attachPrimaryWebView() {
        chromeClient = new GitHubWebChromeClient(this, this);
        webView = WebViewHardening.createPrimaryWebView(
                this, new GitHubWebViewClient(this), chromeClient, this, downloads, navigationTracker);
        webView.setId(R.id.github_webview);
        pullToRefresh.addView(webView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /**
     * Reload what is on screen, at the user's request (pull-to-refresh, F5, Ctrl+R). The current URL is
     * verified again first - a reload is a navigation like any other - and a page that no longer passes,
     * or no page at all, becomes the start page instead.
     */
    private void reloadCurrentPage(String source) {
        WebView view = webView;
        if (view == null) {
            return;
        }
        metrics.increment(PolicyMetrics.Counter.USER_RELOADS);
        if (!EgressGuard.isReady()) {
            pullToRefresh.finishRefreshing();
            startSession(null);
            return;
        }
        String current = null;
        try {
            current = view.getUrl();
        } catch (Throwable ignored) {
            // Treated as "no page".
        }
        if (current == null || current.isEmpty() || overlay.isOverlayVisible()) {
            pullToRefresh.finishRefreshing();
            loadVerifiedDestination(policy.primaryUrl(), source + ":start");
            return;
        }
        PolicyResult result = policy.evaluateNavigation(current);
        if (!result.isAllowed()) {
            metrics.increment(PolicyMetrics.Counter.BLOCKED_NAVIGATIONS);
            metrics.recordBlock(result.reason(), result.canonicalHost(), current);
            Log.w(TAG, "reload (" + source + ") refused, current page failed verification: " + result.describe());
            pullToRefresh.finishRefreshing();
            loadVerifiedDestination(policy.primaryUrl(), source + ":unverified");
            return;
        }
        Log.i(TAG, "reload (" + source + "): " + result.canonicalHost());
        showRefreshSpinner();
        view.reload();
    }

    private void showRefreshSpinner() {
        if (!pullToRefresh.isRefreshing()) {
            pullToRefresh.setRefreshing(true);
        }
        if (refreshSpinnerTimeout == null) {
            refreshSpinnerTimeout = new Runnable() {
                @Override
                public void run() {
                    pullToRefresh.finishRefreshing();
                }
            };
        }
        handler.removeCallbacks(refreshSpinnerTimeout);
        handler.postDelayed(refreshSpinnerTimeout, REFRESH_SPINNER_TIMEOUT_MS);
    }

    private void hideRefreshSpinner() {
        if (pullToRefresh != null) {
            pullToRefresh.finishRefreshing();
        }
        if (handler != null && refreshSpinnerTimeout != null) {
            handler.removeCallbacks(refreshSpinnerTimeout);
        }
    }

    /**
     * F5, the dedicated refresh key and Ctrl+R reload, for keyboards and desktop-mode devices. Handled
     * here, ahead of the view hierarchy, because a focused WebView consumes key events for the page; the
     * whole press (down, repeats, up) is swallowed so the page never sees half of it.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && isReloadKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                reloadCurrentPage("keyboard");
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private static boolean isReloadKey(KeyEvent event) {
        int keyCode = event.getKeyCode();
        return keyCode == KeyEvent.KEYCODE_F5 || keyCode == KeyEvent.KEYCODE_REFRESH
                || (keyCode == KeyEvent.KEYCODE_R && event.isCtrlPressed());
    }

    /**
     * After {@code restoreState}, the WebView holds a history whose current entry it is about to load.
     * Refuse the whole restored session unless that entry is an approved page.
     *
     * @param history the list {@code restoreState} returned
     * @return true when the restored current entry passed the policy
     */
    private boolean verifyRestoredSession(WebBackForwardList history) {
        WebView view = webView;
        if (view == null) {
            return false;
        }
        String current = null;
        try {
            WebHistoryItem item = history.getCurrentItem();
            current = item == null ? null : item.getUrl();
            if (current == null || current.isEmpty()) {
                current = view.getUrl();
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not read the restored history", t);
        }
        if (current == null || current.isEmpty()) {
            return false;
        }
        PolicyResult result = policy.evaluateNavigation(current);
        if (!result.isAllowed()) {
            metrics.increment(PolicyMetrics.Counter.BLOCKED_NAVIGATIONS);
            metrics.recordBlock(result.reason(), result.canonicalHost(), current);
            Log.w(TAG, "restored session refused: " + result.describe());
            try {
                view.stopLoading();
                view.clearHistory();
            } catch (Throwable ignored) {
                // The start page is loaded next, which replaces whatever the restore began.
            }
            return false;
        }
        // The restored page passed verification, so it becomes the last verified page. A
        // resource-only host is refused by the document question above and can never be recorded.
        navigationTracker.onVerifiedNavigation(current);
        return true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Deliberately not calling setIntent(): incoming intents can never influence navigation.
        recordIncomingIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (downloadStateReceiver == null) {
            downloadStateReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    applyDownloadSnapshot(DownloadStateStore.read(MainActivity.this));
                }
            };
        }
        IntentFilter filter = new IntentFilter(DownloadStateStore.ACTION_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(downloadStateReceiver, filter);
        }
        downloadReceiverRegistered = true;
        DownloadStateStore.recoverInterrupted(getApplicationContext());
        applyDownloadSnapshot(DownloadStateStore.read(this));
    }

    @Override
    protected void onStop() {
        if (downloadReceiverRegistered && downloadStateReceiver != null) {
            unregisterReceiver(downloadStateReceiver);
            downloadReceiverRegistered = false;
        }
        dismissDownloadDialog();
        super.onStop();
    }

    private void applyDownloadSnapshot(DownloadStateStore.Snapshot snapshot) {
        if (snapshot.state == DownloadStateStore.State.ACTIVE) {
            if (navigationTracker.activeDownloadCount() == 0) navigationTracker.onDownloadStarted();
            if (downloadDialog == null) onDownloadStarted(snapshot.fileName, snapshot.totalBytes);
            onDownloadProgress(snapshot.fileName, snapshot.bytesRead, snapshot.totalBytes);
            return;
        }
        finishTrackedDownload();
        if (snapshot.updatedAt <= lastRenderedDownloadUpdate) return;
        lastRenderedDownloadUpdate = snapshot.updatedAt;
        switch (snapshot.state) {
            case COMPLETE:
                onDownloadSaved(snapshot.fileName, snapshot.location);
                break;
            case CANCELLED:
                onDownloadCancelled(snapshot.fileName);
                break;
            case REFUSED:
                onDownloadRefused(null, snapshot.host, snapshot.message);
                break;
            case FAILED:
            case INTERRUPTED:
                showRetryableDownloadFailure(snapshot);
                break;
            default:
                break;
        }
    }

    private void finishTrackedDownload() {
        // A local refusal or a rejected duplicate must not finish another service-owned download.
        if (DownloadStateStore.read(this).state != DownloadStateStore.State.ACTIVE
                && navigationTracker.activeDownloadCount() > 0) {
            navigationTracker.onDownloadFinished();
        }
    }

    private void showRetryableDownloadFailure(DownloadStateStore.Snapshot snapshot) {
        dismissDownloadDialog();
        if (!canShowDialogs()) {
            onMessage(snapshot.message);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(snapshot.state == DownloadStateStore.State.INTERRUPTED
                        ? "Download interrupted" : "Download failed")
                .setMessage(snapshot.message + "\n\nThe saved link will be revalidated before retrying.")
                .setPositiveButton("Retry", (dialog, which) -> retryLastDownload())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void retryLastDownload() {
        DownloadStateStore.RetryRequest request = DownloadStateStore.retryRequest(this);
        if (request == null) {
            onMessage("No retryable download is available. Open the GitHub link again.");
            return;
        }
        boolean notificationPermission = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (notificationPermission && manager != null && manager.areNotificationsEnabled()) {
            // The service reruns DownloadPlan and reinstalls EgressGuard if this is a fresh process.
            BackgroundDownloadService.retry(this);
        } else {
            // Route through the normal permission gate; only after notification permission is granted
            // will this enqueue the user-requested, policy-revalidated transfer.
            startBackgroundDownload(request.url, request.disposition, request.mimeType, request.contentLength);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        startWatchdog();
    }

    @Override
    protected void onPause() {
        resumed = false;
        stopWatchdog();
        try {
            CookieManager.getInstance().flush();
        } catch (Throwable ignored) {
            // Cookie flushing is best effort.
        }
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) {
            try {
                webView.saveState(outState);
            } catch (Throwable t) {
                Log.w(TAG, "could not save WebView state", t);
            }
        }
    }

    @Override
    protected void onDestroy() {
        stopWatchdog();
        hideRefreshSpinner();
        dismissDownloadDialog();
        if (downloads != null) {
            downloads.shutdown();
        }
        closeSignInWindow("destroy");
        if (chromeClient != null) {
            chromeClient.destroyPopups();
        }
        if (fullscreenView != null) {
            exitFullscreen(false);
        }
        if (webView != null) {
            try {
                webView.stopLoading();
                webView.setWebChromeClient(null);
                webView.setWebViewClient(new WebViewClient());
                removeFromParent(webView);
                webView.destroy();
            } catch (Throwable t) {
                Log.w(TAG, "WebView teardown failed", t);
            }
            webView = null;
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------------------------------------
    // Back navigation - always internal, never another app
    // ---------------------------------------------------------------------------------------------

    private void registerBackCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                        new android.window.OnBackInvokedCallback() {
                            @Override
                            public void onBackInvoked() {
                                handleBackPressed();
                            }
                        });
            } catch (Throwable t) {
                Log.w(TAG, "predictive back registration failed", t);
            }
        }
    }

    /** Legacy hook, still used below API 33 and by the instrumented tests. */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        handleBackPressed();
    }

    /** Single entry point for every back gesture. */
    public void handleBackPressed() {
        if (authWindow != null && authWindow.isWindowVisible()) {
            // Back closes the sign-in window (and destroys its WebView) before anything else, so the
            // auth flow can never swallow the back gesture.
            closeSignInWindow("back");
            return;
        }
        if (fullscreenView != null) {
            exitFullscreen(true);
            return;
        }
        if (overlay != null && overlay.isOverlayVisible()) {
            overlay.hide();
            String current = webView == null ? null : webView.getUrl();
            if (current == null || !policy.evaluateNavigation(current).isAllowed()) {
                loadVerifiedDestination(policy.primaryUrl(), "back-from-overlay");
            }
            return;
        }
        if (webView == null) {
            finish();
            return;
        }
        BackNavigator.Action action = BackNavigator.decide(webView.canGoBack(), previousHistoryUrl(), policy);
        switch (action) {
            case GO_BACK:
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    loadVerifiedDestination(policy.primaryUrl(), "back-fallback");
                }
                break;
            case SHOW_START:
                loadVerifiedDestination(policy.primaryUrl(), "back-unverified-history");
                break;
            case EXIT:
            default:
                confirmExit();
                break;
        }
    }

    private String previousHistoryUrl() {
        try {
            WebBackForwardList list = webView.copyBackForwardList();
            int index = list.getCurrentIndex();
            if (index <= 0) {
                return null;
            }
            WebHistoryItem item = list.getItemAtIndex(index - 1);
            return item == null ? null : item.getUrl();
        } catch (Throwable t) {
            return null;
        }
    }

    private void confirmExit() {
        long now = SystemClock.uptimeMillis();
        if (now - lastExitBackPressMs <= EXIT_BACK_WINDOW_MS) {
            finish();
            return;
        }
        lastExitBackPressMs = now;
        onMessage("Press back again to close");
    }

    // ---------------------------------------------------------------------------------------------
    // Escape prevention: this app never starts another activity
    // ---------------------------------------------------------------------------------------------

    @Override
    public void startActivity(Intent intent) {
        blockExternalIntent(intent);
    }

    @Override
    public void startActivity(Intent intent, Bundle options) {
        blockExternalIntent(intent);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void startActivityForResult(Intent intent, int requestCode) {
        blockExternalIntent(intent);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void startActivityForResult(Intent intent, int requestCode, Bundle options) {
        blockExternalIntent(intent);
    }

    @Override
    public void startActivities(Intent[] intents) {
        blockExternalIntents(intents);
    }

    @Override
    public void startActivities(Intent[] intents, Bundle options) {
        blockExternalIntents(intents);
    }

    private void blockExternalIntents(Intent[] intents) {
        if (intents == null) {
            return;
        }
        for (Intent intent : intents) {
            blockExternalIntent(intent);
        }
    }

    /**
     * Refuses to open anything. Called for every {@code startActivity} the WebView (or a page) tries -
     * including the system browser, share sheets, the Play Store and download managers.
     */
    private void blockExternalIntent(Intent intent) {
        metrics.increment(PolicyMetrics.Counter.BLOCKED_EXTERNAL_INTENTS);
        String target = "";
        if (intent != null) {
            Uri data = intent.getData();
            target = (intent.getAction() == null ? "" : intent.getAction())
                    + (data == null ? "" : " " + data.getScheme() + "://" + data.getHost());
            if (intent.getComponent() != null) {
                target = target + " component=" + intent.getComponent().flattenToShortString();
            }
        }
        metrics.recordBlock(app.githubwebview.locked.policy.BlockReason.EXTERNAL_INTENT_BLOCKED, "", target);
        Log.i(TAG, "blocked attempt to start another activity: " + target);
        onMessage("This app does not open other apps");
    }

    private void recordIncomingIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        boolean launcherIntent = Intent.ACTION_MAIN.equals(action)
                && intent.hasCategory(Intent.CATEGORY_LAUNCHER)
                && intent.getData() == null;
        if (launcherIntent) {
            return;
        }
        metrics.increment(PolicyMetrics.Counter.IGNORED_INCOMING_INTENTS);
        Log.i(TAG, "ignored incoming intent: action=" + action + " data=" + intent.getData()
                + " (this app has no deep links)");
    }

    // ---------------------------------------------------------------------------------------------
    // GuardHost - the only place that loads anything
    // ---------------------------------------------------------------------------------------------

    @Override
    public PolicyConfig policy() {
        return policy;
    }

    @Override
    public PolicyMetrics metrics() {
        return metrics;
    }

    @Override
    public boolean isPrimaryWebView(WebView view) {
        return view != null && view == webView;
    }

    @Override
    public void loadVerifiedDestination(String url, String source) {
        WebView view = webView;
        if (view == null) {
            return;
        }
        PolicyResult result = policy.evaluateNavigation(url);
        if (!result.isAllowed()) {
            metrics.increment(PolicyMetrics.Counter.BLOCKED_NAVIGATIONS);
            metrics.recordBlock(result.reason(), result.canonicalHost(), url);
            Log.w(TAG, "refused internal load (" + source + "): " + result.describe());
            return;
        }
        if (!EgressGuard.isReady()) {
            // Fail closed: without the egress guard a load would go out directly, WebSockets unseen.
            if (EgressGuard.state() == EgressGuard.State.FAILED) {
                Log.w(TAG, "refused internal load (" + source + "): egress guard unavailable");
                overlay.showError(getString(R.string.egress_unavailable, EgressGuard.failure()));
                return;
            }
            // Still starting: keep the destination for beginSession, which runs when the guard is ready.
            Log.i(TAG, "deferred load (" + source + ") until the egress guard is ready: "
                    + result.canonicalHost());
            deferredUrl = url;
            deferredSource = source;
            return;
        }
        metrics.increment(PolicyMetrics.Counter.ALLOWED_NAVIGATIONS);
        overlay.hide();
        Log.i(TAG, "load (" + source + "): " + result.canonicalHost());
        view.loadUrl(url);
    }

    /** Start a user-requested transfer only when the foreground notification can be shown. */
    private boolean startBackgroundDownload(String url, String disposition, String mime, long length) {
        if (DownloadStateStore.read(this).state == DownloadStateStore.State.ACTIVE) {
            onMessage("A download is already running. Cancel it before starting another.");
            return false;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingDownload = new PendingDownload(url, disposition, mime, length);
            pendingNotificationPermission = true;
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_POST_NOTIFICATIONS);
            return true; // queued behind the system permission prompt; no bytes are fetched yet
        }
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null || !manager.areNotificationsEnabled()) {
            onMessage("Background download not started: enable notifications so its progress and Cancel control are visible.");
            return false;
        }
        boolean started = BackgroundDownloadService.start(this, url, disposition, mime, length);
        if (!started) onMessage("Background download service could not start; nothing was downloaded.");
        return started;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_POST_NOTIFICATIONS || !pendingNotificationPermission) return;
        pendingNotificationPermission = false;
        PendingDownload request = pendingDownload;
        pendingDownload = null;
        if (request == null) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (!startBackgroundDownload(request.url, request.disposition, request.mime, request.length)) {
                onDownloadFailed(DownloadPlanName(request.url), "Background notification could not be shown; nothing was downloaded.");
            }
        } else {
            onDownloadFailed(DownloadPlanName(request.url),
                    "Notification permission was denied. Background download was not started.");
        }
    }

    private String DownloadPlanName(String url) {
        return app.githubwebview.locked.net.DownloadPlan.plan(url, null, null, policy).fileName();
    }

    @Override
    public void onPageFinished(String url) {
        hideRefreshSpinner();
        // The client only reports a finished page after verifying it, and the tracker re-checks
        // before recording, so a resource-only host can never become the last verified page.
        navigationTracker.onVerifiedNavigation(url);
    }

    @Override
    public void onVerifiedNavigation(String url) {
        navigationTracker.onVerifiedNavigation(url);
    }

    @Override
    public boolean isDownloadInProgress() {
        return navigationTracker.isDownloadInProgress()
                || DownloadStateStore.read(this).state == DownloadStateStore.State.ACTIVE;
    }

    @Override
    public void onNavigationBlocked(String url, PolicyResult result) {
        // A resource host can never become the page. When the link is a *file* served by one of them -
        // GitHub's "Download raw file", a patch, an attached image - the honest outcome is an offer to
        // save it, not a dead click that only says "blocked". Nothing is fetched until the user taps Save.
        if (result != null
                && result.reason() == app.githubwebview.locked.policy.BlockReason.DOCUMENT_NOT_ALLOWED
                && url != null && url.regionMatches(true, 0, "https://", 0, 8)
                && canShowDialogs()) {
            offerToSaveInstead(url, result);
            return;
        }
        onMessage("Blocked " + describeDestination(url, result));
    }

    /**
     * Offered when a click lands on an allow-listed host that may only serve resources. The page stays
     * where it was; the file is fetched only if the user says so, through the same guarded downloader.
     */
    private void offerToSaveInstead(final String url, PolicyResult result) {
        String host = destinationHost(url, result);
        new AlertDialog.Builder(this)
                .setTitle("Save this file?")
                .setMessage(host + " serves files for github.com pages, so this app cannot open it as a page.\n\n"
                        + "Save it to Downloads instead?")
                .setPositiveButton("Save", (dialog, which) -> {
                    if (downloads != null && downloads.download(url, null, null, -1L)) {
                        navigationTracker.onDownloadStarted();
                    }
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> onMessage("Nothing was saved"))
                .show();
    }

    @Override
    public void onPageLoadBlocked(String url, PolicyResult result) {
        hideRefreshSpinner();
        if (navigationTracker.shouldSuppressRecovery(url)) {
            // A download redirect to a resource-only storage host is not a page load failure.
            // The page the user was viewing is still on screen; covering it with the blocked
            // overlay would look like the page was reset while the download is in progress.
            // Diagnostics keep the hostname and reason, without any signed URL or cookie.
            Log.i(TAG, "page load blocked for a download resource during download, not showing overlay: host="
                    + destinationHost(url, result));
            return;
        }
        // The page itself failed to load because it was not allowed. Cover the (now broken) WebView with
        // the native overlay and leave it to the user to return to the start page.
        overlay.showBlocked(destinationHost(url, result),
                result == null || result.reason() == null ? "not allowed" : result.reason().humanMessage(),
                url == null ? "" : url);
        onMessage("Blocked " + describeDestination(url, result));
    }

    @Override
    public void onLoadError(String url, String description) {
        hideRefreshSpinner();
        Log.i(TAG, "main frame load error: " + description);
        // A load only ever starts on an allowed URL, so a failure that names a different host means the
        // page was redirected off github.com and the destination was refused. That is a policy decision,
        // not a github.com outage: reporting it as one is why every Help/Docs/Support link and every
        // download looked like the app was broken. Ask the policy which case this is and say so.
        PolicyResult verdict = policy.evaluateNavigation(url);
        if (!verdict.isAllowed()) {
            String host = destinationHost(url, verdict);
            overlay.showBlocked(host, verdict.reason() == null ? "" : verdict.reason().humanMessage(),
                    url == null ? "" : url);
            onMessage("Blocked " + describeDestination(url, verdict));
            return;
        }
        overlay.showError("github.com could not be reached (" + description + ").\n\n"
                + "Check the network connection and try again, or copy the diagnostics report.");
    }

    @Override
    public void onSslErrorBlocked(String url) {
        overlay.showBlocked(destinationHost(url, null),
                "the certificate could not be verified, and this app never bypasses a TLS error",
                url == null ? "" : url);
    }

    @Override
    public void onRecoveryRequired(String reason, String offendingUrl) {
        hideRefreshSpinner();
        if (navigationTracker.shouldSuppressRecovery(offendingUrl)) {
            // A download redirect to a resource-only storage host is not an unauthorized
            // navigation. The WebView stays on the last verified page while the app fetches
            // the file itself; resetting here is the unwanted homepage reset.
            Log.i(TAG, "recovery suppressed for a download resource during download: host="
                    + destinationHost(offendingUrl, null));
            return;
        }
        metrics.increment(PolicyMetrics.Counter.RECOVERY_RELOADS);
        // Recovery diagnostics identify the host and reason only; offending URLs may contain
        // signed artifact query parameters and must never be written to logcat.
        Log.w(TAG, "recovery (" + reason + ") from host=" + destinationHost(offendingUrl, null));
        // A page that ended up somewhere unverified takes its sign-in window down with it.
        closeSignInWindow("recovery:" + reason);
        if (overlay.isOverlayVisible()) {
            // The native overlay is already covering the WebView: nothing unverified is visible.
            return;
        }
        // Return to the page the user was actually viewing, not blindly to the start page.
        loadVerifiedDestination(navigationTracker.recoveryUrl(), "recovery:" + reason);
    }

    /**
     * The renderer behind a WebView died. The dead object is never loaded into again: the primary view is
     * replaced by a fresh one that starts on the verified start page, and a dead sign-in window is simply
     * closed (which is also what its own client does; this is the belt to that brace).
     */
    @Override
    public void onRenderProcessGone(WebView view, boolean crashed) {
        String how = crashed ? "crashed" : "was killed by the system";
        if (view != null && view == signInWindowWebView) {
            Log.w(TAG, "sign-in window renderer " + how + "; closing the window");
            closeSignInWindow("render-process-gone");
            return;
        }
        if (view == null || view != webView) {
            // A hidden pop-up's renderer: PopupRouterClient has already finished that window.
            Log.w(TAG, "pop-up renderer " + how + "; nothing to replace");
            return;
        }
        Log.w(TAG, "primary renderer " + how + "; replacing the WebView");
        metrics.increment(PolicyMetrics.Counter.RECOVERY_RELOADS);
        replacePrimaryWebView(view);
        loadVerifiedDestination(policy.primaryUrl(), "render-process-gone");
        onMessage(getString(R.string.renderer_restarted));
    }

    /**
     * Tear the dead primary WebView down - together with everything that hung off it: sign-in window,
     * hidden pop-ups, fullscreen video - and attach a new one in its place.
     */
    private void replacePrimaryWebView(WebView dead) {
        closeSignInWindow("render-process-gone");
        if (chromeClient != null) {
            chromeClient.destroyPopups();
        }
        if (fullscreenView != null) {
            exitFullscreen(false);
        }
        webView = null;
        chromeClient = null;
        hideRefreshSpinner();
        try {
            dead.stopLoading();
            dead.setWebChromeClient(null);
            dead.setWebViewClient(new WebViewClient());
            removeFromParent(dead);
        } catch (Throwable t) {
            Log.w(TAG, "dead WebView detach failed", t);
        }
        try {
            dead.destroy();
        } catch (Throwable t) {
            Log.w(TAG, "dead WebView destroy failed", t);
        }
        attachPrimaryWebView();
    }

    private static void removeFromParent(View view) {
        if (view != null && view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }
    }

    @Override
    public void onPopupRouted(String url) {
        Log.i(TAG, "popup target verified and loaded in the single WebView");
    }

    @Override
    public void onPopupBlocked(String url, PolicyResult result) {
        onMessage("Pop-up blocked: " + describeDestination(url, result));
    }

    @Override
    public void onPopupClosed(WebView popup) {
        if (popup != null && popup == signInWindowWebView) {
            closeSignInWindow("page-closed");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Sign-in window (the one case where a second WebView may be on screen)
    // ---------------------------------------------------------------------------------------------

    /**
     * A pop-up turned out to be a sign-in hand-off: show it as an in-app window.
     *
     * <p>Idempotent for the same WebView - the hand-off navigates several times (identifier, password,
     * consent) and each one re-enters here.</p>
     */
    @Override
    public void onSignInWindowRequested(WebView popup, String url) {
        if (popup == null || authWindow == null) {
            return;
        }
        if (signInWindowWebView != null && signInWindowWebView != popup) {
            // Only one sign-in window at a time; a second hand-off replaces the first.
            closeSignInWindow("replaced");
        }
        signInWindowWebView = popup;
        try {
            // Nothing hidden: this makes the sign-in window answer window.close() (the OAuth return page
            // ends the hand-off with it) and keeps every dialog, permission prompt and file chooser inside
            // the same guards as the main view.
            signInWindowChromeClient = new GitHubWebChromeClient(this, this);
            popup.setWebChromeClient(signInWindowChromeClient);
        } catch (Throwable t) {
            Log.w(TAG, "could not attach the sign-in window's chrome client", t);
        }
        Log.i(TAG, "sign-in window opened inside the app: " + describeDestination(url, null));
        // The window title says where the sign-in is happening, so the user can see that this is Google's
        // page inside the app and not something the app renders itself.
        authWindow.show(popup, getString(R.string.auth_window_title) + " - " + destinationHost(url, null));
    }

    @Override
    public void onSignInWindowClosed(String reason) {
        Log.i(TAG, "sign-in window closed: " + reason);
    }

    @Override
    public void onSignInWindowNavigated(String url) {
        Log.i(TAG, "sign-in window navigated: " + describeDestination(url, null));
        if (signInWindowWebView != null && authWindow != null && authWindow.isWindowVisible()) {
            // Keep the window's caption honest about which page is inside it.
            authWindow.show(signInWindowWebView, getString(R.string.auth_window_title)
                    + " - " + destinationHost(url, null));
        }
    }

    /**
     * Close and destroy the sign-in window. Closing an OAuth window early is not an error: the flow
     * simply does not complete, and the site stays logged out - which is the fail-closed outcome.
     */
    private void closeSignInWindow(String reason) {
        WebView popup = signInWindowWebView;
        boolean wasOpen = popup != null || (authWindow != null && authWindow.isWindowVisible());
        signInWindowWebView = null;
        GitHubWebChromeClient chrome = signInWindowChromeClient;
        signInWindowChromeClient = null;
        if (chrome != null) {
            // Any window the sign-in page itself opened dies with it; no orphan WebView survives a login.
            chrome.destroyPopups();
        }
        if (authWindow != null) {
            authWindow.hide();
        }
        if (popup != null) {
            try {
                popup.stopLoading();
                popup.setWebViewClient(new WebViewClient());
                popup.destroy();
            } catch (Throwable t) {
                Log.w(TAG, "sign-in window teardown failed", t);
            }
        }
        if (wasOpen) {
            onSignInWindowClosed(reason);
        }
    }

    /**
     * Safety net for the {@link GuardHost} contract. The download listener now routes through
     * {@link FileDownloads} and reports through {@link FileDownloads.Listener} instead, but a refusal that
     * arrives here must never be silent.
     */
    @Override
    public void onDownloadBlocked(String url, String mimeType) {
        overlay.showBlocked(destinationHost(url, null),
                "that download is not allowed, so nothing was saved", url == null ? "" : url);
        onMessage("Download refused: nothing was saved");
    }

    // ---------------------------------------------------------------------------------------------
    // Downloads (FileDownloads.Listener) - fetched and saved by the app, never by another app
    // ---------------------------------------------------------------------------------------------

    @Override
    public void onDownloadRefused(String url, String host, String reason) {
        finishTrackedDownload();
        // Same surface as every other refusal: it names the host, the policy reason, and keeps the
        // diagnostics one tap away. A refused download never navigates the WebView, so the page
        // the user was viewing stays on screen.
        // Keep the useful host/reason diagnostics, but do not surface or retain a signed URL.
        overlay.showBlocked(host, reason, "");
        onMessage("Download refused: " + (reason == null || reason.isEmpty() ? "not allowed" : reason));
    }

    /** The in-app Cancel control, also used by lifecycle tests, always signals the service. */
    public void cancelBackgroundDownload() {
        if (downloads != null) downloads.cancel();
    }

    @Override
    public void onDownloadStarted(String fileName, long totalBytes) {
        // The download was already recorded synchronously when the request was handed to the
        // downloader (GuardedDownloadListener / offerToSaveInstead), so the watchdog cannot tick
        // in between. This callback only drives the progress dialog; it must not count again.
        dismissDownloadDialog();
        if (!canShowDialogs()) {
            return;
        }
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding, padding, padding);
        downloadProgressText = new TextView(this);
        downloadProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        downloadProgressBar.setMax(100);
        downloadProgressBar.setIndeterminate(totalBytes <= 0);
        content.addView(downloadProgressText);
        content.addView(downloadProgressBar);
        downloadDialog = new AlertDialog.Builder(this)
                .setTitle("Downloading")
                .setView(content)
                .setCancelable(true)
                .setNegativeButton("Cancel", (dialog, which) -> cancelBackgroundDownload())
                .setOnCancelListener(dialog -> cancelBackgroundDownload())
                .create();
        downloadDialog.show();
        showDownloadProgress(fileName, 0L, totalBytes);
    }

    @Override
    public void onDownloadProgress(String fileName, long bytesRead, long totalBytes) {
        if (!canShowDialogs()) {
            return;
        }
        showDownloadProgress(fileName, bytesRead, totalBytes);
    }

    @Override
    public void onDownloadSaved(String fileName, String location) {
        finishTrackedDownload();
        dismissDownloadDialog();
        Log.i(TAG, "download saved: " + fileName + " in " + location);
        onMessage("Saved " + fileName + " to " + location);
    }

    @Override
    public void onDownloadFailed(String fileName, String reason) {
        finishTrackedDownload();
        dismissDownloadDialog();
        Log.w(TAG, "download failed: " + fileName + " (" + reason + ")");
        if (!canShowDialogs()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Download failed")
                .setMessage(fileName + "\n\n" + reason + "\n\nNothing was saved.")
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    @Override
    public void onDownloadCancelled(String fileName) {
        finishTrackedDownload();
        dismissDownloadDialog();
        onMessage("Download cancelled: nothing was saved");
    }

    /** Progress on the dialog; indeterminate while the server has not said how large the file is. */
    private void showDownloadProgress(String fileName, long bytesRead, long totalBytes) {
        if (downloadProgressText == null && downloadDialog == null) {
            return;
        }
        if (downloadProgressBar != null) {
            if (totalBytes > 0) {
                downloadProgressBar.setIndeterminate(false);
                downloadProgressBar.setProgress((int) Math.min(100L, (bytesRead * 100L) / totalBytes));
            } else {
                downloadProgressBar.setIndeterminate(true);
            }
        }
        if (downloadProgressText != null) {
            StringBuilder text = new StringBuilder(fileName).append('\n').append(humanBytes(bytesRead));
            if (totalBytes > 0) {
                text.append(" of ").append(humanBytes(totalBytes));
            }
            downloadProgressText.setText(text.toString());
        }
    }

    private void dismissDownloadDialog() {
        if (downloadDialog != null) {
            try {
                downloadDialog.dismiss();
            } catch (Throwable ignored) {
                // a dialog whose activity is already gone is not an error worth reporting
            }
            downloadDialog = null;
        }
        downloadProgressBar = null;
        downloadProgressText = null;
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return (bytes / 1024L) + " KB";
        }
        long tenths = (bytes * 10L) / (1024L * 1024L);
        return (tenths / 10L) + "." + (tenths % 10L) + " MB";
    }

    @Override
    public void onFileChooserBlocked() {
        // Message already sent by the chrome client.
    }

    @Override
    public void onMediaPermissionDenied(String origin, String[] resources) {
        onMessage("Camera/microphone access is disabled in this app");
    }

    @Override
    public void onGeolocationDenied(String origin) {
        onMessage("Location access is disabled in this app");
    }

    @Override
    public void onJsAlert(String url, String message, JsResult result) {
        if (!canShowDialogs()) {
            result.cancel();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(dialogTitle(url))
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> result.confirm())
                .show();
    }

    @Override
    public void onJsConfirm(String url, String message, JsResult result) {
        if (!canShowDialogs()) {
            result.cancel();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(dialogTitle(url))
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> result.confirm())
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> result.cancel())
                .show();
    }

    @Override
    public void onJsPrompt(String url, String message, String defaultValue, JsPromptResult result) {
        if (!canShowDialogs()) {
            result.cancel();
            return;
        }
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        if (defaultValue != null) {
            input.setText(defaultValue);
        }
        new AlertDialog.Builder(this)
                .setTitle(dialogTitle(url))
                .setMessage(message)
                .setView(input)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> result.confirm(input.getText().toString()))
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> result.cancel())
                .show();
    }

    private boolean canShowDialogs() {
        return !isFinishing() && (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !isDestroyed());
    }

    private String dialogTitle(String url) {
        String host = destinationHost(url, null);
        return host.isEmpty() ? getString(R.string.app_name) : host + " says";
    }

    // ---------------------------------------------------------------------------------------------
    // Full-screen video (in-app only)
    // ---------------------------------------------------------------------------------------------

    @Override
    public void onFullscreenRequested(View view, WebChromeClient.CustomViewCallback callback) {
        if (!canShowDialogs() || webView == null) {
            if (callback != null) {
                callback.onCustomViewHidden();
            }
            return;
        }
        if (fullscreenView != null) {
            // Already in full screen: refuse the nested request rather than stack windows.
            if (callback != null) {
                callback.onCustomViewHidden();
            }
            return;
        }
        fullscreenView = view;
        fullscreenCallback = callback;
        fullscreenContainer = new FrameLayout(this);
        fullscreenContainer.setId(R.id.fullscreen_container);
        fullscreenContainer.setBackgroundColor(Color.BLACK);
        fullscreenContainer.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(fullscreenContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        hideSystemBars();
    }

    @Override
    public void onFullscreenExitRequested() {
        exitFullscreen(true);
    }

    private void exitFullscreen(boolean notifyCallback) {
        if (fullscreenView == null) {
            return;
        }
        View view = fullscreenView;
        WebChromeClient.CustomViewCallback callback = fullscreenCallback;
        fullscreenView = null;
        fullscreenCallback = null;
        try {
            if (fullscreenContainer != null) {
                fullscreenContainer.removeView(view);
                root.removeView(fullscreenContainer);
            }
        } catch (Throwable t) {
            Log.w(TAG, "fullscreen teardown failed", t);
        }
        fullscreenContainer = null;
        showSystemBars();
        if (notifyCallback && callback != null) {
            try {
                callback.onCustomViewHidden();
            } catch (Throwable t) {
                Log.w(TAG, "custom view callback failed", t);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        try {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } catch (Throwable ignored) {
            // cosmetic only
        }
    }

    @SuppressWarnings("deprecation")
    private void showSystemBars() {
        try {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } catch (Throwable ignored) {
            // cosmetic only
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Messages, watchdog, diagnostics
    // ---------------------------------------------------------------------------------------------

    @Override
    public void onMessage(String message) {
        if (message == null || message.isEmpty() || isFinishing()) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (message.equals(lastMessage) && now - lastMessageMs < MESSAGE_THROTTLE_MS) {
            return;
        }
        lastMessage = message;
        lastMessageMs = now;
        try {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
            Log.i(TAG, message);
        }
    }

    private void startWatchdog() {
        if (handler == null) {
            return;
        }
        if (watchdogTask == null) {
            watchdogTask = new Runnable() {
                @Override
                public void run() {
                    if (!resumed) {
                        return;
                    }
                    tickWatchdog();
                    handler.postDelayed(this, WATCHDOG_INTERVAL_MS);
                }
            };
        }
        handler.removeCallbacks(watchdogTask);
        // First check right away (a resume is exactly when the screen content is unknown), then periodic.
        handler.post(watchdogTask);
    }

    private void stopWatchdog() {
        if (handler != null && watchdogTask != null) {
            handler.removeCallbacks(watchdogTask);
        }
    }

    /**
     * Last line of defence: whatever the WebView believes it is showing must be an approved page. This
     * catches anything that could ever slip past the interception hooks (a meta refresh, a
     * service-worker navigation, a bug in the WebView, or a future edit to this app).
     *
     * <p>The decision is made by {@link NavigationStateTracker}, which knows the difference between
     * an actual unauthorized top-level navigation and a download redirect to a resource-only
     * storage host. The former returns to the last verified page; the latter leaves the WebView
     * exactly where the user left it. A resource-only host is never recorded as a page.</p>
     */
    private void tickWatchdog() {
        checkSignInWindowUrl();
        WebView view = webView;
        if (view == null) {
            return;
        }
        metrics.increment(PolicyMetrics.Counter.WATCHDOG_CHECKS);
        String current = view.getUrl();
        NavigationStateTracker.WatchdogDecision decision = navigationTracker.checkWatchdog(current);
        switch (decision) {
            case OK:
            case OK_NO_UPDATE:
            case EMPTY:
                return;
            case DOWNLOAD_IN_PROGRESS:
                // A download redirect to a resource-only storage host is not a page navigation.
                // Do not reset, do not record the resource URL as a page, and keep the original
                // approved page on screen. Diagnostics name the host and reason only, never a
                // signed URL or a cookie.
                PolicyResult downloadResult = policy.evaluateNavigation(current);
                Log.i(TAG, "watchdog: download resource during download, not resetting: host="
                        + downloadResult.canonicalHost());
                return;
            case UNAUTHORIZED:
            default:
                break;
        }
        PolicyResult result = policy.evaluateNavigation(current);
        metrics.recordBlock(result.reason(), result.canonicalHost(), current);
        Log.w(TAG, "watchdog found a non-approved URL, resetting: " + result.describe());
        if (overlay.isOverlayVisible()) {
            return;
        }
        loadVerifiedDestination(navigationTracker.recoveryUrl(), "watchdog");
    }

    /**
     * The sign-in window is subject to the same last-line-of-defence check as the main view. It should be
     * impossible for it to hold an off-policy URL (its client refuses such navigations and requests), but
     * if it ever did, the window is closed rather than trusted.
     */
    private void checkSignInWindowUrl() {
        WebView popup = signInWindowWebView;
        if (popup == null) {
            return;
        }
        String current;
        try {
            current = popup.getUrl();
        } catch (Throwable t) {
            return;
        }
        if (current == null || current.isEmpty()) {
            return;
        }
        PolicyResult result = policy.evaluateNavigation(current);
        if (result.isAllowed()) {
            return;
        }
        metrics.recordBlock(result.reason(), result.canonicalHost(), current);
        Log.w(TAG, "sign-in window was on a non-approved URL, closing it: " + result.describe());
        closeSignInWindow("watchdog:" + (result.reason() == null ? "?" : result.reason().name()));
    }

    private void copyDiagnosticsToClipboard() {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("github-webview-locked", diagnosticsReport()));
                onMessage("Diagnostics copied");
            }
        } catch (Throwable t) {
            Log.w(TAG, "clipboard failed", t);
        }
    }

    /** The report shown in logcat and copied by the "Copy diagnostics" button. */
    public String diagnosticsReport() {
        return "policy:\n" + policy.describe()
                + "\nallowed hosts: " + GitHubAllowlist.allowedHosts() + "\n\n"
                + metrics.snapshot();
    }

    private String describeDestination(String url, PolicyResult result) {
        String host = destinationHost(url, result);
        String reason = result == null || result.reason() == null ? "" : result.reason().humanMessage();
        if (host.isEmpty()) {
            return reason.isEmpty() ? "that destination (not allowed)" : reason;
        }
        return reason.isEmpty() ? host : host + " (" + reason + ")";
    }

    private String destinationHost(String url, PolicyResult result) {
        if (result != null && !result.canonicalHost().isEmpty()) {
            return result.canonicalHost();
        }
        if (url == null || url.isEmpty()) {
            return "";
        }
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host != null && !host.isEmpty()) {
                return host.toLowerCase(Locale.ROOT);
            }
            String scheme = uri.getScheme();
            return scheme == null ? "" : scheme + ":";
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Keep the WebView inside the system bars. targetSdk 35 makes Android 15+ draw apps edge to edge by
     * default, which would put the site's own header under the status bar.
     */
    private void applyWindowInsetsPadding() {
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                int left = insets.getSystemWindowInsetLeft();
                int top = insets.getSystemWindowInsetTop();
                int right = insets.getSystemWindowInsetRight();
                int bottom = insets.getSystemWindowInsetBottom();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && insets.getDisplayCutout() != null) {
                    top = Math.max(top, insets.getDisplayCutout().getSafeInsetTop());
                    bottom = Math.max(bottom, insets.getDisplayCutout().getSafeInsetBottom());
                    left = Math.max(left, insets.getDisplayCutout().getSafeInsetLeft());
                    right = Math.max(right, insets.getDisplayCutout().getSafeInsetRight());
                }
                view.setPadding(left, top, right, bottom);
                return insets;
            }
        });
        root.setFitsSystemWindows(true);
        Window window = getWindow();
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
}
