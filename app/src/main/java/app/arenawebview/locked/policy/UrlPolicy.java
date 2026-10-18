package app.arenawebview.locked.policy;

import java.util.Locale;

/**
 * Strict, fail-closed URL validation for the github.com wrapper.
 *
 * <p><b>Design rules</b></p>
 * <ol>
 *   <li><b>Deny by default.</b> Every input that cannot be positively proven to be
 *       {@code https://<exactly an allow-listed host>} is refused with a {@link BlockReason}.</li>
 *   <li><b>No wildcards, no suffix matching.</b> A host is accepted only if it is byte-for-byte equal
 *       to an allow-list entry after lower-casing. Nothing else is normalised: a trailing dot
 *       ({@code github.com.}) is a <em>different origin</em> to a browser (separate cookies, separate
 *       storage, CORS mismatch), so it is refused rather than folded into the canonical host. This
 *       removes the entire class of "look-alike" bypasses and keeps this validator's notion of "host"
 *       identical to Chromium's.</li>
 *   <li><b>Kill parser differentials before parsing.</b> Control characters and backslashes are
 *       rejected outright, and tab/newline removal mirrors the WHATWG URL standard that Chromium
 *       implements, so a URL can never mean one thing to this validator and another to the WebView.</li>
 *   <li><b>Never touch the network.</b> This class performs pure string analysis: no DNS, no HTTP,
 *       no Android APIs, no logging. That is what makes it exhaustively unit-testable.</li>
 * </ol>
 *
 * <p>The validator deliberately does <em>not</em> attempt to "repair" hostile input; there is no
 * normalisation that could turn a refused URL into an accepted one.</p>
 */
public final class UrlPolicy {

    /**
     * What the URL is being used for. The classes differ in two ways: which schemes can ever be allowed,
     * and whether a host that exists only to serve assets may be used.
     */
    public enum TrafficClass {
        /**
         * The URL can become the app's own visible page: the main frame, a pop-up window, a redirect the
         * main frame follows. Nothing that is not a real page may ever end up here.
         */
        DOCUMENT,
        /**
         * A document rendered <em>inside</em> a page: {@code <iframe>}, {@code <frame>}, {@code <embed>}.
         *
         * <p>This is deliberately not the same as {@link #DOCUMENT}. An asset host may provide an embedded
         * document or frame. A sub-document cannot replace the app's page (only the main frame can), cannot
         * navigate the app anywhere and cannot escape the page it is embedded in, so it is treated like a
         * resource request: same host and path rules, but usable.</p>
         */
        SUBDOCUMENT,
        /** The URL addresses a sub-resource: script, style, image, media, XHR/fetch, service worker. */
        SUBRESOURCE;

        /** True when this traffic can never become the app's own visible page. */
        public boolean isInPage() {
            return this != DOCUMENT;
        }
    }

    private static final int MAX_HOST_LENGTH = 253;
    private static final int MAX_LABEL_LENGTH = 63;
    private static final int MAX_DETAIL_LENGTH = 120;

    private UrlPolicy() {
        // no instances
    }

    /**
     * Evaluate a URL against the policy.
     *
     * @param rawUrl      URL exactly as supplied by the WebView (or by app code)
     * @param config      the active policy
     * @param trafficClass whether the URL can become a rendered document
     * @return an immutable {@link PolicyResult}; never null
     */
    public static PolicyResult evaluate(String rawUrl, PolicyConfig config, TrafficClass trafficClass) {
        return evaluate(rawUrl, config, trafficClass, true);
    }

    /**
     * @param checkPath false only when evaluating the <em>origin</em> embedded in a {@code blob:} URL,
     *                  where the text after the authority is the blob's UUID and not a path on the host
     */
    private static PolicyResult evaluate(String rawUrl, PolicyConfig config, TrafficClass trafficClass,
                                         boolean checkPath) {
        if (config == null) {
            return PolicyResult.block(BlockReason.INVALID_HOST, "", rawUrl, "no policy configured");
        }
        if (trafficClass == null) {
            // A caller that cannot say what the URL is for gets the fail-closed answer, not an exception
            // thrown inside a WebView callback (which would take the whole app down).
            return PolicyResult.block(BlockReason.INVALID_HOST, "", rawUrl, "no traffic class");
        }
        if (rawUrl == null) {
            return PolicyResult.block(BlockReason.NULL_URL, "", null, "null");
        }

        // --- 1. Canonicalise the way a browser does, then refuse anything still suspicious. --------
        // WHATWG URL parsing removes ASCII tab and newline anywhere in the input. Mirroring that keeps
        // this validator and Chromium in agreement ("java\nscript:" is "javascript:" for both).
        String url = removeTabAndNewlines(rawUrl);
        url = stripLeadingAndTrailingC0AndSpace(url);
        if (url.isEmpty()) {
            return PolicyResult.block(BlockReason.EMPTY_URL, "", rawUrl, "empty");
        }
        if (containsControlCharacter(url)) {
            return PolicyResult.block(BlockReason.CONTROL_CHARACTER, "", rawUrl, "control character");
        }
        // Backslashes are '/' for special (http/https) URLs. Two parsers can therefore disagree about
        // where the authority ends, so a backslash is refused rather than interpreted.
        if (url.indexOf('\\') >= 0) {
            return PolicyResult.block(BlockReason.BACKSLASH_AMBIGUITY, "", rawUrl, "backslash");
        }

        // --- 2. Scheme. ---------------------------------------------------------------------------
        int colon = url.indexOf(':');
        if (colon <= 0) {
            return PolicyResult.block(BlockReason.MISSING_SCHEME, "", rawUrl,
                    truncate(url));
        }
        String schemeRaw = url.substring(0, colon);
        if (!isValidScheme(schemeRaw)) {
            return PolicyResult.block(BlockReason.INVALID_SCHEME, "", rawUrl, truncate(schemeRaw));
        }
        String scheme = schemeRaw.toLowerCase(Locale.ROOT);

        if (!"https".equals(scheme)) {
            // A handful of schemes are generated by the browser itself, cannot address a remote host and
            // are needed for ordinary page behaviour. They are tolerated only in the roles the browser
            // actually uses them for, and only after their origin (if they have one) has been checked.
            String remainder = url.substring(colon + 1);

            if ("about".equals(scheme)) {
                if ("blank".equalsIgnoreCase(remainder)) {
                    // Inert empty document: the bootstrap of every embed/iframe.
                    return PolicyResult.allow("", rawUrl, null);
                }
                if (trafficClass.isInPage() && "srcdoc".equalsIgnoreCase(remainder)) {
                    // <iframe srcdoc> takes this URL; it is a same-origin, network-free document.
                    return PolicyResult.allow("", rawUrl, null);
                }
                return PolicyResult.block(BlockReason.UNSUPPORTED_SCHEME, "", rawUrl, truncate(remainder));
            }
            if (trafficClass == TrafficClass.SUBRESOURCE && "data".equals(scheme)) {
                // No origin and no network: a data: URL can only be an in-page resource.
                return PolicyResult.allow("", rawUrl, null);
            }
            if (trafficClass == TrafficClass.SUBRESOURCE && "blob".equals(scheme)) {
                // A blob URL is <blob:origin/uuid>. Pinning the embedded origin to the allow-list makes
                // even a forged-origin blob string useless, and fails closed for opaque origins
                // ("blob:null/...") which this wrapper has no use for.
                //
                // Only a document can mint a blob, and a document from any allow-listed host can exist in
                // this app - GitHub.com and any explicitly approved sign-in page as the page itself, and asset
                // hosts as embedded frames - so the origin is asked the embedded-document question. The text
                // after the origin is the blob's UUID, not a path on the host, so the entry's path pins
                // do not apply to it (and could never be satisfied by it).
                if (startsWithIgnoreCase(remainder, "https://") || startsWithIgnoreCase(remainder, "http://")) {
                    PolicyResult origin = evaluate(remainder, config, TrafficClass.SUBDOCUMENT, false);
                    if (!origin.isAllowed()) {
                        // Report the reason the *origin* was refused, which is more useful than a generic
                        // "unsupported scheme".
                        return PolicyResult.block(origin.reason(), origin.canonicalHost(), rawUrl,
                                "blob origin refused");
                    }
                    return PolicyResult.allow(origin.canonicalHost(), rawUrl, origin.matchedEntry());
                }
                return PolicyResult.block(BlockReason.UNSUPPORTED_SCHEME, "", rawUrl,
                        "blob without an https origin");
            }
            if ("http".equals(scheme)) {
                return PolicyResult.block(BlockReason.SCHEME_NOT_HTTPS, "", rawUrl, "http");
            }
            return PolicyResult.block(BlockReason.UNSUPPORTED_SCHEME, "", rawUrl, truncate(scheme));
        }

        // --- 3. Authority. ------------------------------------------------------------------------
        String rest = url.substring(colon + 1);
        if (!rest.startsWith("//")) {
            return PolicyResult.block(BlockReason.MALFORMED_AUTHORITY, "", rawUrl, truncate(rest));
        }
        rest = rest.substring(2);

        int authorityEnd = indexOfAny(rest, '/', '?', '#');
        String authority = authorityEnd < 0 ? rest : rest.substring(0, authorityEnd);
        String pathAndBeyond = authorityEnd < 0 ? "" : rest.substring(authorityEnd);

        if (authority.isEmpty()) {
            return PolicyResult.block(BlockReason.EMPTY_HOST, "", rawUrl, "empty authority");
        }
        if (authority.indexOf('@') >= 0) {
            // userinfo@host - includes the classic "https://github.com@evil.example/" trick.
            return PolicyResult.block(BlockReason.USERINFO_PRESENT, "", rawUrl, truncate(authority));
        }
        if (authority.indexOf('[') >= 0 || authority.indexOf(']') >= 0) {
            return PolicyResult.block(BlockReason.IP_LITERAL_HOST, "", rawUrl, truncate(authority));
        }

        String hostPart = authority;
        int lastColon = authority.lastIndexOf(':');
        if (lastColon >= 0) {
            String port = authority.substring(lastColon + 1);
            hostPart = authority.substring(0, lastColon);
            if (!"443".equals(port)) {
                // Everything that is not the default HTTPS port is refused, including ":80",
                // ":8443", empty ports and the multi-colon oddities that fall through to here.
                return PolicyResult.block(BlockReason.PORT_NOT_ALLOWED, "", rawUrl, truncate(authority));
            }
        }

        String canonicalHost = canonicalizeHost(hostPart);
        if (canonicalHost == null) {
            BlockReason reason = containsNonAscii(hostPart)
                    ? BlockReason.NON_ASCII_HOST
                    : BlockReason.INVALID_HOST;
            return PolicyResult.block(reason, "", rawUrl, truncate(hostPart));
        }

        // --- 4. Allow-list. -----------------------------------------------------------------------
        AllowEntry entry = config.findByHost(canonicalHost);
        if (entry == null) {
            return PolicyResult.block(BlockReason.HOST_NOT_ALLOWED, canonicalHost, rawUrl, "");
        }

        if (trafficClass == TrafficClass.DOCUMENT && entry.isResourceOnly()) {
            // The app's own page is GitHub.com or an explicitly approved sign-in page. A resource-only
            // host may be fetched and embedded, but the wrapper is never navigated to it.
            return PolicyResult.block(BlockReason.DOCUMENT_NOT_ALLOWED, canonicalHost, rawUrl, "");
        }

        if (checkPath) {
            String rawPath = extractRawPath(pathAndBeyond);
            if (!entry.matchesPath(rawPath)) {
                return PolicyResult.block(BlockReason.PATH_NOT_ALLOWED, canonicalHost, rawUrl, truncate(rawPath));
            }
        }

        return PolicyResult.allow(canonicalHost, rawUrl, entry);
    }

    /**
     * The question the egress proxy asks for every {@code CONNECT host:port} it is handed: may this app
     * open <em>any</em> TLS connection to that host?
     *
     * <p>This is a host-level question, deliberately weaker than the three URL questions: at the TCP
     * layer there is no path, no role and no traffic class - only a name and a port - and the URL hooks
     * keep enforcing paths and roles on top. It is also the only question that WebSockets and every other
     * transport the WebView hooks cannot see are asked, so it has the same host rules as a URL: exact
     * allow-listed names only, port 443 only, no IP literals, no look-alikes.</p>
     *
     * @param host the host as it appears in the CONNECT request (any case; must not contain a port)
     * @param port the requested port
     */
    public static PolicyResult evaluateTunnel(String host, int port, PolicyConfig config) {
        String raw = "connect://" + (host == null ? "" : host) + ":" + port;
        if (config == null) {
            return PolicyResult.block(BlockReason.INVALID_HOST, "", raw, "no policy configured");
        }
        if (host == null || host.isEmpty()) {
            return PolicyResult.block(BlockReason.EMPTY_HOST, "", raw, "empty host");
        }
        if (host.indexOf('[') >= 0 || host.indexOf(']') >= 0) {
            return PolicyResult.block(BlockReason.IP_LITERAL_HOST, "", raw, truncate(host));
        }
        if (host.indexOf('@') >= 0) {
            return PolicyResult.block(BlockReason.USERINFO_PRESENT, "", raw, truncate(host));
        }
        String canonicalHost = canonicalizeHost(host);
        if (canonicalHost == null) {
            BlockReason reason = containsNonAscii(host) ? BlockReason.NON_ASCII_HOST : BlockReason.INVALID_HOST;
            return PolicyResult.block(reason, "", raw, truncate(host));
        }
        if (port != 443) {
            return PolicyResult.block(BlockReason.PORT_NOT_ALLOWED, canonicalHost, raw, String.valueOf(port));
        }
        AllowEntry entry = config.findByHost(canonicalHost);
        if (entry == null) {
            return PolicyResult.block(BlockReason.HOST_NOT_ALLOWED, canonicalHost, raw, "");
        }
        return PolicyResult.allow(canonicalHost, raw, entry);
    }

    /**
     * Strict host canonicalisation: lower-casing is the only transformation applied.
     *
     * <p>A trailing dot is <em>not</em> stripped. {@code https://github.com./} resolves to the same server
     * as {@code https://github.com/}, but every browser treats it as a separate origin (own cookie jar, own
     * storage, and a CORS mismatch against the real site), so the page would be a logged-out, half-broken
     * copy at best. Nothing legitimate ever produces such a URL; refusing it keeps the policy's host
     * identical to the browser's and is the fail-closed choice.</p>
     *
     * @return the canonical host, or {@code null} if the input is not exactly a plain ASCII DNS name
     *         of at least two labels with an alphabetic top level domain and no trailing dot.
     */
    public static String canonicalizeHost(String host) {
        if (host == null || host.isEmpty()) {
            return null;
        }
        if (host.length() > MAX_HOST_LENGTH) {
            return null;
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            // Non-ASCII (IDN homographs), percent-escapes (%2e), underscores, colons, spaces, ...
            // are all refused: github.com is a plain ASCII name and never needs them.
            if (!isAllowedHostCharacter(c)) {
                return null;
            }
        }
        String canonical = host.toLowerCase(Locale.ROOT);
        if (canonical.endsWith(".") || canonical.startsWith(".") || canonical.startsWith("-")) {
            return null;
        }
        if (canonical.endsWith("-") || canonical.indexOf("..") >= 0 || canonical.indexOf('.') < 0) {
            return null;
        }
        String[] labels = canonical.split("\\.", -1);
        for (String label : labels) {
            if (label.isEmpty() || label.length() > MAX_LABEL_LENGTH) {
                return null;
            }
            if (label.charAt(0) == '-' || label.charAt(label.length() - 1) == '-') {
                return null;
            }
        }
        // The TLD must be alphabetic and at least two characters long. This is what rejects bare IP
        // literals ("1.2.3.4"), integer hosts ("2130706433") and mixed alphanumeric TLDs.
        String tld = labels[labels.length - 1];
        if (tld.length() < 2) {
            return null;
        }
        for (int i = 0; i < tld.length(); i++) {
            char c = tld.charAt(i);
            if (c < 'a' || c > 'z') {
                return null;
            }
        }
        return canonical;
    }

    /** Raw path (still percent-encoded), always starting with '/'; "" and "?..."/"#..." map to "/". */
    static String extractRawPath(String pathAndBeyond) {
        if (pathAndBeyond == null || pathAndBeyond.isEmpty()) {
            return "/";
        }
        if (pathAndBeyond.charAt(0) != '/') {
            return "/";
        }
        int end = indexOfAny(pathAndBeyond, '?', '#');
        return end < 0 ? pathAndBeyond : pathAndBeyond.substring(0, end);
    }

    // ---------------------------------------------------------------------------------------------
    // small helpers (pure)
    // ---------------------------------------------------------------------------------------------

    private static boolean isAllowedHostCharacter(char c) {
        if (c >= 'a' && c <= 'z') {
            return true;
        }
        if (c >= 'A' && c <= 'Z') {
            return true;
        }
        if (c >= '0' && c <= '9') {
            return true;
        }
        return c == '.' || c == '-';
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static boolean containsNonAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 127) {
                return true;
            }
        }
        return false;
    }

    private static boolean isValidScheme(String scheme) {
        if (scheme.isEmpty()) {
            return false;
        }
        char first = scheme.charAt(0);
        if (!((first >= 'a' && first <= 'z') || (first >= 'A' && first <= 'Z'))) {
            return false;
        }
        for (int i = 1; i < scheme.length(); i++) {
            char c = scheme.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '+'
                    || c == '.'
                    || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static String removeTabAndNewlines(String value) {
        if (value.indexOf('\t') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String stripLeadingAndTrailingC0AndSpace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isC0ControlOrSpace(value.charAt(start))) {
            start++;
        }
        while (end > start && isC0ControlOrSpace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isC0ControlOrSpace(char c) {
        return c <= 0x20;
    }

    private static boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return true;
            }
        }
        return false;
    }

    private static int indexOfAny(String value, char a, char b) {
        return indexOfAny(value, a, b, '\0');
    }

    private static int indexOfAny(String value, char a, char b, char c) {
        for (int i = 0; i < value.length(); i++) {
            char cur = value.charAt(i);
            if (cur == a || cur == b || (c != '\0' && cur == c)) {
                return i;
            }
        }
        return -1;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= MAX_DETAIL_LENGTH) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_DETAIL_LENGTH) + "...";
    }
}
