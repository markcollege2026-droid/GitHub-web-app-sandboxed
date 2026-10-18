package app.arenawebview.locked.web;

import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

import app.arenawebview.locked.policy.PolicyResult;

/**
 * The synthetic response returned for a refused sub-resource.
 *
 * <p>Returning an empty 4xx response (instead of {@code null}) means the WebView does not fall back to
 * its own network stack, so the refused request is never sent anywhere: no DNS lookup, no TCP
 * connection, no TLS handshake to a third party. The response is generated entirely in-process.</p>
 */
public final class BlockedResponses {

    private BlockedResponses() {
        // no instances
    }

    /** Empty {@code 403} body flagged with the reason that caused the refusal. */
    public static WebResourceResponse create(PolicyResult result) {
        Map<String, String> headers = new HashMap<String, String>(2);
        headers.put("X-Blocked-By", "github-webview-locked");
        headers.put("X-Blocked-Reason", result == null || result.reason() == null
                ? "UNKNOWN"
                : result.reason().name());
        return new WebResourceResponse(
                "text/plain",
                "utf-8",
                403,
                "Blocked by app policy",
                headers,
                new ByteArrayInputStream(new byte[0]));
    }
}
