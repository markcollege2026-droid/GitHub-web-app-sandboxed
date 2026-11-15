package app.githubwebview.locked.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import app.githubwebview.locked.policy.GitHubAllowlist;
import app.githubwebview.locked.policy.PolicyConfig;
import app.githubwebview.locked.policy.PolicyMetrics;

/**
 * Drives the real {@link GitHubWebViewClient} on a device with synthesized requests.
 *
 * <p>No network is used: these assertions are about the guard's decisions, which is what decides whether
 * a request ever leaves the device.</p>
 */
@RunWith(AndroidJUnit4.class)
public class NavigationGuardTest {

    private WebView webView;
    private RecordingGuardHost host;
    private GitHubWebViewClient client;
    private PolicyConfig policy;

    @Before
    public void setUp() {
        policy = GitHubAllowlist.productionConfig();
        host = new RecordingGuardHost(policy);
        client = new GitHubWebViewClient(host);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                webView = new WebView(InstrumentationRegistry.getInstrumentation().getTargetContext());
                WebViewHardening.applyHardenedSettings(webView.getSettings());
            }
        });
    }

    @Test
    public void allowsTheSiteItself() {
        assertFalse(client.shouldOverrideUrlLoading(webView, FakeWebResourceRequest.navigation("https://github.com/chat")));
        assertEquals(1, host.metrics().count(PolicyMetrics.Counter.ALLOWED_NAVIGATIONS));
        assertNull(client.shouldInterceptRequest(webView, FakeWebResourceRequest.navigation("https://github.com/chat")));
        assertNull(client.shouldInterceptRequest(webView, FakeWebResourceRequest.subresource("https://github.githubassets.com/x.js")));
    }

    @Test
    public void allowsPostNavigationsWithoutTouchingThem() {
        // A "return true" here would silently turn a form POST into a GET; the guard must not do that.
        assertFalse(client.shouldOverrideUrlLoading(webView,
                new FakeWebResourceRequest("https://github.com/session", true, false, true, "POST")));
    }

    @Test
    public void refusesThirdPartyNavigation() {
        assertTrue(client.shouldOverrideUrlLoading(webView, FakeWebResourceRequest.navigation("https://evil.example/")));
        assertEquals(1, host.metrics().count(PolicyMetrics.Counter.BLOCKED_NAVIGATIONS));
        assertEquals("https://evil.example/", host.lastBlockedNavigation);
        assertEquals("evil.example", host.metrics().recentBlocks().get(0).host());
    }

    @Test
    public void refusesThirdPartySubresourcesWithoutSendingThem() {
        WebResourceResponse response = client.shouldInterceptRequest(webView,
                FakeWebResourceRequest.subresource("https://evil.example/tracker.js"));
        assertNotNull("a refused request must be answered in-process, not with null", response);
        assertEquals(403, response.getStatusCode());
        assertEquals(1, host.metrics().count(PolicyMetrics.Counter.BLOCKED_REQUESTS));
    }

    @Test
    public void refusesThirdPartyIframes() {
        assertTrue(client.shouldOverrideUrlLoading(webView, FakeWebResourceRequest.subframe("https://evil.example/frame")));
        assertEquals(1, host.metrics().count(PolicyMetrics.Counter.BLOCKED_REQUESTS));
    }


    /**
     * A CAPTCHA widget is a third-party <em>document in an iframe</em>. Refusing those means the challenge
     * can never render and any form that needs its token can never be submitted - which is exactly what
     * happened to the sign-in forms of this app in the field. An embedded document is judged as an
     * embedded document, not as the app's page.
     */
    @Test
    public void allowsWidgetIframesThatAPageNeedsToRender() {
        assertFalse("the reCAPTCHA anchor frame must be allowed to render",
                client.shouldOverrideUrlLoading(webView, FakeWebResourceRequest.subframe(
                        "https://www.google.com/recaptcha/enterprise/anchor?ar=1&k=6LeTGMcsAAAAA")));
        assertFalse("a Google identity frame must be allowed to render",
                client.shouldOverrideUrlLoading(webView, FakeWebResourceRequest.subframe(
                        "https://www.gstatic.com/accounts/gsi/iframe.html")));
        assertFalse("the Arkose challenge frame must be allowed to render",
                client.shouldOverrideUrlLoading(webView, FakeWebResourceRequest.subframe(
                        "https://github-api.arkoselabs.com/fc/gc/game/index.html")));
    }

    /**
     * ...but the very same URLs must never replace the app's own page: an iframe is not a navigation.
     * This is the invariant that keeps "the widget can render" from turning into "the app can browse a
     * third party".
     */
    @Test
    public void refusesToNavigateTheAppItselfToAWidgetHost() {
        assertTrue(client.shouldOverrideUrlLoading(webView, FakeWebResourceRequest.navigation(
                "https://www.google.com/recaptcha/enterprise/anchor?ar=1&k=6LeTGMcsAAAAA")));
        assertTrue(client.shouldOverrideUrlLoading(webView, FakeWebResourceRequest.navigation(
                "https://github-api.arkoselabs.com/fc/gc/game/index.html")));
        assertTrue(client.shouldOverrideUrlLoading(webView, FakeWebResourceRequest.navigation(
                "https://www.gstatic.com/_/mss/boq-identity/_/js/k=boq-identity.x/y")));
        assertEquals(3, host.metrics().count(PolicyMetrics.Counter.BLOCKED_NAVIGATIONS));
    }

    /** An iframe still cannot reach a host that is not on the allow-list at all. */
    @Test
    public void refusesIframesToUnlistedHosts() {
        assertTrue(client.shouldOverrideUrlLoading(webView,
                FakeWebResourceRequest.subframe("https://evil.example/frame")));
        assertTrue(client.shouldOverrideUrlLoading(webView,
                FakeWebResourceRequest.subframe("https://accounts.google.com.evil.example/frame")));
        assertEquals(2, host.metrics().count(PolicyMetrics.Counter.BLOCKED_REQUESTS));
    }

    /**
     * Every refusal is now named in logcat, once per distinct destination, so a page that only half works
     * can be diagnosed from a log instead of guessed at. The line must carry the reason, the host and the
     * path - and never the query string, which is where tokens live.
     */
    @Test
    public void everyRefusalIsNamedInTheLogWithHostAndPathOnly() {
        client.shouldInterceptRequest(webView, FakeWebResourceRequest.subresource(
                "https://evil.example/steal?token=supersecret"));
        assertEquals(1, host.metrics().count(PolicyMetrics.Counter.BLOCKED_REQUESTS));
        String report = host.metrics().snapshot();
        assertTrue("the diagnostics must name the refused host: " + report, report.contains("evil.example"));
        assertFalse("no query string may be recorded: " + report, report.contains("supersecret"));
    }

    @Test
    public void refusesRedirectsToThirdParties() {
        assertTrue(client.shouldOverrideUrlLoading(webView, FakeWebResourceRequest.redirect("https://evil.example/")));
        assertEquals(1, host.metrics().count(PolicyMetrics.Counter.BLOCKED_REDIRECTS));
        // ... and the redirect target is refused at the network layer as well.
        WebResourceResponse response = client.shouldInterceptRequest(webView,
                new FakeWebResourceRequest("https://evil.example/step2", true, true, false, "GET"));
        assertNotNull(response);
        assertEquals(403, response.getStatusCode());
        assertEquals(2, host.metrics().count(PolicyMetrics.Counter.BLOCKED_REDIRECTS));
    }

    private static final String FILE_LINK =
            "https://codeload.github.com/markcollege2026-droid/GitHub-web-app-sandboxed/zip/refs/heads/main";

    /** Puts a verified github.com page on screen without any network: the base URL is what getUrl() reports. */
    private void showAVerifiedPage() throws Exception {
        final String page = "https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed";
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                webView.loadDataWithBaseURL(page, "<!doctype html><html><body>page</body></html>", "text/html", "utf-8", page));
        final String[] current = new String[1];
        for (int i = 0; i < 100 && !page.equals(current[0]); i++) {
            Thread.sleep(100);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> current[0] = webView.getUrl());
        }
        assertEquals("the test page must be what the WebView reports", page, current[0]);
    }

    /** Refuses {@code refused} as a main-frame redirect, then delivers WebView's finished report for {@code reported}. */
    private void refuseThenReport(final String refused, final String reported) {
        // Nothing is asserted on the main thread: a failure there would take the whole run down.
        final boolean[] refusedByClient = {true};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            if (refused != null) {
                refusedByClient[0] = client.shouldOverrideUrlLoading(webView, FakeWebResourceRequest.redirect(refused));
            }
            client.onPageFinished(webView, reported);
        });
        assertTrue("the navigation must have been refused", refusedByClient[0]);
    }

    /**
     * The blink behind "Save this file?": WebView reports a finished page for the file link this client
     * has just refused. The verified page is still on screen, so it must not be reloaded.
     */
    @Test
    public void aRefusedFileLinkDoesNotReloadThePageBehindTheSaveOffer() throws Exception {
        showAVerifiedPage();
        refuseThenReport(FILE_LINK, FILE_LINK);
        assertEquals(FILE_LINK, host.lastBlockedNavigation);
        assertNull("the page behind the offer must stay", host.lastRecoveryReason);
        assertNull(host.lastVerifiedNavigation);
    }

    @Test
    public void theReportIsCoveredOnce() throws Exception {
        showAVerifiedPage();
        refuseThenReport(FILE_LINK, FILE_LINK);
        assertNull(host.lastRecoveryReason);
        refuseThenReport(null, FILE_LINK);
        assertEquals("onPageFinished:DOCUMENT_NOT_ALLOWED", host.lastRecoveryReason);
    }

    @Test
    public void aResourceHostThatWasNeverRefusedStillRecovers() throws Exception {
        showAVerifiedPage();
        refuseThenReport(null, FILE_LINK);
        assertEquals("onPageFinished:DOCUMENT_NOT_ALLOWED", host.lastRecoveryReason);
    }

    @Test
    public void aRefusalCoversOnlyThatExactUrl() throws Exception {
        showAVerifiedPage();
        refuseThenReport(FILE_LINK, FILE_LINK + "-other");
        assertEquals("onPageFinished:DOCUMENT_NOT_ALLOWED", host.lastRecoveryReason);
    }

    @Test
    public void aRefusedThirdPartyStillRecovers() throws Exception {
        showAVerifiedPage();
        refuseThenReport("https://evil.example/file.zip", "https://evil.example/file.zip");
        assertNotNull("an unlisted host is never excused", host.lastRecoveryReason);
    }

    @Test
    public void aRefusedFileLinkStillRecoversWhenNoVerifiedPageIsOnScreen() {
        // A WebView that has loaded nothing reports no page at all.
        refuseThenReport(FILE_LINK, FILE_LINK);
        assertEquals("onPageFinished:DOCUMENT_NOT_ALLOWED", host.lastRecoveryReason);
    }

    @Test
    public void refusesCustomSchemes() {
        String[] schemes = {
                "intent://scan/#Intent;scheme=zxing;end",
                "market://details?id=com.example",
                "mailto:someone@github.com",
                "tel:+15551234567",
                "whatsapp://send?text=hi",
                "javascript:alert(1)",
                "file:///etc/hosts",
                "content://com.android.providers.media.documents/document/1",
        };
        for (String url : schemes) {
            assertTrue("must be refused: " + url,
                    client.shouldOverrideUrlLoading(webView, FakeWebResourceRequest.navigation(url)));
        }
        assertEquals(schemes.length, host.metrics().count(PolicyMetrics.Counter.BLOCKED_NAVIGATIONS));
    }

    @Test
    public void refusesCleartextHttp() {
        assertTrue(client.shouldOverrideUrlLoading(webView, FakeWebResourceRequest.navigation("http://github.com/")));
        assertNotNull(client.shouldInterceptRequest(webView, FakeWebResourceRequest.subresource("http://github.com/x.js")));
    }

    @Test
    public void refusesLookAlikeHosts() {
        String[] hosts = {
                "https://git-hub.com/",
                "https://github.com.evil.example/",
                "https://g\u0456thub.com/",
                "https://xn--rena-4qa.ai/",
                "https://mail.github.com/",
                "https://sub.github.com/",
                "https://github.com@evil.example/",
                "https://github.com:8443/",
        };
        for (String url : hosts) {
            assertTrue("must be refused: " + url,
                    client.shouldOverrideUrlLoading(webView, FakeWebResourceRequest.navigation(url)));
        }
    }

    @Test
    public void failsClosedOnUnusableRequests() {
        assertTrue(client.shouldOverrideUrlLoading(webView, FakeWebResourceRequest.navigation(null)));
        WebResourceResponse response = client.shouldInterceptRequest(webView, FakeWebResourceRequest.subresource(null));
        assertNotNull(response);
        assertEquals(403, response.getStatusCode());
        assertTrue(host.metrics().count(PolicyMetrics.Counter.BLOCKED_NAVIGATIONS) >= 1);
        assertTrue(host.metrics().count(PolicyMetrics.Counter.BLOCKED_REQUESTS) >= 1);
    }

    @Test
    public void dataUrlsAreNeverDocuments() {
        assertTrue(client.shouldOverrideUrlLoading(webView,
                FakeWebResourceRequest.navigation("data:text/html,<script>alert(1)</script>")));
        // ... but an inline resource is fine.
        assertNull(client.shouldInterceptRequest(webView,
                FakeWebResourceRequest.subresource("data:image/png;base64,iVBORw0KGgo=")));
    }
}
