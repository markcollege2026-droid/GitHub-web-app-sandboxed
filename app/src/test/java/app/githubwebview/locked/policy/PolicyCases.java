package app.githubwebview.locked.policy;

import static app.githubwebview.locked.policy.UrlPolicy.TrafficClass.DOCUMENT;
import static app.githubwebview.locked.policy.UrlPolicy.TrafficClass.SUBDOCUMENT;
import static app.githubwebview.locked.policy.UrlPolicy.TrafficClass.SUBRESOURCE;

import java.util.ArrayList;
import java.util.List;

/**
 * The table of URL cases the policy is verified against.
 *
 * <p>Kept in one place so the table can be read as documentation of the intended behaviour: every row
 * is an assertion that the wrapper either allows exactly this URL or refuses it for exactly this
 * reason. Rows are added for every bypass technique that was considered (scheme smuggling, userinfo,
 * look-alike domains, parser differentials, IP literals, ports, ...).</p>
 */
public final class PolicyCases {

    /** One expected outcome. */
    public static final class Case {
        public final String name;
        public final String url;
        public final UrlPolicy.TrafficClass trafficClass;
        public final boolean allowed;
        public final BlockReason reason;

        Case(String name, String url, UrlPolicy.TrafficClass trafficClass, boolean allowed, BlockReason reason) {
            this.name = name;
            this.url = url;
            this.trafficClass = trafficClass;
            this.allowed = allowed;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private PolicyCases() {
        // no instances
    }

    /** Every case, with the default (shipping) policy: first-party hosts only. */
    public static List<Case> all() {
        List<Case> cases = new ArrayList<Case>();

        // ---------------------------------------------------------------------------------------
        // 1. What must keep working
        // ---------------------------------------------------------------------------------------
        both(cases, "start page", "https://github.com/", true, null);
        both(cases, "start page without trailing slash", "https://github.com", true, null);
        both(cases, "deep link inside the site", "https://github.com/octocat/Hello-World/blob/master/README", true, null);
        both(cases, "query string", "https://github.com/search?q=hello%20world", true, null);
        both(cases, "query with a raw space (Chromium encodes it)", "https://github.com/?q=a b", true, null);
        both(cases, "fragment is client side", "https://github.com/#/chat/42", true, null);
        both(cases, "fragment containing another URL is harmless", "https://github.com/#https://evil.example/",
                true, null);
        both(cases, "explicit default HTTPS port", "https://github.com:443/chat", true, null);
        both(cases, "upper case scheme and host", "HTTPS://GITHUB.COM/Chat", true, null);
        both(cases, "www host", "https://www.github.com/octocat/Hello-World", true, null);
        both(cases, "Google account handoff", "https://accounts.google.com/signin/v2/identifier", true, null);
        for (String path : new String[]{"/octocat/Hello-World", "/octocat/Hello-World/blob/master/README",
                "/octocat/Hello-World/edit/master/README", "/octocat/Hello-World/commits/master",
                "/octocat/Hello-World/issues/1", "/octocat/Hello-World/pull/1",
                "/octocat/Hello-World/actions", "/octocat/Hello-World/actions/runs/1/job/2",
                "/octocat/Hello-World/edit/master/.github/workflows/build.yml", "/login", "/session"}) {
            both(cases, "GitHub workflow " + path, "https://github.com" + path, true, null);
        }
        // Resource permission is never navigation permission, even for GitHub-owned hosts.
        for (String host : new String[]{
                "github.githubassets.com",
                "api.github.com",
                "avatars.githubusercontent.com",
                "private-avatars.githubusercontent.com",
                "identicons.github.com",
                "user-images.githubusercontent.com",
                "private-user-images.githubusercontent.com",
                "secured-user-images.githubusercontent.com",
                "camo.githubusercontent.com",
                "raw.githubusercontent.com",
                "media.githubusercontent.com",
                "objects.githubusercontent.com",
                "objects-origin.githubusercontent.com",
                "release-assets.githubusercontent.com",
                "codeload.github.com",
                "uploads.github.com",
                "viewscreen.githubusercontent.com",
                "notebooks.githubusercontent.com",
                "repository-images.githubusercontent.com",
                "opengraph.githubassets.com",
                "alive.github.com",
                "github-cloud.s3.amazonaws.com",
                "github-production-repository-file-5c1aeb.s3.amazonaws.com",
                "github-production-upload-manifest-file-7fdce7.s3.amazonaws.com",
                "github-production-user-asset-6210df.s3.amazonaws.com",
                "github-production-repository-image-32fea6.s3.amazonaws.com",
                "github-production-release-asset-2e65be.s3.amazonaws.com",
                "productionresultssa0.blob.core.windows.net",
                "productionresultssa19.blob.core.windows.net",
                "productionresultssa30.blob.core.windows.net",
                "productionresultssa49.blob.core.windows.net",
                "productionresultssa100.blob.core.windows.net",
                "productionresultssa199.blob.core.windows.net",
                "productionresultssa200.blob.core.windows.net"}) {
            String resource = "https://" + host + "/owner/repo/resource";
            sub(cases, host + " resource", resource, true, null);
            frame(cases, host + " embedded document", resource, true, null);
            doc(cases, host + " never a top-level page", resource, false, BlockReason.DOCUMENT_NOT_ALLOWED);
        }
        for (String host : new String[]{"docs.github.com", "gist.github.com", "example.github.io",
                "collector.github.com", "api.githubcopilot.com", "arena.ai", "example.s3.amazonaws.com",
                "example.blob.core.windows.net", "appleid.apple.com", "company.okta.com"}) {
            both(cases, "unapproved destination " + host, "https://" + host + "/", false, BlockReason.HOST_NOT_ALLOWED);
        }
        // GitHub Actions results storage pattern: the complete hostname must be
        // productionresultssa + digits + .blob.core.windows.net. Look-alikes are refused.
        for (String host : new String[]{
                "productionresultssa30.blob.core.windows.net.evil.example",
                "evilproductionresultssa30.blob.core.windows.net",
                "productionresultssaX.blob.core.windows.net",
                "productionresultssa.blob.core.windows.net",
                "productionresultssa30.blob.core.windows.net.",
                "unrelated.blob.core.windows.net"}) {
            // The trailing-dot host is refused as INVALID_HOST by canonicalisation, the rest as
            // HOST_NOT_ALLOWED because the anchored pattern does not match the complete hostname.
            BlockReason reason = host.endsWith(".")
                    ? BlockReason.INVALID_HOST : BlockReason.HOST_NOT_ALLOWED;
            both(cases, "results storage look-alike " + host, "https://" + host + "/x", false, reason);
        }
        sub(cases, "results storage host upper case", "https://PRODUCTIONRESULTSSA30.BLOB.CORE.WINDOWS.NET/x", true, null);
        frame(cases, "results storage host upper case frame", "https://PRODUCTIONRESULTSSA30.BLOB.CORE.WINDOWS.NET/x", true, null);
        doc(cases, "results storage host upper case never a page", "https://PRODUCTIONRESULTSSA30.BLOB.CORE.WINDOWS.NET/x", false, BlockReason.DOCUMENT_NOT_ALLOWED);
        both(cases, "non-ASCII characters in the path only", "https://github.com/caf\u00e9", true, null);
        both(cases, "percent-encoded path", "https://github.com/chat/%E6%97%A5%E6%9C%AC", true, null);
        both(cases, "dot-segments in the path cannot change the host", "https://github.com/../evil.example",
                true, null);
        both(cases, "leading and trailing whitespace around the URL",
                "  https://github.com/chat  ", true, null);
        both(cases, "tab inside the scheme (Chromium removes it too)", "ht\ttps://github.com/", true, null);
        both(cases, "newline inside the URL (Chromium removes it too)", "https://github.com\n/chat", true, null);

        // `about:blank` / `about:srcdoc` are inert browser-generated documents.
        doc(cases, "about:blank as a document", "about:blank", true, null);
        sub(cases, "about:srcdoc as a frame", "about:srcdoc", true, null);
        doc(cases, "about:srcdoc may not become the page", "about:srcdoc", false,
                BlockReason.UNSUPPORTED_SCHEME);
        doc(cases, "about:config style internal page", "about:config", false,
                BlockReason.UNSUPPORTED_SCHEME);
        sub(cases, "inline data: image", "data:image/png;base64,iVBORw0KGgo=", true, null);
        sub(cases, "inline data: iframe document", "data:text/html,<b>hi</b>", true, null);
        sub(cases, "blob belonging to the site itself", "blob:https://github.com/1a2b3c4d", true, null);
        sub(cases, "blob belonging to the site itself, upper-case origin", "blob:HTTPS://GITHUB.COM/1a2b3c4d",
                true, null);
        sub(cases, "blob belonging to the site itself, explicit default port",
                "blob:https://github.com:443/1a2b3c4d", true, null);
        sub(cases, "blob belonging to the auth service", "blob:https://accounts.google.com/1a2b3c4d", true, null);

        // ---------------------------------------------------------------------------------------
        // 2. Inert schemes are not documents: they may never become the visible page
        // ---------------------------------------------------------------------------------------
        doc(cases, "data: as a document", "data:text/html,<script>alert(1)</script>", false,
                BlockReason.UNSUPPORTED_SCHEME);
        doc(cases, "blob: as a document", "blob:https://github.com/1a2b3c4d", false,
                BlockReason.UNSUPPORTED_SCHEME);
        sub(cases, "blob of a foreign origin", "blob:https://evil.example/1a2b3c4d", false,
                BlockReason.HOST_NOT_ALLOWED);
        sub(cases, "blob of the wrong sub-domain", "blob:https://mail.github.com/1a2b3c4d", false,
                BlockReason.HOST_NOT_ALLOWED);
        sub(cases, "blob with an opaque origin", "blob:null/1a2b3c4d", false,
                BlockReason.UNSUPPORTED_SCHEME);
        sub(cases, "blob with an http origin", "blob:http://github.com/1a2b3c4d", false,
                BlockReason.SCHEME_NOT_HTTPS);
        sub(cases, "blob whose 'origin' smuggles userinfo", "blob:https://github.com@evil.example/1a2b3c4d",
                false, BlockReason.USERINFO_PRESENT);
        sub(cases, "blob whose 'origin' has a non-default port", "blob:https://github.com:8443/1a2b3c4d",
                false, BlockReason.PORT_NOT_ALLOWED);
        sub(cases, "blob of a trailing-dot origin", "blob:https://github.com./1a2b3c4d", false,
                BlockReason.INVALID_HOST);
        frame(cases, "blob: as a frame", "blob:https://github.com/1a2b3c4d", false,
                BlockReason.UNSUPPORTED_SCHEME);

        // ---------------------------------------------------------------------------------------
        // 3. Schemes that could leave the app or reach another app
        // ---------------------------------------------------------------------------------------
        both(cases, "javascript: URL", "javascript:alert(document.cookie)", false,
                BlockReason.UNSUPPORTED_SCHEME);
        both(cases, "javascript: URL split by a newline", "java\nscript:alert(1)", false,
                BlockReason.UNSUPPORTED_SCHEME);
        both(cases, "javascript: with mixed case", "JaVaScRiPt:alert(1)", false,
                BlockReason.UNSUPPORTED_SCHEME);
        both(cases, "javascript: with a leading space", "  javascript:alert(1)", false,
                BlockReason.UNSUPPORTED_SCHEME);
        both(cases, "intent: URL (would launch another app)", "intent://scan/#Intent;scheme=zxing;end", false,
                BlockReason.UNSUPPORTED_SCHEME);
        both(cases, "play store scheme", "market://details?id=com.example", false,
                BlockReason.UNSUPPORTED_SCHEME);
        both(cases, "mailto:", "mailto:someone@github.com", false, BlockReason.UNSUPPORTED_SCHEME);
        both(cases, "tel:", "tel:+15551234567", false, BlockReason.UNSUPPORTED_SCHEME);
        both(cases, "sms:", "sms:+15551234567", false, BlockReason.UNSUPPORTED_SCHEME);
        both(cases, "third-party app deep link", "whatsapp://send?text=hi", false,
                BlockReason.UNSUPPORTED_SCHEME);
        both(cases, "custom scheme", "myapp://open?url=https://github.com", false,
                BlockReason.UNSUPPORTED_SCHEME);
        both(cases, "file: URL", "file:///etc/hosts", false, BlockReason.UNSUPPORTED_SCHEME);
        both(cases, "file: URL to app assets", "file:///android_asset/index.html", false,
                BlockReason.UNSUPPORTED_SCHEME);
        both(cases, "content: URL", "content://com.android.providers.media.documents/document/1", false,
                BlockReason.UNSUPPORTED_SCHEME);
        both(cases, "chrome: URL", "chrome://settings", false, BlockReason.UNSUPPORTED_SCHEME);
        both(cases, "view-source: URL", "view-source:https://github.com/", false,
                BlockReason.UNSUPPORTED_SCHEME);
        both(cases, "filesystem: URL", "filesystem:https://github.com/temporary/x", false,
                BlockReason.UNSUPPORTED_SCHEME);
        both(cases, "cleartext http on the allowed host", "http://github.com/", false,
                BlockReason.SCHEME_NOT_HTTPS);
        both(cases, "cleartext http on a third party", "http://evil.example/", false,
                BlockReason.SCHEME_NOT_HTTPS);
        both(cases, "protocol-relative URL", "//github.com/", false, BlockReason.MISSING_SCHEME);
        both(cases, "bare host without a scheme", "github.com", false, BlockReason.MISSING_SCHEME);
        both(cases, "scheme starting with a digit", "1http://github.com/", false,
                BlockReason.INVALID_SCHEME);
        both(cases, "no authority after https:", "https:github.com", false,
                BlockReason.MALFORMED_AUTHORITY);
        both(cases, "https: with nothing else", "https:", false, BlockReason.MALFORMED_AUTHORITY);
        both(cases, "empty authority", "https:///", false, BlockReason.EMPTY_HOST);
        both(cases, "collapsed slashes (Chromium would collapse them)", "https:////github.com/", false,
                BlockReason.EMPTY_HOST);
        both(cases, "userinfo hiding the real host", "https://github.com@evil.example/", false,
                BlockReason.USERINFO_PRESENT);
        both(cases, "userinfo with credentials", "https://user:pass@github.com/", false,
                BlockReason.USERINFO_PRESENT);
        both(cases, "empty userinfo", "https://@github.com/", false, BlockReason.USERINFO_PRESENT);
        both(cases, "IPv6 literal", "https://[::1]/", false, BlockReason.IP_LITERAL_HOST);
        both(cases, "IPv6 literal with port", "https://[2001:db8::1]:443/", false,
                BlockReason.IP_LITERAL_HOST);
        both(cases, "non-default port", "https://github.com:8443/", false,
                BlockReason.PORT_NOT_ALLOWED);
        both(cases, "port 80", "https://github.com:80/", false, BlockReason.PORT_NOT_ALLOWED);
        both(cases, "empty port", "https://github.com:/", false, BlockReason.PORT_NOT_ALLOWED);
        both(cases, "double port", "https://github.com:443:443/", false, BlockReason.INVALID_HOST);

        // ---------------------------------------------------------------------------------------
        // 4. Hosts that are not exactly on the list (the whole point of the exercise)
        // ---------------------------------------------------------------------------------------
        both(cases, "plain third party", "https://evil.example/", false, BlockReason.HOST_NOT_ALLOWED);
        both(cases, "third party with default port", "https://evil.example:443/", false,
                BlockReason.HOST_NOT_ALLOWED);
        both(cases, "third party in the path of an allowed host", "https://github.com/evil.example", true, null);
        both(cases, "hyphen look-alike", "https://git-hub.com/", false, BlockReason.HOST_NOT_ALLOWED);
        both(cases, "no-dot look-alike", "https://githubcom.com/", false, BlockReason.HOST_NOT_ALLOWED);
        both(cases, "plural look-alike", "https://githubs.com/", false, BlockReason.HOST_NOT_ALLOWED);
        both(cases, "sub-domain look-alike", "https://github.com.evil.example/", false,
                BlockReason.HOST_NOT_ALLOWED);
        both(cases, "allowed host as a sub-domain of a third party", "https://github.com.evil.example/x", false,
                BlockReason.HOST_NOT_ALLOWED);
        both(cases, "unlisted sub-domain", "https://sub.github.com/", false, BlockReason.HOST_NOT_ALLOWED);
        both(cases, "mail infrastructure sub-domain", "https://mail.github.com/", false,
                BlockReason.HOST_NOT_ALLOWED);
        both(cases, "cPanel sub-domain", "https://cpanel.github.com/", false, BlockReason.HOST_NOT_ALLOWED);
        both(cases, "SSO sub-domain (needs an explicit decision)", "https://okta.github.com/", false,
                BlockReason.HOST_NOT_ALLOWED);
        both(cases, "non-production preview host", "https://api.preview.github.com/", false,
                BlockReason.HOST_NOT_ALLOWED);
        both(cases, "non-production portal host", "https://portal.api.preview.github.com/", false,
                BlockReason.HOST_NOT_ALLOWED);
        both(cases, "e-mail security host", "https://mta-sts.github.com/", false, BlockReason.HOST_NOT_ALLOWED);
        // A trailing dot names the same DNS zone but a *different browser origin* (no shared cookies or
        // storage, CORS mismatch against the real site). It is never produced by the site and is refused
        // instead of being folded into the canonical host, so this validator and Chromium agree on what
        // the host is.
        both(cases, "single trailing dot (fully qualified name)", "https://github.com./chat", false,
                BlockReason.INVALID_HOST);
        both(cases, "trailing dot with default port", "https://github.com.:443/", false,
                BlockReason.INVALID_HOST);
        both(cases, "trailing dot on a sign-in host", "https://accounts.google.com./signin", false,
                BlockReason.INVALID_HOST);
        both(cases, "two trailing dots", "https://github.com../", false, BlockReason.INVALID_HOST);
        both(cases, "punycode look-alike", "https://xn--rena-4qa.ai/", false, BlockReason.HOST_NOT_ALLOWED);
        both(cases, "punycode of the real host", "https://xn--arena-2d0b.ai/", false,
                BlockReason.HOST_NOT_ALLOWED);
        both(cases, "Cyrillic look-alike 'a'", "https://g\u0456thub.com/", false, BlockReason.NON_ASCII_HOST);
        both(cases, "ideographic full stop", "https://github\u3002com/", false, BlockReason.NON_ASCII_HOST);
        both(cases, "zero-width joiner in the host", "https://gith\u200dub.com/", false,
                BlockReason.NON_ASCII_HOST);
        both(cases, "percent-encoded dot in the host", "https://github.com%2e/", false,
                BlockReason.INVALID_HOST);
        both(cases, "percent-encoded NUL in the host", "https://github.com%00/", false,
                BlockReason.INVALID_HOST);
        both(cases, "underscore in the host", "https://github_com.example/", false,
                BlockReason.INVALID_HOST);
        both(cases, "IPv4 literal", "https://127.0.0.1/", false, BlockReason.INVALID_HOST);
        both(cases, "decimal IP literal", "https://2130706433/", false, BlockReason.INVALID_HOST);
        both(cases, "hex IP literal", "https://0x7f.0.0.1/", false, BlockReason.INVALID_HOST);
        both(cases, "single label host", "https://arena/", false, BlockReason.INVALID_HOST);
        both(cases, "empty label", "https://github..com/", false, BlockReason.INVALID_HOST);
        both(cases, "leading dot", "https://.github.com/", false, BlockReason.INVALID_HOST);
        both(cases, "leading hyphen", "https://-github.com/", false, BlockReason.INVALID_HOST);
        both(cases, "trailing hyphen", "https://github.com-/", false, BlockReason.INVALID_HOST);
        both(cases, "numeric top level domain", "https://github.123/", false, BlockReason.INVALID_HOST);
        both(cases, "backslash before an at-sign", "https://evil.example\\@github.com/", false,
                BlockReason.BACKSLASH_AMBIGUITY);
        both(cases, "backslash in the host", "https://github.com\\evil.example/", false,
                BlockReason.BACKSLASH_AMBIGUITY);
        both(cases, "backslash after the scheme", "https:\\\\evil.example/", false,
                BlockReason.BACKSLASH_AMBIGUITY);
        both(cases, "tab-separated look-alike host", "https://github.com\tevil.example/", false,
                BlockReason.HOST_NOT_ALLOWED);
        both(cases, "NUL in the path", "https://github.com/\u0000evil", false, BlockReason.CONTROL_CHARACTER);
        both(cases, "carriage return is removed, exactly like Chromium does", "https://github.com\r/chat",
                true, null);
        both(cases, "control character in the path", "https://github.com/\u0001evil", false,
                BlockReason.CONTROL_CHARACTER);
        both(cases, "control character in the host", "https://arena\u0001.ai/", false,
                BlockReason.CONTROL_CHARACTER);
        both(cases, "DEL in the URL", "https://github.com/\u007f", false,
                BlockReason.CONTROL_CHARACTER);
        both(cases, "colon in the middle of the authority", "https://github.com:443@evil.example/", false,
                BlockReason.USERINFO_PRESENT);

        // ---------------------------------------------------------------------------------------
        // 5. Degenerate input must never be "allowed"
        // ---------------------------------------------------------------------------------------
        both(cases, "null URL", null, false, BlockReason.NULL_URL);
        both(cases, "empty URL", "", false, BlockReason.EMPTY_URL);
        both(cases, "whitespace only URL", "   ", false, BlockReason.EMPTY_URL);
        both(cases, "padded empty URL", "\t\n", false, BlockReason.EMPTY_URL);
        both(cases, "colon only", ":", false, BlockReason.MISSING_SCHEME);
        both(cases, "scheme with no host", "https://?x", false, BlockReason.EMPTY_HOST);

        return cases;
    }

    /**
     * The Google sign-in hand-off and the CAPTCHA widgets, which this build enables (see
     * {@link GitHubAllowlist#ENABLE_GOOGLE_SIGN_IN} and {@link GitHubAllowlist#ENABLE_CAPTCHA_WIDGETS}).
     *
     * <p>These rows assert both halves of the trade-off: the URLs the real flow needs are allowed, and
     * everything next to them stays refused - Google's other products, look-alike hosts, the asset hosts
     * as pages, and any path outside a pinned widget path.</p>
     */
    public static List<Case> withGoogleSignInAndCaptchaEnabled() {
        List<Case> cases = new ArrayList<Case>();

        // --- the sign-in flow itself --------------------------------------------------------------
        doc(cases, "Google OAuth authorize URL", "https://accounts.google.com/o/oauth2/v2/auth"
                + "?client_id=123.apps.googleusercontent.com&redirect_uri=https%3A%2F%2Fgithub.com%2F"
                + "&response_type=code&scope=openid+email", true, null);
        doc(cases, "Google sign-in identifier page",
                "https://accounts.google.com/signin/v2/identifier?flowName=GlifWebSignIn", true, null);
        doc(cases, "Google account chooser", "https://accounts.google.com/AccountChooser?continue=x", true, null);
        doc(cases, "Google consent page", "https://accounts.google.com/signin/oauth/consent?authuser=0",
                true, null);
        doc(cases, "Google client-side identity library",
                "https://accounts.google.com/gsi/client?hl=en", true, null);
        doc(cases, "sign-in page may read its own assets",
                "https://accounts.google.com/static/accounts/xxx.css", true, null);
        sub(cases, "sign-in asset host (gstatic)",
                "https://ssl.gstatic.com/accounts/o/xxx.gif", true, null);
        sub(cases, "sign-in asset host (www.gstatic)",
                "https://www.gstatic.com/accounts/gsi/x.js", true, null);
        sub(cases, "Google identity script", "https://apis.google.com/js/client.js", true, null);
        sub(cases, "account avatar", "https://lh3.googleusercontent.com/a/ACg8ocABC=s96-c", true, null);
        sub(cases, "Google font file", "https://fonts.gstatic.com/s/roboto/v30/x.woff2", true, null);

        sub(cases, "Google identity connection check resource",
                "https://accounts.youtube.com/accounts/CheckConnection?checkConnection=1", true, null);
        frame(cases, "Google identity connection check frame",
                "https://accounts.youtube.com/accounts/CheckConnection?checkConnection=1", true, null);
        doc(cases, "connection check never grants a YouTube navigation",
                "https://accounts.youtube.com/accounts/CheckConnection", false, BlockReason.DOCUMENT_NOT_ALLOWED);
        sub(cases, "YouTube account host outside the reviewed check path",
                "https://accounts.youtube.com/watch?v=x", false, BlockReason.PATH_NOT_ALLOWED);
        both(cases, "the YouTube website is still not allowed", "https://www.youtube.com/watch?v=x",
                false, BlockReason.HOST_NOT_ALLOWED);

        // --- what stays out ----------------------------------------------------------------------
        doc(cases, "look-alike of Google's account host",
                "https://accounts.google.com.evil.example/signin", false, BlockReason.HOST_NOT_ALLOWED);
        doc(cases, "Google account host on a look-alike TLD", "https://accounts.google.co/signin",
                false, BlockReason.HOST_NOT_ALLOWED);
        doc(cases, "userinfo trick against Google's host",
                "https://accounts.google.com@evil.example/signin", false, BlockReason.USERINFO_PRESENT);
        sub(cases, "Google search stays unreachable", "https://www.google.com/search?q=hello",
                false, BlockReason.PATH_NOT_ALLOWED);
        both(cases, "Gmail stays unreachable", "https://mail.google.com/mail/u/0/",
                false, BlockReason.HOST_NOT_ALLOWED);
        both(cases, "Google Drive stays unreachable", "https://drive.google.com/drive/my-drive",
                false, BlockReason.HOST_NOT_ALLOWED);
        both(cases, "Google Play stays unreachable", "https://play.google.com/store/apps",
                false, BlockReason.HOST_NOT_ALLOWED);
        both(cases, "the account host is reachable at every path the flow uses",
                "https://accounts.google.com/v3/accounts/whatever", true, null);
        both(cases, "a Google account page is still HTTPS only", "http://accounts.google.com/signin",
                false, BlockReason.SCHEME_NOT_HTTPS);
        sub(cases, "asset host outside its pinned paths",
                "https://apis.google.com/console/whatever", false, BlockReason.PATH_NOT_ALLOWED);
        sub(cases, "avatar host outside its pinned path",
                "https://lh3.googleusercontent.com/not-an-avatar", false, BlockReason.PATH_NOT_ALLOWED);

        // --- asset hosts can never become the page ------------------------------------------------
        doc(cases, "gstatic sign-in asset may never become the page",
                "https://ssl.gstatic.com/accounts/o/x.gif", false, BlockReason.DOCUMENT_NOT_ALLOWED);
        doc(cases, "apis.google.com may never become the page",
                "https://apis.google.com/js/client.js", false, BlockReason.DOCUMENT_NOT_ALLOWED);
        doc(cases, "avatar host may never become the page",
                "https://lh3.googleusercontent.com/a/x", false, BlockReason.DOCUMENT_NOT_ALLOWED);

        // --- CAPTCHA widgets ----------------------------------------------------------------------
        sub(cases, "reCAPTCHA widget script", "https://www.google.com/recaptcha/api.js", true, null);
        sub(cases, "reCAPTCHA widget frame", "https://www.google.com/recaptcha/enterprise/anchor",
                true, null);
        sub(cases, "reCAPTCHA static asset", "https://www.gstatic.com/recaptcha/releases/x/r.js",
                true, null);
        sub(cases, "Google asset host outside the old pinned paths is reachable as an asset",
                "https://www.gstatic.com/images/branding/x.svg", true, null);
        doc(cases, "but the app is never navigated to it",
                "https://www.gstatic.com/images/branding/x.svg", false, BlockReason.DOCUMENT_NOT_ALLOWED);
        sub(cases, "reCAPTCHA on the alternate domain",
                "https://www.recaptcha.net/recaptcha/api.js", true, null);
        sub(cases, "Arkose widget", "https://github-api.arkoselabs.com/v2/key/api.js",
                true, null);
        // Path escapes are checked on a host that is still path-pinned. (Google's static asset hosts are
        // deliberately path-unrestricted now, because their paths are content-hashed and change without
        // notice - see GitHubAllowlist.signInEntries.)
        sub(cases, "path traversal out of a pinned path",
                "https://lh3.googleusercontent.com/a/../../translate/x", false,
                BlockReason.PATH_NOT_ALLOWED);
        sub(cases, "percent-encoded path escape", "https://lh3.googleusercontent.com/a%2f..%2fx", false,
                BlockReason.PATH_NOT_ALLOWED);
        frame(cases, "the Arkose embedded challenge frame",
                "https://github-api.arkoselabs.com/fc/gc/game/index.html", true, null);
        sub(cases, "Arkose host outside the widget paths",
                "https://github-api.arkoselabs.com/other/x", false, BlockReason.PATH_NOT_ALLOWED);
        doc(cases, "Arkose host may never become the page",
                "https://github-api.arkoselabs.com/v2/key/api.js", false,
                BlockReason.DOCUMENT_NOT_ALLOWED);
        doc(cases, "reCAPTCHA script may never become the page",
                "https://www.google.com/recaptcha/api.js", false, BlockReason.DOCUMENT_NOT_ALLOWED);

        // --- blobs minted by the widget and identity frames ---------------------------------------
        // A frame that the policy lets exist (asset host, pinned path) may create blob: URLs - workers,
        // images, audio challenges - and fetch them. The blob carries only the frame's *origin*, never
        // its path, so the origin is judged as an embedded document and the path pins do not apply.
        sub(cases, "blob minted by the reCAPTCHA frame", "blob:https://www.google.com/1a2b3c4d", true, null);
        sub(cases, "blob minted by a Google asset host", "blob:https://www.gstatic.com/1a2b3c4d", true, null);
        sub(cases, "blob minted by the Arkose frame", "blob:https://github-api.arkoselabs.com/1a2b3c4d",
                true, null);
        sub(cases, "blob minted by the sign-in window", "blob:https://accounts.google.com/1a2b3c4d",
                true, null);
        sub(cases, "blob of a Google host that is not on the list", "blob:https://www.googleapis.com/1a2b3c4d",
                false, BlockReason.HOST_NOT_ALLOWED);
        sub(cases, "blob of a look-alike of a widget host", "blob:https://www.google.com.evil.example/1a2b3c4d",
                false, BlockReason.HOST_NOT_ALLOWED);
        doc(cases, "a blob is never a page, whatever its origin", "blob:https://www.google.com/1a2b3c4d",
                false, BlockReason.UNSUPPORTED_SCHEME);

        for (String resource : new String[]{
                "https://octocaptcha.com/captcha",
                "https://github-api.arkoselabs.com/v2/key/api.js",
                "https://client-api.arkoselabs.com/fc/gc/game/index.html",
                "https://geo.captcha-delivery.com/captcha/?initialCid=test",
                "https://geo.captcha-delivery.com/interstitial/",
                "https://ct.captcha-delivery.com/c.js",
                "https://ct.captcha-delivery.com/i.js",
                "https://ct.captcha-delivery.com/interstitial.1.33.1.202610161.js",
                "https://ct.captcha-delivery.com/future/deployment/bundle.js",
                "https://static.captcha-delivery.com/captcha/assets/challenge.css",
                "https://images.ctfassets.net/8aevphvgewt8/image.png",
                "https://videos.ctfassets.net/8aevphvgewt8/video.mp4"}) {
            sub(cases, "required challenge/media resource " + resource, resource, true, null);
            frame(cases, "required challenge/media frame " + resource, resource, true, null);
            doc(cases, "resource must not become a page " + resource, resource, false, BlockReason.DOCUMENT_NOT_ALLOWED);
        }
        for (String resource : new String[]{
                "https://github-api.arkoselabs.com/other/",
                "https://github-api.arkoselabs.com/fc/../other",
                "https://client-api.arkoselabs.com/fc%2fescape",
                "https://geo.captcha-delivery.com/marketing/",
                "https://images.ctfassets.net/other-space/image.png"}) {
            sub(cases, "outside reviewed path " + resource, resource, false, BlockReason.PATH_NOT_ALLOWED);
        }

        return cases;
    }

    private static void doc(List<Case> out, String name, String url, boolean allowed, BlockReason reason) {
        out.add(new Case(name, url, DOCUMENT, allowed, reason));
    }

    private static void sub(List<Case> out, String name, String url, boolean allowed, BlockReason reason) {
        out.add(new Case(name, url, SUBRESOURCE, allowed, reason));
    }

    private static void both(List<Case> out, String name, String url, boolean allowed, BlockReason reason) {
        out.add(new Case(name + " (document)", url, DOCUMENT, allowed, reason));
        out.add(new Case(name + " (subresource)", url, SUBRESOURCE, allowed, reason));
    }

    private static void frame(List<Case> out, String name, String url, boolean allowed, BlockReason reason) {
        out.add(new Case(name, url, SUBDOCUMENT, allowed, reason));
    }
}
