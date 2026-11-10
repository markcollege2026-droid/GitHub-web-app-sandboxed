package app.githubwebview.locked.policy;

/**
 * Tracks the last verified, approved top-level page and whether a download is in progress.
 *
 * <p>This is the navigation-state abstraction behind the watchdog. The watchdog used to ask only
 * "is the current URL an approved page?" and, on any other answer, reload the start page. That
 * conflates two different things: an actual unauthorized top-level navigation, and a download
 * whose redirect target is a resource-only storage host. A download never becomes the page, so
 * the WebView must stay on the page the user was viewing while the bytes are fetched by the app
 * itself.</p>
 *
 * <p>The tracker separates the two questions:</p>
 * <ul>
 *   <li>{@link #onVerifiedNavigation(String)} records a page only after a legitimate, approved
 *       main-frame navigation. A resource-only host can never be recorded, because the document
 *       question refuses it, and an inert bootstrap document such as {@code about:blank} has no
 *       entry and is not recorded either.</li>
 *   <li>{@link #checkWatchdog(String)} decides what the watchdog should do with the current URL.
 *       An approved page updates the verified page. A resource-allowed URL while a download is
 *       in progress is a download, not a navigation: it neither updates the verified page nor
 *       triggers a reset. Anything else is an unauthorized navigation and the caller must return
 *       to {@link #recoveryUrl()} - the last verified page, not the start page.</li>
 * </ul>
 *
 * <p>Download state is a count, not a flag, because a refused or busy download still reports a
 * terminal callback. Every start is matched by exactly one finish (saved, failed, cancelled or
 * refused), so the count returns to zero and a later unauthorized navigation is never masked by
 * a stale download.</p>
 *
 * <p>Immutable policy, mutable navigation state, no Android dependency: unit tested on a plain
 * JVM. All methods are synchronized because the watchdog and the download callbacks can arrive
 * from different threads in tests, even though the app posts them to the main thread.</p>
 */
public final class NavigationStateTracker {

    /** What the watchdog should do with the current URL. */
    public enum WatchdogDecision {
        /** Approved page; the verified page was updated. */
        OK,
        /** Inert bootstrap document (for example {@code about:blank}); nothing to do, nothing recorded. */
        OK_NO_UPDATE,
        /** Resource-allowed URL while a download is in progress; do not reset, do not record. */
        DOWNLOAD_IN_PROGRESS,
        /** Actual unauthorized top-level navigation; return to the last verified page. */
        UNAUTHORIZED,
        /** No current URL; nothing to do. */
        EMPTY
    }

    private final PolicyConfig policy;
    private String lastVerifiedUrl;
    private int activeDownloads;

    /**
     * @param policy the active policy (never null)
     * @param initialVerifiedUrl the first verified page, or null to start from the policy start page.
     *                           An initial value that is not an approved page is ignored and the
     *                           start page is used instead, so recovery always has a safe target.
     */
    public NavigationStateTracker(PolicyConfig policy, String initialVerifiedUrl) {
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        this.policy = policy;
        String initial = policy.primaryUrl();
        if (initialVerifiedUrl != null && !initialVerifiedUrl.isEmpty()) {
            PolicyResult result = policy.evaluateNavigation(initialVerifiedUrl);
            if (result.isAllowed() && result.matchedEntry() != null
                    && !result.matchedEntry().isResourceOnly()) {
                initial = initialVerifiedUrl;
            }
        }
        this.lastVerifiedUrl = initial;
    }

    /** The last verified, approved top-level page. Never null. */
    public synchronized String lastVerifiedUrl() {
        return lastVerifiedUrl;
    }

    /** Where to return after an unauthorized navigation: the last verified page. */
    public synchronized String recoveryUrl() {
        return lastVerifiedUrl == null ? policy.primaryUrl() : lastVerifiedUrl;
    }

    /**
     * Record a main-frame navigation that has been verified as approved.
     *
     * @return true when the URL became the new verified page
     */
    public synchronized boolean onVerifiedNavigation(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        PolicyResult result = policy.evaluateNavigation(url);
        if (!result.isAllowed()) {
            return false;
        }
        AllowEntry entry = result.matchedEntry();
        if (entry == null || entry.isResourceOnly()) {
            // about:blank and friends have no entry; a resource host is refused above, but the
            // role check is kept here so a future policy change cannot record a resource host
            // as a page through this path.
            return false;
        }
        lastVerifiedUrl = url;
        return true;
    }

    /** A download was requested. Call synchronously when the request is handed to the downloader. */
    public synchronized void onDownloadStarted() {
        activeDownloads++;
    }

    /** A download reached a terminal state: saved, failed, cancelled or refused. */
    public synchronized void onDownloadFinished() {
        if (activeDownloads > 0) {
            activeDownloads--;
        }
    }

    public synchronized boolean isDownloadInProgress() {
        return activeDownloads > 0;
    }

    public synchronized int activeDownloadCount() {
        return activeDownloads;
    }

    /**
     * The watchdog question for the current WebView URL.
     *
     * <p>An approved page updates the verified page and is {@link WatchdogDecision#OK}. A
     * resource-allowed URL during a download is {@link WatchdogDecision#DOWNLOAD_IN_PROGRESS}:
     * the caller must not reset the WebView and must not record the resource URL as a page.
     * Every other non-approved URL is {@link WatchdogDecision#UNAUTHORIZED}, including a
     * resource host when no download is in progress - that is an actual navigation to a host
     * that may never become the page.</p>
     */
    public synchronized WatchdogDecision checkWatchdog(String currentUrl) {
        if (currentUrl == null || currentUrl.isEmpty()) {
            return WatchdogDecision.EMPTY;
        }
        PolicyResult navigation = policy.evaluateNavigation(currentUrl);
        if (navigation.isAllowed()) {
            AllowEntry entry = navigation.matchedEntry();
            if (entry != null && !entry.isResourceOnly()) {
                lastVerifiedUrl = currentUrl;
                return WatchdogDecision.OK;
            }
            return WatchdogDecision.OK_NO_UPDATE;
        }
        if (isDownloadInProgress() && policy.evaluateRequest(currentUrl).isAllowed()) {
            return WatchdogDecision.DOWNLOAD_IN_PROGRESS;
        }
        return WatchdogDecision.UNAUTHORIZED;
    }

    /**
     * True when the URL is on a resource-only host: refused as a page, allowed as a request.
     * Used for diagnostics and for deciding whether a blocked main-frame URL during a download
     * is the download itself rather than an unauthorized navigation.
     */
    public boolean isResourceOnlyUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        PolicyResult navigation = policy.evaluateNavigation(url);
        if (navigation.isAllowed()) {
            return false;
        }
        if (navigation.reason() != BlockReason.DOCUMENT_NOT_ALLOWED) {
            return false;
        }
        return policy.evaluateRequest(url).isAllowed();
    }

    /**
     * True when a blocked URL should not trigger a recovery reset because it is the resource
     * target of a download that is in progress. An unauthorized host never qualifies, even
     * during a download.
     */
    public synchronized boolean shouldSuppressRecovery(String url) {
        return isDownloadInProgress() && isResourceOnlyUrl(url);
    }
}
