package app.arenawebview.locked.web;

import android.net.Uri;
import android.webkit.WebResourceRequest;

import java.util.Collections;
import java.util.Map;

/**
 * A {@link WebResourceRequest} that the test can shape freely, so navigation and request decisions can be
 * exercised on a device without any network traffic at all.
 */
public final class FakeWebResourceRequest implements WebResourceRequest {

    private final Uri url;
    private final boolean forMainFrame;
    private final boolean redirect;
    private final boolean hasGesture;
    private final String method;

    public FakeWebResourceRequest(String url, boolean forMainFrame, String method) {
        this(url, forMainFrame, false, true, method);
    }

    public FakeWebResourceRequest(String url, boolean forMainFrame, boolean redirect, boolean hasGesture,
                                  String method) {
        this.url = url == null ? null : Uri.parse(url);
        this.forMainFrame = forMainFrame;
        this.redirect = redirect;
        this.hasGesture = hasGesture;
        this.method = method;
    }

    public static FakeWebResourceRequest navigation(String url) {
        return new FakeWebResourceRequest(url, true, false, true, "GET");
    }

    public static FakeWebResourceRequest subresource(String url) {
        return new FakeWebResourceRequest(url, false, false, false, "GET");
    }

    public static FakeWebResourceRequest redirect(String url) {
        return new FakeWebResourceRequest(url, true, true, false, "GET");
    }

    public static FakeWebResourceRequest subframe(String url) {
        return new FakeWebResourceRequest(url, false, false, false, "GET");
    }

    @Override
    public Uri getUrl() {
        return url;
    }

    @Override
    public boolean isForMainFrame() {
        return forMainFrame;
    }

    @Override
    public boolean isRedirect() {
        return redirect;
    }

    @Override
    public boolean hasGesture() {
        return hasGesture;
    }

    @Override
    public String getMethod() {
        return method;
    }

    @Override
    public Map<String, String> getRequestHeaders() {
        return Collections.emptyMap();
    }
}
