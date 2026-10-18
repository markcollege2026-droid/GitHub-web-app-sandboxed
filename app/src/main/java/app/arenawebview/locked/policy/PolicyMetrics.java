package app.arenawebview.locked.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Local, in-process record of everything the wrapper allowed and refused.
 *
 * <p>This exists so that the security behaviour of the app is observable on a device: the app can show
 * a copy-to-clipboard report, logcat shows the same counters, and the instrumented tests assert on
 * them. It is deliberately memory-only (nothing is persisted or transmitted) and it contains no user
 * data: recorded URLs have their query string and fragment removed before they are stored.</p>
 *
 * <p>Pure Java; unit tested by {@code PolicyMetricsTest}.</p>
 */
public final class PolicyMetrics {

    /** Counters a test or the in-app report can assert on. */
    public enum Counter {
        /** Main-frame navigations that passed the policy. */
        ALLOWED_NAVIGATIONS,
        /** Documents (main frame / iframes / redirects / popups) that were refused. */
        BLOCKED_NAVIGATIONS,
        /** Non-document requests (scripts, images, XHR/fetch, service workers, ...) that were refused. */
        BLOCKED_REQUESTS,
        /** Refusals that arrived with {@code WebResourceRequest.isRedirect()} set. */
        BLOCKED_REDIRECTS,
        /** {@code window.open}/{@code target=_blank} attempts that were refused before any window existed. */
        BLOCKED_POPUPS,
        /** {@code window.open}/{@code target=_blank} targets that were verified and re-routed into the single WebView. */
        ROUTED_POPUPS,
        /** Sign-in windows opened inside the app for a verified sign-in page (never a system browser). */
        SIGN_IN_WINDOWS,
        /** Download attempts refused (the app never hands a URL to a download manager or browser). */
        BLOCKED_DOWNLOADS,
        /** Attempts to start another Android app, refused in {@code Activity.startActivity(...)}. */
        BLOCKED_EXTERNAL_INTENTS,
        /** File-picker launches refused (never opens another app, never reads a local file). */
        BLOCKED_FILE_CHOOSERS,
        /** Camera/microphone requests refused. */
        BLOCKED_MEDIA_PERMISSIONS,
        /** Geolocation requests refused. */
        BLOCKED_GEOLOCATION,
        /** TLS errors refused - {@code SslErrorHandler.proceed()} is never called. */
        BLOCKED_SSL_ERRORS,
        /** Safe Browsing hits sent back to safety. */
        BLOCKED_SAFE_BROWSING,
        /** HTTP basic/digest authentication prompts refused. */
        BLOCKED_HTTP_AUTH,
        /** Client-certificate requests refused. */
        BLOCKED_CLIENT_CERT,
        /** Times the app reset the WebView to the start page because it was not on an approved URL. */
        RECOVERY_RELOADS,
        /** Times the periodic watchdog verified the current URL. */
        WATCHDOG_CHECKS,
        /** Renderer process losses handled by tearing the WebView down. */
        RENDERER_GONE,
        /** Incoming intents from other apps that were ignored (the app has no deep-link handling). */
        IGNORED_INCOMING_INTENTS,
        /** TLS tunnels the in-app egress proxy opened to an allow-listed host on port 443. */
        TUNNELS_OPENED,
        /** {@code CONNECT} requests the egress proxy refused (host not listed, wrong port, malformed). */
        BLOCKED_TUNNELS,
        /** Reloads the user asked for (pull-to-refresh, F5 / Ctrl+R); every one re-verifies the URL first. */
        USER_RELOADS
    }

    /** One refused destination, kept for the in-app report. */
    public static final class BlockEvent {
        private final BlockReason reason;
        private final String host;
        private final String url;
        private final long timestampMs;

        BlockEvent(BlockReason reason, String host, String url, long timestampMs) {
            this.reason = reason;
            this.host = host == null ? "" : host;
            this.url = url == null ? "" : url;
            this.timestampMs = timestampMs;
        }

        public BlockReason reason() {
            return reason;
        }

        public String host() {
            return host;
        }

        /** Query string and fragment removed; length capped. */
        public String url() {
            return url;
        }

        public long timestampMs() {
            return timestampMs;
        }

        public String describe() {
            String who = host.isEmpty() ? "?" : host;
            return (reason == null ? "?" : reason.name()) + " host=" + who + " url=" + url;
        }
    }

    private static final int MAX_RECENT_BLOCKS = 50;
    private static final int MAX_URL_LENGTH = 200;
    private static final PolicyMetrics PROCESS_INSTANCE = new PolicyMetrics();

    private final Map<Counter, AtomicLong> counters = new EnumMap<Counter, AtomicLong>(Counter.class);
    private final List<BlockEvent> recentBlocks = new ArrayList<BlockEvent>();
    private long sequence;

    public PolicyMetrics() {
        for (Counter counter : Counter.values()) {
            counters.put(counter, new AtomicLong());
        }
    }

    /** The process-wide instance used by the app. */
    public static PolicyMetrics get() {
        return PROCESS_INSTANCE;
    }

    /** Increment a counter by one. */
    public void increment(Counter counter) {
        increment(counter, 1);
    }

    /** Increment a counter by {@code delta}. */
    public void increment(Counter counter, long delta) {
        AtomicLong value = counters.get(counter);
        if (value != null) {
            value.addAndGet(delta);
        }
    }

    /** Current value of a counter. */
    public long count(Counter counter) {
        AtomicLong value = counters.get(counter);
        return value == null ? 0 : value.get();
    }

    /** Record a refusal (bounded ring buffer, newest first). */
    public void recordBlock(BlockReason reason, String host, String rawUrl) {
        BlockEvent event = new BlockEvent(reason, host, sanitizeUrl(rawUrl), nowMs());
        synchronized (recentBlocks) {
            recentBlocks.add(0, event);
            if (recentBlocks.size() > MAX_RECENT_BLOCKS) {
                recentBlocks.remove(recentBlocks.size() - 1);
            }
            sequence++;
        }
    }

    /** Snapshot of the most recent refusals, newest first. */
    public List<BlockEvent> recentBlocks() {
        synchronized (recentBlocks) {
            return Collections.unmodifiableList(new ArrayList<BlockEvent>(recentBlocks));
        }
    }

    /** Number of refusals recorded so far (may exceed {@link #recentBlocks()}.size()). */
    public long recordedBlockCount() {
        synchronized (recentBlocks) {
            return sequence;
        }
    }

    /** Clear every counter and the ring buffer (used between tests and by "clear diagnostics"). */
    public void reset() {
        for (AtomicLong value : counters.values()) {
            value.set(0);
        }
        synchronized (recentBlocks) {
            recentBlocks.clear();
            sequence = 0;
        }
    }

    /** Human readable report; safe to copy to the clipboard or paste into a bug report. */
    public String snapshot() {
        StringBuilder sb = new StringBuilder();
        sb.append("GitHub WebView (locked) - policy diagnostics\n");
        for (Counter counter : Counter.values()) {
            sb.append(String.format(Locale.US, "%-28s %d%n", counter.name(), count(counter)));
        }
        sb.append("recent blocked destinations (newest first):\n");
        List<BlockEvent> events = recentBlocks();
        if (events.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (BlockEvent event : events) {
                sb.append("  ").append(event.describe()).append('\n');
            }
        }
        return sb.toString();
    }

    /** Removes query string and fragment, then caps the length. */
    static String sanitizeUrl(String rawUrl) {
        if (rawUrl == null) {
            return "";
        }
        int cut = rawUrl.length();
        int query = rawUrl.indexOf('?');
        int fragment = rawUrl.indexOf('#');
        if (query >= 0 && query < cut) {
            cut = query;
        }
        if (fragment >= 0 && fragment < cut) {
            cut = fragment;
        }
        String trimmed = rawUrl.substring(0, cut);
        if (trimmed.length() > MAX_URL_LENGTH) {
            trimmed = trimmed.substring(0, MAX_URL_LENGTH) + "...";
        }
        return trimmed;
    }

    private static long nowMs() {
        return System.currentTimeMillis();
    }
}
