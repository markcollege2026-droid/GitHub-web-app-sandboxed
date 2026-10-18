package app.arenawebview.locked.web;

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

import app.arenawebview.locked.policy.GitHubAllowlist;
import app.arenawebview.locked.policy.PolicyConfig;
import app.arenawebview.locked.policy.PolicyMetrics;

/** Exercises the real WebView client with synthesized requests; no network is used. */
@RunWith(AndroidJUnit4.class)
public class NavigationGuardTest {

    private WebView webView;
    private RecordingGuardHost host;
    private ArenaWebViewClient client;
    private PolicyConfig policy;

    @Before
    public void setUp() {
        policy = GitHubAllowlist.productionConfig();
        host = new RecordingGuardHost(policy);
        client = new ArenaWebViewClient(host);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                webView = new WebView(InstrumentationRegistry.getInstrumentation().getTargetContext());
                WebViewHardening.applyHardenedSettings(webView.getSettings());
            }
        });
    }

    @Test
    public void allowsGithubPagesAndReviewedStaticResources() {
        assertFalse(client.shouldOverrideUrlLoading(webView,
                FakeWebResourceRequest.navigation("https://github.com/owner/repository/issues/12")));
        assertEquals(1, host.metrics().count(PolicyMetrics.Counter.ALLOWED_NAVIGATIONS));
        assertNull(client.shouldInterceptRequest(webView,
                FakeWebResourceRequest.navigation("https://github.com/owner/repository/issues/12")));
        assertNull(client.shouldInterceptRequest(webView,
                FakeWebResourceRequest.subresource("https://github.githubassets.com/assets/app.js")));
        assertNull(client.shouldInterceptRequest(webView,
                FakeWebResourceRequest.subresource("https://github.githubassets.com/favicons/favicon-dark.svg")));
        assertNull(client.shouldInterceptRequest(webView,
                FakeWebResourceRequest.subresource("https://avatars.githubusercontent.com/u/583231?v=4")));
    }

    @Test
    public void allowsPostNavigationsWithoutTouchingThem() {
        // A "return true" here would silently turn a form POST into a GET; the guard must not do that.
        assertFalse(client.shouldOverrideUrlLoading(webView,
                new FakeWebResourceRequest("https://github.com/session", true, false, true, "POST")));
    }

    @Test
    public void refusesThirdPartyNavigation() {
        assertTrue(client.shouldOverrideUrlLoading(webView,
                FakeWebResourceRequest.navigation("https://evil.example/")));
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
        assertTrue(client.shouldOverrideUrlLoading(webView,
                FakeWebResourceRequest.subframe("https://evil.example/frame")));
        assertEquals(1, host.metrics().count(PolicyMetrics.Counter.BLOCKED_REQUESTS));
    }

    /** A permitted resource-role host can render as a frame without becoming a navigation target. */
    @Test
    public void githubResourceFramesAreAllowedButResourceHostsCannotReplaceThePage() {
        assertFalse("a GitHub-hosted embedded image/document may render",
                client.shouldOverrideUrlLoading(webView, FakeWebResourceRequest.subframe(
                        "https://avatars.githubusercontent.com/u/583231")));
        assertFalse("GitHub repository API data may be embedded",
                client.shouldOverrideUrlLoading(webView, FakeWebResourceRequest.subframe(
                        "https://api.github.com/repos/owner/repository")));
        assertFalse("GitHub static resources load in-page",
                client.shouldOverrideUrlLoading(webView, FakeWebResourceRequest.subframe(
                        "https://github.githubassets.com/assets/embed.js")));

        assertTrue("the static asset CDN must not replace the app page",
                client.shouldOverrideUrlLoading(webView, FakeWebResourceRequest.navigation(
                        "https://github.githubassets.com/assets/app.js")));
        assertTrue("the raw content host must not replace the app page",
                client.shouldOverrideUrlLoading(webView, FakeWebResourceRequest.navigation(
                        "https://raw.githubusercontent.com/owner/repo/main/README.md")));
        assertTrue("the API host must not replace the app page",
                client.shouldOverrideUrlLoading(webView, FakeWebResourceRequest.navigation(
                        "https://api.github.com/repos/owner/repository")));
        assertEquals(3, host.metrics().count(PolicyMetrics.Counter.BLOCKED_NAVIGATIONS));
    }

    @Test
    public void pathScopedHostsRejectUnreviewedResourcePaths() {
        WebResourceResponse response = client.shouldInterceptRequest(webView,
                FakeWebResourceRequest.subresource("https://github.githubassets.com/not-assets/app.js"));
        assertNotNull("an out-of-scope CDN path must be refused locally", response);
        assertEquals(403, response.getStatusCode());

        response = client.shouldInterceptRequest(webView,
                FakeWebResourceRequest.subresource("https://api.github.com/gists"));
        assertNotNull("an unapproved API path must be refused locally", response);
        assertEquals(403, response.getStatusCode());
    }

    /** A frame still cannot reach a host that is not on the allow-list at all. */
    @Test
    public void refusesIframesToUnlistedHostsIncludingExternalIdentityProviders() {
        assertTrue(client.shouldOverrideUrlLoading(webView,
                FakeWebResourceRequest.subframe("https://evil.example/frame")));
        assertTrue(client.shouldOverrideUrlLoading(webView,
                FakeWebResourceRequest.subframe("https://accounts.google.com/signin")));
        assertTrue(client.shouldOverrideUrlLoading(webView,
                FakeWebResourceRequest.subframe("https://sso.example.org/login")));
        assertEquals(3, host.metrics().count(PolicyMetrics.Counter.BLOCKED_REQUESTS));
    }

    /** Refusal diagnostics keep the host and path but omit query strings that may contain tokens. */
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
        assertTrue(client.shouldOverrideUrlLoading(webView,
                FakeWebResourceRequest.redirect("https://evil.example/")));
        assertEquals(1, host.metrics().count(PolicyMetrics.Counter.BLOCKED_REDIRECTS));
        WebResourceResponse response = client.shouldInterceptRequest(webView,
                new FakeWebResourceRequest("https://evil.example/step2", true, true, false, "GET"));
        assertNotNull(response);
        assertEquals(403, response.getStatusCode());
        assertEquals(2, host.metrics().count(PolicyMetrics.Counter.BLOCKED_REDIRECTS));
    }

    @Test
    public void refusesCustomSchemesAndAppLaunchIntents() {
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
        assertTrue(client.shouldOverrideUrlLoading(webView,
                FakeWebResourceRequest.navigation("http://github.com/")));
        assertNotNull(client.shouldInterceptRequest(webView,
                FakeWebResourceRequest.subresource("http://github.com/assets/x.js")));
    }

    @Test
    public void refusesLookAlikeHostsAndUnreviewedSubdomains() {
        String[] hosts = {
                "https://github-com.example/",
                "https://github.com.evil.example/",
                "https://\u0430github.com/",
                "https://xn--gthub-5va.example/",
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
        WebResourceResponse response = client.shouldInterceptRequest(webView,
                FakeWebResourceRequest.subresource(null));
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
