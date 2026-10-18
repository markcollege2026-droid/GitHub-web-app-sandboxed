package app.arenawebview.locked;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;

/**
 * Source-level invariants.
 *
 * <p>Some of the wrapper's guarantees are "the app must never contain this call". Those cannot be
 * expressed as a unit test of a method, so they are asserted against the source: if somebody adds a
 * JavaScript bridge, a {@code proceed()} on a TLS error, a second place that loads a URL, or a path that
 * launches another activity, this test fails.</p>
 */
public class SourceInvariantsTest {

    private static String allMainSources() throws Exception {
        StringBuilder sb = new StringBuilder();
        collect(TestPaths.mainSourcesDir(), sb);
        return sb.toString();
    }

    private static void collect(File dir, StringBuilder sb) throws Exception {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                collect(file, sb);
            } else if (file.getName().endsWith(".java")) {
                sb.append("// FILE: ").append(file.getName()).append('\n');
                sb.append(stripComments(TestPaths.read(file))).append('\n');
            }
        }
    }

    /** Reads one main-source file with comments removed - the invariants are about code, not prose. */
    private static String code(String relativePath) throws Exception {
        return stripComments(TestPaths.readSource(relativePath));
    }

    /**
     * Removes {@code //} and {@code /* *}{@code /} comments while leaving string and character
     * literals untouched, so that a URL such as {@code "https://github.com/"} survives intact.
     */
    private static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                out.append(c);
                i++;
                while (i < n) {
                    char inLiteral = source.charAt(i);
                    out.append(inLiteral);
                    i++;
                    if (inLiteral == '\\' && i < n) {
                        out.append(source.charAt(i));
                        i++;
                    } else if (inLiteral == c) {
                        break;
                    }
                }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int count(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }

    @Test
    public void noJavascriptBridgeExists() throws Exception {
        String sources = allMainSources();
        assertFalse("a JavaScript bridge is the classic WebView RCE path",
                sources.contains("addJavascriptInterface"));
        assertFalse(sources.contains("@JavascriptInterface"));
        assertFalse(sources.contains("removeJavascriptInterface"));
    }

    @Test
    public void tlsErrorsAreNeverBypassed() throws Exception {
        String client = code("app/arenawebview/locked/web/ArenaWebViewClient.java");
        assertTrue("the client must handle TLS errors", client.contains("onReceivedSslError"));
        assertFalse("proceed() must never be called", client.contains("proceed()"));
        assertTrue("the handler must be cancelled", client.contains("handler.cancel()"));
    }

    @Test
    public void onlyOnePlaceLoadsUrls() throws Exception {
        String sources = allMainSources();
        assertEquals("every load must go through loadVerifiedDestination",
                1, count(sources, ".loadUrl("));
        String main = code("app/arenawebview/locked/MainActivity.java");
        assertTrue(main.contains("public void loadVerifiedDestination(String url, String source)"));

        // A reload is a navigation too. Exactly one call site, and it re-verifies the current URL first.
        assertEquals("every reload must go through reloadCurrentPage", 1, count(sources, ".reload("));
        int at = main.indexOf("private void reloadCurrentPage(String source)");
        assertTrue(at >= 0);
        String body = main.substring(at, main.indexOf(".reload(", at));
        assertTrue("the current URL must be verified before it is reloaded",
                body.contains("policy.evaluateNavigation(current)"));
        assertTrue("an unverified page is replaced by the start page, not reloaded",
                body.contains("loadVerifiedDestination(policy.primaryUrl()"));
    }

    /**
     * The egress guard is the one layer that sees WebSockets. It must be installed before the first load,
     * it must never offer Chromium a direct fallback, and it must listen on loopback only.
     */
    @Test
    public void nothingLoadsBeforeTheEgressGuardIsReadyAndThereIsNoDirectFallback() throws Exception {
        String main = code("app/arenawebview/locked/MainActivity.java");
        String guard = code("app/arenawebview/locked/web/EgressGuard.java");
        String proxy = code("app/arenawebview/locked/net/EgressProxy.java");

        int at = main.indexOf("public void loadVerifiedDestination(String url, String source)");
        String body = main.substring(at, main.indexOf(".loadUrl(", at));
        assertTrue("the single loadUrl must be gated on the egress guard", body.contains("EgressGuard.isReady()"));
        String onCreate = main.substring(main.indexOf("protected void onCreate("),
                main.indexOf("private void startSession("));
        assertTrue("onCreate hands over to startSession", onCreate.contains("startSession(savedInstanceState)"));
        assertFalse("the start page is not loaded from onCreate", onCreate.contains("\"startup\""));
        String begin = main.substring(main.indexOf("private void beginSession("),
                main.indexOf("private void attachPrimaryWebView("));
        assertTrue("the start page is loaded once the guard reported ready",
                begin.contains("loadVerifiedDestination(policy.primaryUrl(), \"startup\")"));
        assertTrue(main.contains("EgressGuard.install(policy, metrics"));

        assertTrue(guard.contains("WebViewFeature.PROXY_OVERRIDE"));
        assertTrue("one rule for every scheme, to the address the proxy really listens on",
                guard.contains("addProxyRule(EgressProxy.LOOPBACK_HOST + \":\" + running.port())"));
        assertFalse("a direct fallback would let a failed proxy become no proxy", guard.contains("addDirect("));
        assertFalse(guard.contains("addBypassRule("));
        assertTrue("loopback and link-local destinations go through the guard too", guard.contains(".removeImplicitRules()"));
        assertFalse("the override is for the process lifetime", guard.contains("clearProxyOverride("));

        assertTrue("IPv4 loopback, spelled out (Android's getLoopbackAddress() is ::1)",
                proxy.contains("new byte[] {127, 0, 0, 1}") && proxy.contains("LOOPBACK_HOST = \"127.0.0.1\""));
        assertFalse("never the platform's idea of loopback", proxy.contains("getLoopbackAddress()"));
        assertFalse(proxy.contains("0.0.0.0"));
        assertTrue("only CONNECT is served", proxy.contains("\"CONNECT\".equals(parts[0])"));
        assertTrue("the policy decides every tunnel", proxy.contains("policy.evaluateTunnel(request.host, request.port)"));
        assertFalse("the proxy never reads TLS", proxy.contains("SSLSocket") || proxy.contains("javax.net.ssl"));
    }

    @Test
    public void theAppNeverStartsAnotherActivity() throws Exception {
        String sources = allMainSources();
        assertFalse("startActivity must never be delegated to the platform",
                sources.contains("super.startActivity"));
        assertFalse("no call sites may start an activity",
                sources.contains(".startActivity(") || sources.contains(".startActivityForResult("));

        String main = code("app/arenawebview/locked/MainActivity.java");
        assertTrue(main.contains("public void startActivity(Intent intent)"));
        assertTrue(main.contains("public void startActivityForResult(Intent intent, int requestCode)"));
        assertTrue(main.contains("blockExternalIntent"));
    }

    @Test
    public void webViewIsConfiguredStrictly() throws Exception {
        String hardening = code("app/arenawebview/locked/web/WebViewHardening.java");
        assertTrue(hardening.contains("setAllowFileAccess(false)"));
        assertTrue(hardening.contains("setAllowContentAccess(false)"));
        assertTrue(hardening.contains("setAllowFileAccessFromFileURLs(false)"));
        assertTrue(hardening.contains("setAllowUniversalAccessFromFileURLs(false)"));
        assertTrue(hardening.contains("MIXED_CONTENT_NEVER_ALLOW"));
        assertTrue(hardening.contains("setSafeBrowsingEnabled(true)"));
        assertTrue(hardening.contains("setGeolocationEnabled(false)"));
        assertTrue("the service-worker transport must be policed too",
                hardening.contains("installServiceWorkerGuard"));
        assertTrue("the guard clients must be attached to the WebView",
                hardening.contains("webView.setWebViewClient(client)")
                        && hardening.contains("webView.setWebChromeClient(chromeClient)"));

        assertFalse("file access must never be enabled", hardening.contains("setAllowFileAccess(true)"));
        assertFalse("content access must never be enabled", hardening.contains("setAllowContentAccess(true)"));
        assertFalse(hardening.contains("setAllowUniversalAccessFromFileURLs(true)"));
        assertFalse("JavaScript is required by the real site", hardening.contains("setJavaScriptEnabled(false)"));
        assertFalse("mixed content must never be allowed",
                hardening.contains("MIXED_CONTENT_ALWAYS_ALLOW")
                        || hardening.contains("MIXED_CONTENT_COMPATIBILITY_MODE"));
    }

    @Test
    public void inputHandlingDoesNotInjectPageCodeOrReplaceGitHubControls() throws Exception {
        String sources = allMainSources();
        assertFalse("the app must not inject JavaScript into GitHub pages", sources.contains("evaluateJavascript("));
        assertFalse("the app must not replace GitHub pages with local HTML", sources.contains("loadDataWithBaseURL("));
        assertFalse("the app must not inject custom HTML", sources.contains("loadData("));
    }

    @Test
    public void authenticationAndBotProtectionPoliciesAreNotBypassedByUserAgentSpoofing() throws Exception {
        assertFalse("the app must preserve Android WebView's default user-agent marker",
                allMainSources().contains("setUserAgentString"));
    }

    @Test
    public void webDebuggingOnlyExistsInDebugBuilds() throws Exception {
        String main = code("app/arenawebview/locked/MainActivity.java");
        assertTrue(main.contains("WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)"));
        assertFalse(main.contains("setWebContentsDebuggingEnabled(true)"));
    }

    @Test
    public void chromeClientRefusesEscapes() throws Exception {
        String chrome = code("app/arenawebview/locked/web/ArenaWebChromeClient.java");
        assertTrue("the file picker must be intercepted", chrome.contains("onShowFileChooser"));
        assertTrue("the file picker callback must be completed without a file",
                chrome.contains("filePathCallback.onReceiveValue(null)"));
        assertTrue("media capture must be denied", chrome.contains("request.deny()"));
        assertTrue("geolocation must be denied", chrome.contains("callback.invoke(origin, false, false)"));
        assertTrue("popups must be intercepted", chrome.contains("onCreateWindow"));
    }

    @Test
    public void downloadsAreRefused() throws Exception {
        String hardening = code("app/arenawebview/locked/web/WebViewHardening.java");
        assertTrue(hardening.contains("setDownloadListener"));
        assertFalse("the app must not use the platform download manager",
                allMainSources().contains("DownloadManager"));
        assertFalse("nothing may be shared with another app", allMainSources().contains("ACTION_SEND"));
    }

    /**
     * Exactly two WebViews can exist, and both are accounted for:
     *
     * <ol>
     *   <li>the primary one, created in {@code WebViewHardening} and owned by the activity; and</li>
     *   <li>an ordinary pop-up window, which is destroyed by {@code PopupRouterClient} on its first
     *       navigation - the one exception being a sign-in hand-off, which is shown in the in-app
     *       window instead (never handed to a system browser).</li>
     * </ol>
     *
     * <p>The production GitHub policy has no external sign-in host, but the architecture retains the
     * narrow in-app sign-in window for a provider that might be approved after a separate review. Only a
     * pop-up explicitly promoted by {@code PopupRouterClient} may be attached, and the activity destroys
     * it when the window closes.</p>
     */
    @Test
    public void theOnlyWebViewsAreThePrimaryOneAndTheSignInWindow() throws Exception {
        String main = code("app/arenawebview/locked/MainActivity.java");
        String hardening = code("app/arenawebview/locked/web/WebViewHardening.java");
        String chrome = code("app/arenawebview/locked/web/ArenaWebChromeClient.java");
        String router = code("app/arenawebview/locked/web/PopupRouterClient.java");
        String authWindow = code("app/arenawebview/locked/ui/AuthWindow.java");

        assertFalse("the activity must not create WebViews itself", main.contains("new WebView("));
        assertTrue(main.contains("createPrimaryWebView"));
        // Exactly one WebView is created for the visible UI, in one place.
        assertEquals(1, count(hardening, "new WebView("));
        assertEquals("pop-up windows are created in exactly one place", 1, count(chrome, "new WebView("));
        assertTrue(chrome.contains("transport.setWebView(popup)"));

        // A pop-up is only attached by the sign-in window, and only after the router promoted it.
        assertFalse("a pop-up must never be added to a view hierarchy by the chrome client",
                chrome.contains("addView(popup"));
        assertFalse("a pop-up must never be added to a view hierarchy by the router",
                router.contains("addView(popup"));
        assertTrue("only the in-app sign-in window attaches a WebView",
                authWindow.contains("content.addView(webView"));

        // Promotion happens for sign-in URLs only, and the router asks the host to show it.
        assertTrue(router.contains("isSignInNavigation(url)"));
        assertTrue(router.contains("onSignInWindowRequested(popup, url)"));
        assertTrue("the sign-in window must be torn down again", main.contains("closeSignInWindow"));
        assertTrue("a closed sign-in window must destroy its WebView", main.contains("popup.destroy()"));
        assertTrue("the watchdog must check the sign-in window too", main.contains("checkSignInWindowUrl"));
    }

    /**
     * An approved sign-in hand-off must never open a way out of the app: no system browser, no Custom
     * Tab, no ACTION_VIEW, and the in-app window is a plain child view.
     */
    @Test
    public void signInNeverLeavesTheApp() throws Exception {
        String sources = allMainSources();
        assertFalse("no Custom Tabs", sources.contains("androidx.browser"));
        assertFalse("no ACTION_VIEW", sources.contains("Intent.ACTION_VIEW"));
        assertFalse("no web-browser intent", sources.contains("setPackage(\"com.android.chrome\")"));
        assertFalse("no chooser", sources.contains("createChooser"));

        String authWindow = code("app/arenawebview/locked/ui/AuthWindow.java");
        assertFalse("the sign-in window must not be its own activity", authWindow.contains("extends Activity"));
        assertTrue("the sign-in window is a view inside the activity", authWindow.contains("extends FrameLayout"));
        assertTrue("the user can always close it", authWindow.contains("auth_window_close"));
    }

    @Test
    public void incomingIntentsAreIgnored() throws Exception {
        String main = code("app/arenawebview/locked/MainActivity.java");
        assertTrue(main.contains("recordIncomingIntent"));
        assertFalse("the activity must not read URL data out of an intent",
                main.contains("getIntent().getData()"));
        assertFalse(main.contains("getIntent().getStringExtra"));
    }

    /**
     * Every client that can decide the fate of a frame must ask the <em>frame</em> question.
     *
     * <p>This is the source-level guard that keeps embedded GitHub documents and resource-role hosts
     * evaluated as subdocuments instead of top-level pages. Both {@code ArenaWebViewClient} and
     * {@code PopupRouterClient} must therefore consult
     * {@code evaluateSubdocument}; a new client that does not will fail here.</p>
     */
    @Test
    public void everyClientThatSeesFramesAsksTheSubdocumentQuestion() throws Exception {
        for (String file : new String[]{
                "app/arenawebview/locked/web/ArenaWebViewClient.java",
                "app/arenawebview/locked/web/PopupRouterClient.java"}) {
            String source = code(file);
            assertTrue(file + " must ask the embedded-document question as well as the navigation question",
                    source.contains("evaluateSubdocument("));
            assertTrue(file + " must only ask it for frames (isForMainFrame), never for the whole view",
                    source.contains("isForMainFrame()"));
        }
    }

    /**
     * The three questions must stay three questions.
     *
     * <p>If {@code evaluateSubdocument} ever collapses back into {@code evaluateNavigation}, resource-role
     * frames are refused again - silently, because the page simply stops working. The traffic classes are
     * distinct values, and the config must keep asking each of them.</p>
     */
    @Test
    public void theThreeTrafficQuestionsStayDistinct() throws Exception {
        String config = code("app/arenawebview/locked/policy/PolicyConfig.java");
        for (String expected : new String[]{
                "UrlPolicy.evaluate(rawUrl, this, UrlPolicy.TrafficClass.DOCUMENT)",
                "UrlPolicy.evaluate(rawUrl, this, UrlPolicy.TrafficClass.SUBDOCUMENT)",
                "UrlPolicy.evaluate(rawUrl, this, UrlPolicy.TrafficClass.SUBRESOURCE)"}) {
            assertTrue("PolicyConfig must still ask this question: " + expected, config.contains(expected));
        }
        assertTrue("an embedded document needs its own traffic class",
                code("app/arenawebview/locked/policy/UrlPolicy.java").contains("SUBDOCUMENT"));
    }

    /**
     * A WebView whose renderer died is dead: the platform contract is "remove and destroy", and a
     * {@code loadUrl} into it shows nothing. The client must therefore hand the view to the host rather
     * than ask for a "recovery" reload, and the host must destroy the dead view and build a new one from
     * the single hardened factory.
     */
    @Test
    public void aDeadRendererReplacesTheWebViewInsteadOfReloadingIt() throws Exception {
        String client = code("app/arenawebview/locked/web/ArenaWebViewClient.java");
        String router = code("app/arenawebview/locked/web/PopupRouterClient.java");
        String main = code("app/arenawebview/locked/MainActivity.java");

        int at = client.indexOf("public boolean onRenderProcessGone(");
        assertTrue("the client must handle a lost renderer", at >= 0);
        String body = client.substring(at, client.indexOf("return true;", at));
        assertTrue("the host must be told that the view is dead", body.contains("host.onRenderProcessGone(view"));
        assertFalse("a dead view must not be 'recovered' by reloading it",
                body.contains("onRecoveryRequired") || body.contains("loadVerifiedDestination")
                        || body.contains("loadUrl") || body.contains("reload("));

        at = router.indexOf("public boolean onRenderProcessGone(");
        assertTrue("a dead pop-up must be finished", at >= 0
                && router.substring(at, router.indexOf("return true;", at)).contains("finish();"));

        assertTrue(main.contains("public void onRenderProcessGone(WebView view, boolean crashed)"));
        assertTrue("the dead primary view is destroyed", main.contains("dead.destroy()"));
        assertTrue("and removed from the hierarchy", main.contains("removeFromParent(dead)"));
        assertTrue("and replaced through the one factory", count(main, "attachPrimaryWebView();") == 2);
        assertEquals("the primary view is created in exactly one method", 1, count(main, "createPrimaryWebView("));
    }
}
