package app.arenawebview.locked;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.util.Log;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import app.arenawebview.locked.policy.PolicyMetrics;
import app.arenawebview.locked.web.EgressGuard;

/**
 * The egress guard on a real device: the in-app proxy is running, the WebView is routed through it, and
 * a connection the policy does not allow is refused by the proxy itself - including a WebSocket, which no
 * {@code WebViewClient} hook ever sees.
 */
@RunWith(AndroidJUnit4.class)
public class EgressGuardTest {

    private static final String TAG = "GitHubWebViewLocked";
    private static final long READY_TIMEOUT_MS = 15_000L;
    private static final long PROBE_TIMEOUT_MS = 20_000L;

    @Test
    public void theGuardIsReadyBeforeAnythingLoadsAndRefusesForeignTunnels() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitReady();
            int port = EgressGuard.port();
            assertTrue("proxy port", port > 0);

            // The WebView must not have been given anything to show before the guard was ready; whatever
            // it shows now is on the start host.
            scenario.onActivity(activity -> {
                WebView webView = activity.findViewById(R.id.arena_webview);
                String url = webView.getUrl();
                assertTrue("url=" + url, url == null || url.isEmpty() || url.startsWith("https://github.com/"));
            });

            long blockedBefore = PolicyMetrics.get().count(PolicyMetrics.Counter.BLOCKED_TUNNELS);

            assertEquals("HTTP/1.1 403 Forbidden", connect(port, "CONNECT evil.example:443 HTTP/1.1\r\n\r\n"));
            assertEquals("HTTP/1.1 403 Forbidden", connect(port, "CONNECT github.com:8443 HTTP/1.1\r\n\r\n"));
            assertEquals("HTTP/1.1 403 Forbidden", connect(port, "CONNECT github.com.evil.example:443 HTTP/1.1\r\n\r\n"));
            assertEquals("HTTP/1.1 403 Forbidden", connect(port, "CONNECT 10.0.2.2:443 HTTP/1.1\r\n\r\n"));
            assertEquals("HTTP/1.1 400 Bad Request", connect(port, "GET http://github.com/ HTTP/1.1\r\nHost: github.com\r\n\r\n"));

            // An allowed host is never refused by the guard: it is either tunnelled (online) or the
            // upstream connection fails (offline emulator). Neither is a policy decision.
            String allowed = connect(port, "CONNECT github.com:443 HTTP/1.1\r\n\r\n");
            Log.i(TAG, "egress probe: CONNECT github.com:443 -> " + allowed);
            assertNotEquals("HTTP/1.1 403 Forbidden", allowed);
            assertNotEquals("HTTP/1.1 400 Bad Request", allowed);

            // At least the five above; the live page may have added refusals of its own meanwhile.
            assertTrue(PolicyMetrics.get().count(PolicyMetrics.Counter.BLOCKED_TUNNELS) >= blockedBefore + 5);
            assertTrue("the refused tunnel must appear in the diagnostics report",
                    recordedTunnelTo("evil.example"));
            Log.i(TAG, "egress tunnels opened so far: "
                    + PolicyMetrics.get().count(PolicyMetrics.Counter.TUNNELS_OPENED));
        }
    }

    /**
     * A WebSocket opened by page script is the case the request hooks cannot see. With the guard in
     * place it still ends up as a {@code CONNECT} on the proxy, where the policy refuses it.
     *
     * <p>The script runs in a plain helper WebView showing an inline document, so the outcome depends
     * only on Chromium's network stack being routed - not on the live site's Content-Security-Policy,
     * which may well forbid the socket before any connection is attempted.</p>
     */
    @Test
    public void aWebSocketToAForeignHostIsRefusedByTheProxy() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitReady();
            final int before = tunnelRefusalsTo("evil.example");
            final AtomicReference<WebView> helper = new AtomicReference<WebView>();
            final AtomicReference<String> started = new AtomicReference<String>();
            final CountDownLatch scriptDone = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                WebView view = new WebView(activity);
                view.getSettings().setJavaScriptEnabled(true);
                view.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView v, String url) {
                        if (scriptDone.getCount() == 0) {
                            return;
                        }
                        v.evaluateJavascript("""
                                (function () {
                                  try {
                                    var ws = new WebSocket('wss://evil.example/socket');
                                    ws.onerror = function () {};
                                    ws.onclose = function () {};
                                    return 'started';
                                  } catch (e) {
                                    return 'threw:' + e;
                                  }
                                })()
                                """, value -> {
                            started.set(value);
                            scriptDone.countDown();
                        });
                    }
                });
                helper.set(view);
                view.loadDataWithBaseURL(null, "<!doctype html><html><body>websocket probe</body></html>",
                        "text/html", "utf-8", null);
            });
            assertTrue("probe page did not load", scriptDone.await(15, TimeUnit.SECONDS));
            Log.i(TAG, "websocket probe: " + started.get());
            assertEquals("\"started\"", started.get());

            awaitTunnelRefusal("evil.example", before);
            Log.i(TAG, "websocket probe diagnostics:\n" + PolicyMetrics.get().snapshot());
            scenario.onActivity(activity -> helper.get().destroy());
        }
    }

    /**
     * The override is for every WebView in the process, so a plain WebView with no client of its own is
     * guarded too: its navigation to a foreign host is stopped at the proxy, nothing is resolved or sent.
     */
    @Test
    public void everyWebViewInTheProcessIsRouted() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitReady();
            final int before = tunnelRefusalsTo("evil.example");
            final AtomicReference<WebView> extra = new AtomicReference<WebView>();
            scenario.onActivity(activity -> {
                WebView view = new WebView(activity);
                extra.set(view);
                view.loadUrl("https://evil.example/via-second-webview");
            });
            awaitTunnelRefusal("evil.example", before);
            scenario.onActivity(activity -> extra.get().destroy());
        }
    }

    // --- helpers ----------------------------------------------------------------------------------

    private static void awaitReady() throws InterruptedException {
        long deadline = System.currentTimeMillis() + READY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline && !EgressGuard.isReady()) {
            assertFalse("egress guard failed: " + EgressGuard.failure(),
                    EgressGuard.state() == EgressGuard.State.FAILED);
            Thread.sleep(100);
        }
        assertTrue("egress guard not ready within " + READY_TIMEOUT_MS + "ms (state " + EgressGuard.state()
                + ", failure '" + EgressGuard.failure() + "')", EgressGuard.isReady());
    }

    private static boolean recordedTunnelTo(String host) {
        return tunnelRefusalsTo(host) > 0;
    }

    /** How many refused tunnels to {@code host} the diagnostics report currently lists. */
    private static int tunnelRefusalsTo(String host) {
        int n = 0;
        for (PolicyMetrics.BlockEvent event : PolicyMetrics.get().recentBlocks()) {
            if (event.url().startsWith("connect://" + host + ":")) {
                n++;
            }
        }
        return n;
    }

    /** Wait for a new refused tunnel to {@code host} - i.e. for the connection to have reached the proxy. */
    private static void awaitTunnelRefusal(String host, int before) throws InterruptedException {
        long deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline && tunnelRefusalsTo(host) <= before) {
            Thread.sleep(250);
        }
        assertTrue("the connection to " + host + " must have reached the proxy and been refused there "
                + "(refusals before: " + before + ", now: " + tunnelRefusalsTo(host) + ")\n"
                + PolicyMetrics.get().snapshot(), tunnelRefusalsTo(host) > before);
    }

    /** Talk to the proxy the way Chromium does and return the status line of its reply. */
    private static String connect(int port, String request) throws IOException {
        try (Socket socket = new Socket()) {
            // The literal Chromium is given. (Android's getLoopbackAddress() is ::1, which must NOT work.)
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 2000);
            socket.setSoTimeout(15_000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int prev = -1;
            int b;
            while ((b = in.read()) >= 0) {
                if (prev == '\r' && b == '\n') {
                    byte[] bytes = line.toByteArray();
                    return new String(bytes, 0, bytes.length - 1, StandardCharsets.US_ASCII);
                }
                line.write(b);
                prev = b;
            }
            return line.toString("US-ASCII");
        }
    }

    private static String evaluate(ActivityScenario<MainActivity> scenario, String script) throws Exception {
        final AtomicReference<String> result = new AtomicReference<String>();
        final CountDownLatch latch = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            WebView webView = activity.findViewById(R.id.arena_webview);
            assertNotNull(webView);
            webView.evaluateJavascript(script, value -> {
                result.set(value);
                latch.countDown();
            });
        });
        assertTrue("script did not complete", latch.await(15, TimeUnit.SECONDS));
        return result.get();
    }
}
