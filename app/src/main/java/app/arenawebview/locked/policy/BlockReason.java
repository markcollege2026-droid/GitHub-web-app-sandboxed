package app.arenawebview.locked.policy;

/**
 * Machine-readable reason why a URL was refused.
 *
 * <p>Every value in this enum represents a <em>fail-closed</em> decision: when in doubt the
 * wrapper refuses the URL instead of loading it. The reason is surfaced in the in-app "blocked"
 * overlay and in the local diagnostics report so that behaviour can be audited from a device.</p>
 *
 * <p>This class is intentionally free of Android dependencies so that it can be exercised by plain
 * JVM unit tests (see {@code app/src/test/.../UrlPolicyTest.java}).</p>
 */
public enum BlockReason {

    /** The URL handed to the policy was null. */
    NULL_URL("the URL was null", Category.PARSE),

    /** The URL was empty or only whitespace. */
    EMPTY_URL("the URL was empty", Category.PARSE),

    /** The URL contained a C0/DEL control character that could change how a parser sees it. */
    CONTROL_CHARACTER("the URL contained control characters", Category.PARSE),

    /**
     * The URL contained a backslash. HTTPS URLs are "special" URLs in the WHATWG URL standard and
     * treat {@code \} exactly like {@code /}, which is a classic way to make two parsers disagree
     * about where the host ends (e.g. {@code https://evil.example\@github.com}).
     */
    BACKSLASH_AMBIGUITY("the URL contained a backslash that could change its meaning", Category.PARSE),

    /** No {@code scheme:} prefix at all (e.g. {@code //github.com/} or {@code github.com}). */
    MISSING_SCHEME("the URL had no scheme", Category.PARSE),

    /** A scheme that is not even syntactically valid (e.g. {@code 1http://}). */
    INVALID_SCHEME("the URL scheme was not valid", Category.PARSE),

    /** Plain {@code http:} - cleartext is never allowed. */
    SCHEME_NOT_HTTPS("only HTTPS URLs are allowed", Category.SCHEME),

    /** <em>Any</em> other scheme: javascript:, intent:, file:, content:, data: (navigation), market:, mailto:, tel:, ... */
    UNSUPPORTED_SCHEME("that URL scheme is not allowed", Category.SCHEME),

    /** {@code https:} without the {@code //} authority introducer. */
    MALFORMED_AUTHORITY("the URL had no host", Category.PARSE),

    /** Authority present but empty host (e.g. {@code https:///path}). */
    EMPTY_HOST("the URL had no host", Category.PARSE),

    /** {@code https://user:pass@host/} - userinfo is an ambiguity and is never needed. */
    USERINFO_PRESENT("the URL contained a username/password part", Category.PARSE),

    /** IPv6 literal or bracketed host, e.g. {@code https://[::1]/}. */
    IP_LITERAL_HOST("the URL used an IP literal host", Category.HOST),

    /** Host failed strict host syntax validation (bad labels, bad characters, too long, ...). */
    INVALID_HOST("the host was malformed", Category.HOST),

    /** Host contained non-ASCII characters (IDN homograph / look-alike attempt). */
    NON_ASCII_HOST("the host contained non-ASCII characters (look-alike domain)", Category.HOST),

    /** Explicit port that is not 443. */
    PORT_NOT_ALLOWED("only the default HTTPS port is allowed", Category.HOST),

    /**
     * The host is well formed but is not on the allow-list. This is the reason that fires for every
     * third-party domain, every look-alike domain (github-com.example, github.com.evil.com, xn--..., ...)
     * and every github.com sub-domain that was not explicitly approved.
     */
    HOST_NOT_ALLOWED("that domain is not on the allow-list", Category.HOST),

    /** Host is on the allow-list but the entry is restricted to specific paths and this path is not one. */
    PATH_NOT_ALLOWED("that path is not allowed for this domain", Category.PATH),

    /**
     * The host is an allow-listed <em>resource-only</em> entry (one of the opt-in third-party widget
     * hosts). Such a host may serve a script or an asset, but it may never become the visible page.
     */
    DOCUMENT_NOT_ALLOWED("that domain may serve page resources but must never become the page", Category.PATH),

    /** A capability (file chooser / media capture / geolocation / download) was refused. */
    CAPABILITY_DISABLED("that browser capability is disabled in this wrapper", Category.CAPABILITY),

    /** An attempt to hand control to another Android app (startActivity) was refused. */
    EXTERNAL_INTENT_BLOCKED("opening another app is not allowed", Category.ESCAPE);

    /** Coarse grouping used for reporting. */
    public enum Category {
        PARSE,
        SCHEME,
        HOST,
        PATH,
        CAPABILITY,
        ESCAPE
    }

    private final String humanMessage;
    private final Category category;

    BlockReason(String humanMessage, Category category) {
        this.humanMessage = humanMessage;
        this.category = category;
    }

    /** Short, user-facing explanation. */
    public String humanMessage() {
        return humanMessage;
    }

    /** Coarse category of the refusal. */
    public Category category() {
        return category;
    }
}
