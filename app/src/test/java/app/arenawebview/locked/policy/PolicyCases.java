package app.arenawebview.locked.policy;

import static app.arenawebview.locked.policy.UrlPolicy.TrafficClass.DOCUMENT;
import static app.arenawebview.locked.policy.UrlPolicy.TrafficClass.SUBDOCUMENT;
import static app.arenawebview.locked.policy.UrlPolicy.TrafficClass.SUBRESOURCE;

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
        // 1. What must keep working: GitHub.com pages, including authentication and core workflows.
        // ---------------------------------------------------------------------------------------
        both(cases, "start page", "https://github.com/", true, null);
        both(cases, "start page without trailing slash", "https://github.com", true, null);
        both(cases, "repository page", "https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed", true, null);
        both(cases, "repository file view", "https://github.com/owner/repository/blob/main/README.md", true, null);
        both(cases, "repository file editor", "https://github.com/owner/repository/edit/main/README.md", true, null);
        both(cases, "commit page", "https://github.com/owner/repository/commit/0123456789abcdef", true, null);
        both(cases, "issue page", "https://github.com/owner/repository/issues/12", true, null);
        both(cases, "pull request page", "https://github.com/owner/repository/pull/12", true, null);
        both(cases, "Actions page", "https://github.com/owner/repository/actions", true, null);
        both(cases, "workflow run and logs page", "https://github.com/owner/repository/actions/runs/123/jobs/456", true, null);
        both(cases, "GitHub login page", "https://github.com/login", true, null);
        both(cases, "GitHub login submission endpoint", "https://github.com/session", true, null);
        both(cases, "GitHub OAuth authorization page", "https://github.com/login/oauth/authorize?client_id=x", true, null);
        both(cases, "deep link within GitHub", "https://github.com/owner/repository/issues?q=is%3Aopen", true, null);
        both(cases, "query string", "https://github.com/search?q=hello%20world", true, null);
        both(cases, "query with a raw space (Chromium encodes it)", "https://github.com/search?q=a b", true, null);
        both(cases, "fragment is client side", "https://github.com/owner/repository/issues#issue-12", true, null);
        both(cases, "fragment containing another URL is harmless", "https://github.com/#https://evil.example/", true, null);
        both(cases, "explicit default HTTPS port", "https://github.com:443/owner/repository", true, null);
        both(cases, "upper case scheme and host", "HTTPS://GITHUB.COM/owner/repository", true, null);
        both(cases, "non-ASCII characters in the path only", "https://github.com/owner/caf\u00e9", true, null);
        both(cases, "percent-encoded path", "https://github.com/owner/%E6%97%A5%E6%9C%AC", true, null);
        both(cases, "dot-segments in the path cannot change the host", "https://github.com/../evil.example", true, null);
        both(cases, "leading and trailing whitespace around the URL", "  https://github.com/owner/repository  ", true, null);
        both(cases, "tab inside the scheme (Chromium removes it too)", "ht\ttps://github.com/", true, null);
        both(cases, "newline inside the URL (Chromium removes it too)", "https://github.com\n/owner", true, null);

        // `about:blank` / `about:srcdoc` are inert browser-generated documents.
        doc(cases, "about:blank as a document", "about:blank", true, null);
        sub(cases, "about:srcdoc as a frame", "about:srcdoc", true, null);
        doc(cases, "about:srcdoc may not become the page", "about:srcdoc", false,
                BlockReason.UNSUPPORTED_SCHEME);
        doc(cases, "about:config style internal page", "about:config", false,
                BlockReason.UNSUPPORTED_SCHEME);
        sub(cases, "inline data: image", "data:image/png;base64,iVBORw0KGgo=", true, null);
        sub(cases, "inline data: iframe document", "data:text/html,<b>hi</b>", true, null);
        sub(cases, "blob belonging to the product itself", "blob:https://github.com/1a2b3c4d", true, null);
        sub(cases, "blob belonging to the product itself, upper-case origin", "blob:HTTPS://GITHUB.COM/1a2b3c4d", true, null);
        sub(cases, "blob belonging to the product itself, explicit default port", "blob:https://github.com:443/1a2b3c4d", true, null);

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
        both(cases, "hyphen look-alike", "https://github-com.example/", false, BlockReason.HOST_NOT_ALLOWED);
        both(cases, "no-dot look-alike", "https://githubcom.example/", false, BlockReason.HOST_NOT_ALLOWED);
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
        both(cases, "trailing dot on an external identity host", "https://accounts.google.com./signin", false,
                BlockReason.INVALID_HOST);
        both(cases, "two trailing dots", "https://github.com../", false, BlockReason.INVALID_HOST);
        both(cases, "punycode look-alike", "https://xn--gthub-5va.example/", false, BlockReason.HOST_NOT_ALLOWED);
        both(cases, "punycode of the real host", "https://xn--githb-5za.example/", false,
                BlockReason.HOST_NOT_ALLOWED);
        both(cases, "Cyrillic look-alike 'a'", "https://\u0430github.com/", false, BlockReason.NON_ASCII_HOST);
        both(cases, "ideographic full stop", "https://github\u3002com/", false, BlockReason.NON_ASCII_HOST);
        both(cases, "zero-width joiner in the host", "https://githu\u200db.com/", false,
                BlockReason.NON_ASCII_HOST);
        both(cases, "percent-encoded dot in the host", "https://github.com%2e/", false,
                BlockReason.INVALID_HOST);
        both(cases, "percent-encoded NUL in the host", "https://github.com%00/", false,
                BlockReason.INVALID_HOST);
        both(cases, "underscore in the host", "https://github_ai.example/", false,
                BlockReason.INVALID_HOST);
        both(cases, "IPv4 literal", "https://127.0.0.1/", false, BlockReason.INVALID_HOST);
        both(cases, "decimal IP literal", "https://2130706433/", false, BlockReason.INVALID_HOST);
        both(cases, "hex IP literal", "https://0x7f.0.0.1/", false, BlockReason.INVALID_HOST);
        both(cases, "single label host", "https://github/", false, BlockReason.INVALID_HOST);
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
        both(cases, "control character in the host", "https://github\u0001.com/", false,
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
     * GitHub's observed static assets, repository content, API resources, and authentication boundaries.
     * Only github.com may become a page. Every supporting host is tested as an in-page resource and as a
     * rejected top-level document; Google and other external identity providers are not enabled by
     * default because GitHub.com login itself is first-party and SAML providers are organization-specific.
     */
    public static List<Case> withGitHubResourcesEnabled() {
        List<Case> cases = new ArrayList<Case>();

        sub(cases, "GitHub JavaScript bundle", "https://github.githubassets.com/assets/app-0123.js", true, null);
        sub(cases, "GitHub CSS bundle", "https://github.githubassets.com/assets/github-0123.css", true, null);
        sub(cases, "GitHub font bundle", "https://github.githubassets.com/assets/font-0123.woff2", true, null);
        sub(cases, "GitHub static asset outside the reviewed path", "https://github.githubassets.com/not-assets/app.js", false,
                BlockReason.PATH_NOT_ALLOWED);
        doc(cases, "GitHub asset CDN can never become a page", "https://github.githubassets.com/assets/app.js", false,
                BlockReason.DOCUMENT_NOT_ALLOWED);

        sub(cases, "GitHub avatar", "https://avatars.githubusercontent.com/u/583231?v=4", true, null);
        sub(cases, "GitHub user attachment image", "https://user-images.githubusercontent.com/1234/5678/image.png", true, null);
        sub(cases, "GitHub private attachment image", "https://private-user-images.githubusercontent.com/1234/5678/image.png", true, null);
        sub(cases, "GitHub repository raw content as a resource", "https://raw.githubusercontent.com/owner/repo/main/README.md", true, null);
        sub(cases, "GitHub Open Graph repository preview", "https://opengraph.githubassets.com/012345/owner/repo", true, null);
        sub(cases, "GitHub legacy content bucket resource", "https://github-cloud.s3.amazonaws.com/opaque/object-key", true, null);

        sub(cases, "GitHub REST repository data", "https://api.github.com/repos/owner/repo/issues", true, null);
        sub(cases, "GitHub REST user data", "https://api.github.com/users/octocat", true, null);
        sub(cases, "GitHub GraphQL endpoint", "https://api.github.com/graphql", true, null);
        sub(cases, "GitHub search API endpoint", "https://api.github.com/search/issues?q=repo:owner/repo", true, null);
        sub(cases, "GitHub notification API endpoint", "https://api.github.com/notifications", true, null);
        sub(cases, "GitHub REST path outside approved prefixes", "https://api.github.com/gists", false,
                BlockReason.PATH_NOT_ALLOWED);
        doc(cases, "GitHub API host is never a top-level page", "https://api.github.com/repos/owner/repo", false,
                BlockReason.DOCUMENT_NOT_ALLOWED);

        doc(cases, "raw repository host is never a top-level destination", "https://raw.githubusercontent.com/owner/repo/main/README.md", false,
                BlockReason.DOCUMENT_NOT_ALLOWED);
        doc(cases, "avatar host is never a top-level destination", "https://avatars.githubusercontent.com/u/583231", false,
                BlockReason.DOCUMENT_NOT_ALLOWED);
        doc(cases, "uploaded-image host is never a top-level destination", "https://user-images.githubusercontent.com/1234/image.png", false,
                BlockReason.DOCUMENT_NOT_ALLOWED);
        doc(cases, "private uploaded-image host is never a top-level destination", "https://private-user-images.githubusercontent.com/1234/image.png", false,
                BlockReason.DOCUMENT_NOT_ALLOWED);
        doc(cases, "Open Graph host is never a top-level destination", "https://opengraph.githubassets.com/012345/owner/repo", false,
                BlockReason.DOCUMENT_NOT_ALLOWED);
        doc(cases, "GitHub S3 content host is never a top-level destination", "https://github-cloud.s3.amazonaws.com/opaque/object-key", false,
                BlockReason.DOCUMENT_NOT_ALLOWED);

        both(cases, "Google account sign-in is not a GitHub-wide hand-off", "https://accounts.google.com/signin", false,
                BlockReason.HOST_NOT_ALLOWED);
        both(cases, "Google static resources are not required by GitHub login", "https://www.gstatic.com/identity.js", false,
                BlockReason.HOST_NOT_ALLOWED);
        both(cases, "GitHub Copilot API is not required for core workflows", "https://api.githubcopilot.com/chat", false,
                BlockReason.HOST_NOT_ALLOWED);
        both(cases, "GitHub documentation is an external destination", "https://docs.github.com/en", false,
                BlockReason.HOST_NOT_ALLOWED);
        both(cases, "GitHub support is an external destination", "https://support.github.com/", false,
                BlockReason.HOST_NOT_ALLOWED);
        both(cases, "GitHub Pages is not in the app's web-app allowlist", "https://example.github.io/", false,
                BlockReason.HOST_NOT_ALLOWED);
        both(cases, "GitHub Codespaces is not in the app's allowlist", "https://github.dev/owner/repo", false,
                BlockReason.HOST_NOT_ALLOWED);
        both(cases, "GitHub Gist is a separate site and is not enabled", "https://gist.github.com/owner/123", false,
                BlockReason.HOST_NOT_ALLOWED);
        both(cases, "source archive host is excluded because downloads are blocked", "https://codeload.github.com/owner/repo/zip/refs/heads/main", false,
                BlockReason.HOST_NOT_ALLOWED);
        both(cases, "release object host is excluded because downloads are blocked", "https://objects.githubusercontent.com/owner/repo/release", false,
                BlockReason.HOST_NOT_ALLOWED);
        both(cases, "GitHub telemetry collector is not essential", "https://collector.github.com/github/collect", false,
                BlockReason.HOST_NOT_ALLOWED);

        // Same-origin login and callback pages remain GitHub.com pages; no provider host is granted page role.
        doc(cases, "GitHub sign-in stays on github.com", "https://github.com/login", true, null);
        doc(cases, "GitHub OAuth authorization stays on github.com", "https://github.com/login/oauth/authorize", true, null);
        doc(cases, "GitHub login callback stays on github.com", "https://github.com/login/oauth/callback", true, null);

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
