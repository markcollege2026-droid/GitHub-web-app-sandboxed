package app.arenawebview.locked.web;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import app.arenawebview.locked.net.EgressProxy;
import app.arenawebview.locked.policy.PolicyConfig;
import app.arenawebview.locked.policy.PolicyMetrics;
import app.arenawebview.locked.policy.PolicyResult;

/**
 * Points every WebView in this process at the in-app {@link EgressProxy}, and says whether that has
 * happened yet.
 *
 * <p>The proxy override is process-wide and asynchronous: {@code ProxyController.setProxyOverride}
 * reports through a listener when Chromium has switched over. Until that moment a load would go out
 * directly, so the activity must not load anything before {@link #isReady()} is true - and if the
 * override can never be installed (a WebView too old to support it, or the loopback listener cannot be
 * opened) the app shows why and loads nothing. That is the fail-closed choice: the alternative, a
 * WebView whose WebSockets the policy cannot see, is exactly what this class exists to remove.</p>
 *
 * <p>Install once per process; the proxy and the override live for the process lifetime. If the proxy
 * ever stops, Chromium's connections fail (there is no direct fallback in the override) and the next
 * {@link #install} re-creates it on a new port.</p>
 */
public final class EgressGuard {

    private static final String TAG = "GitHubWebViewLocked";

    /** Outcome of an {@link #install} call, delivered on the main thread. */
    public interface Callback {
        void onReady(int port);

        void onUnavailable(String reason);
    }

    public enum State { NOT_STARTED, STARTING, READY, FAILED }

    private static final Object LOCK = new Object();
    private static State state = State.NOT_STARTED;
    private static EgressProxy proxy;
    private static String failure = "";
    private static final List<Callback> WAITING = new ArrayList<Callback>();

    private EgressGuard() {
    }

    /** True once Chromium has confirmed that it routes through the running proxy. */
    public static boolean isReady() {
        synchronized (LOCK) {
            return state == State.READY && proxy != null && proxy.isRunning();
        }
    }

    public static State state() {
        synchronized (LOCK) {
            return state;
        }
    }

    /** Why the last install failed, or an empty string. */
    public static String failure() {
        synchronized (LOCK) {
            return failure;
        }
    }

    /** Port of the running proxy, or -1. */
    public static int port() {
        synchronized (LOCK) {
            return proxy == null || !proxy.isRunning() ? -1 : proxy.port();
        }
    }

    /**
     * Start the proxy (if needed) and install the override (if needed). Must be called on the main
     * thread; the callback is invoked on the main thread, possibly before this method returns.
     */
    public static void install(final PolicyConfig policy, final PolicyMetrics metrics, final Callback callback) {
        final Handler main = new Handler(Looper.getMainLooper());
        synchronized (LOCK) {
            if (state == State.READY && proxy != null && proxy.isRunning()) {
                final int port = proxy.port();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onReady(port);
                    }
                });
                return;
            }
            if (state == State.STARTING) {
                WAITING.add(callback);
                return;
            }
            state = State.STARTING;
            WAITING.add(callback);
        }

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            fail("this device's WebView is too old to route its traffic through the app "
                    + "(no PROXY_OVERRIDE support); update Android System WebView / Chrome");
            return;
        }

        final EgressProxy running;
        try {
            synchronized (LOCK) {
                if (proxy != null && !proxy.isRunning()) {
                    proxy.close();
                    proxy = null;
                }
                if (proxy == null) {
                    proxy = EgressProxy.start(policy, metrics, new EgressProxy.Listener() {
                        @Override
                        public void onTunnelRefused(String host, int port, PolicyResult result) {
                            Log.w(TAG, "egress refused " + result.describe());
                        }

                        @Override
                        public void onTunnelOpened(String host) {
                            // Routine; logged at verbose level only to keep the device log readable.
                            Log.v(TAG, "egress tunnel " + host);
                        }

                        @Override
                        public void onBadRequest(String requestLine) {
                            Log.w(TAG, "egress rejected a request that is not a CONNECT: " + requestLine);
                        }

                        @Override
                        public void onProxyError(String what, Throwable error) {
                            Log.e(TAG, "egress proxy error in " + what, error);
                        }
                    });
                }
                running = proxy;
            }
        } catch (Throwable t) {
            Log.e(TAG, "could not start the egress proxy", t);
            fail("the app could not open its local network guard (" + t.getClass().getSimpleName() + ")");
            return;
        }

        // One rule for *all* URL schemes (https, wss, http, ws, ...) and no direct fallback: if the proxy
        // is unreachable the connection fails, it never goes around the guard. Chromium's implicit
        // bypass for localhost and link-local addresses is removed too, so even a page-script connection
        // to a service on the device itself arrives at the proxy - where the policy refuses it, as it
        // refuses every IP-literal and single-label host. (The manifest forbids cleartext, so http:// and
        // ws:// never get as far as the proxy either.)
        final ProxyConfig config = new ProxyConfig.Builder()
                .addProxyRule(EgressProxy.LOOPBACK_HOST + ":" + running.port())
                .removeImplicitRules()
                .build();
        Executor onMain = new Executor() {
            @Override
            public void execute(Runnable command) {
                main.post(command);
            }
        };
        try {
            ProxyController.getInstance().setProxyOverride(config, onMain, new Runnable() {
                @Override
                public void run() {
                    ready(running.port());
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "could not install the proxy override", t);
            fail("the WebView refused the app's network guard (" + t.getClass().getSimpleName() + ")");
        }
    }

    private static void ready(int port) {
        List<Callback> callbacks;
        synchronized (LOCK) {
            state = State.READY;
            failure = "";
            callbacks = new ArrayList<Callback>(WAITING);
            WAITING.clear();
        }
        Log.i(TAG, "egress guard ready: every WebView connection now goes through "
                + EgressProxy.LOOPBACK_HOST + ":" + port);
        for (Callback callback : callbacks) {
            callback.onReady(port);
        }
    }

    private static void fail(final String reason) {
        final List<Callback> callbacks;
        synchronized (LOCK) {
            state = State.FAILED;
            failure = reason;
            callbacks = new ArrayList<Callback>(WAITING);
            WAITING.clear();
        }
        Log.e(TAG, "egress guard unavailable: " + reason);
        Runnable deliver = new Runnable() {
            @Override
            public void run() {
                for (Callback callback : callbacks) {
                    callback.onUnavailable(reason);
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            deliver.run();
        } else {
            new Handler(Looper.getMainLooper()).post(deliver);
        }
    }
}
