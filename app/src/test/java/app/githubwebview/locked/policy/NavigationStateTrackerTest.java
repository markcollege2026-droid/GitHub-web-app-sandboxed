package app.githubwebview.locked.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import app.githubwebview.locked.policy.NavigationStateTracker.WatchdogDecision;

/**
 * The navigation-state abstraction behind the watchdog, tested on a plain JVM.
 *
 * <p>These are the regression tests for the unwanted homepage reset: a download that redirects to
 * a resource-only storage host must never move the WebView, never become the verified page, and
 * never send the user to the start page - while an actual unauthorized navigation must still be
 * blocked and must return to the page the user was viewing, not to the homepage.</p>
 */
public class NavigationStateTrackerTest {

    private static final PolicyConfig POLICY = GitHubAllowlist.productionConfig();
    private static final String WORKFLOW =
            "https://github.com/markcollege2026-droid/GitHub-web-app-sandboxed/actions/runs/123";
    private static final String ARTIFACTS = WORKFLOW + "/artifacts";
    private static final String STORAGE_30 =
            "https://productionresultssa30.blob.core.windows.net/actions-results/artifact.zip?sig=x";
    private static final String STORAGE_199 =
            "https://productionresultssa199.blob.core.windows.net/actions-results/artifact.zip?sig=x";
    private static final String EVIL = "https://evil.example/phish";

    private NavigationStateTracker tracker() {
        return new NavigationStateTracker(POLICY, POLICY.primaryUrl());
    }

    @Test
    public void startsOnTheStartPage() {
        NavigationStateTracker tracker = tracker();
        assertEquals(POLICY.primaryUrl(), tracker.lastVerifiedUrl());
        assertEquals(POLICY.primaryUrl(), tracker.recoveryUrl());
        assertFalse(tracker.isDownloadInProgress());
    }

    @Test
    public void anInvalidInitialPageFallsBackToTheStartPage() {
        NavigationStateTracker tracker = new NavigationStateTracker(POLICY, EVIL);
        assertEquals(POLICY.primaryUrl(), tracker.lastVerifiedUrl());
        NavigationStateTracker resource = new NavigationStateTracker(POLICY, STORAGE_30);
        assertEquals(POLICY.primaryUrl(), resource.lastVerifiedUrl());
    }

    @Test
    public void aVerifiedWorkflowNavigationBecomesTheVerifiedPage() {
        NavigationStateTracker tracker = tracker();
        assertTrue(tracker.onVerifiedNavigation(WORKFLOW));
        assertEquals(WORKFLOW, tracker.lastVerifiedUrl());
        assertTrue(tracker.onVerifiedNavigation(ARTIFACTS));
        assertEquals(ARTIFACTS, tracker.lastVerifiedUrl());
        assertEquals(ARTIFACTS, tracker.recoveryUrl());
    }

    @Test
    public void aResourceOnlyHostCanNeverBecomeTheVerifiedPage() {
        NavigationStateTracker tracker = tracker();
        assertTrue(tracker.onVerifiedNavigation(WORKFLOW));
        assertFalse(tracker.onVerifiedNavigation(STORAGE_30));
        assertFalse(tracker.onVerifiedNavigation(STORAGE_199));
        assertEquals(WORKFLOW, tracker.lastVerifiedUrl());
        // about:blank is an inert bootstrap document with no entry; it is not a page either.
        assertFalse(tracker.onVerifiedNavigation("about:blank"));
        assertEquals(WORKFLOW, tracker.lastVerifiedUrl());
    }

    @Test
    public void anUnauthorizedHostCanNeverBecomeTheVerifiedPage() {
        NavigationStateTracker tracker = tracker();
        assertTrue(tracker.onVerifiedNavigation(WORKFLOW));
        assertFalse(tracker.onVerifiedNavigation(EVIL));
        assertFalse(tracker.onVerifiedNavigation("https://docs.github.com/help"));
        assertEquals(WORKFLOW, tracker.lastVerifiedUrl());
    }

    @Test
    public void watchdogAcceptsAnApprovedPageAndRecordsIt() {
        NavigationStateTracker tracker = tracker();
        assertEquals(WatchdogDecision.OK, tracker.checkWatchdog(WORKFLOW));
        assertEquals(WORKFLOW, tracker.lastVerifiedUrl());
        assertEquals(WatchdogDecision.EMPTY, tracker.checkWatchdog(null));
        assertEquals(WatchdogDecision.EMPTY, tracker.checkWatchdog(""));
        assertEquals(WatchdogDecision.OK_NO_UPDATE, tracker.checkWatchdog("about:blank"));
        assertEquals(WORKFLOW, tracker.lastVerifiedUrl());
    }

    @Test
    public void downloadBeginsOnAWorkflowPageRedirectsToStorageAndWatchdogDoesNotReset() {
        NavigationStateTracker tracker = tracker();
        assertTrue(tracker.onVerifiedNavigation(WORKFLOW));
        assertTrue(tracker.onVerifiedNavigation(ARTIFACTS));

        // The user taps the artifact download. The request is recorded synchronously.
        tracker.onDownloadStarted();
        assertTrue(tracker.isDownloadInProgress());

        // The redirect target is a resource-only storage host. The watchdog must not reset,
        // must not record the storage URL as a page, and must keep the workflow page.
        assertEquals(WatchdogDecision.DOWNLOAD_IN_PROGRESS, tracker.checkWatchdog(STORAGE_30));
        assertEquals(ARTIFACTS, tracker.lastVerifiedUrl());
        assertEquals(ARTIFACTS, tracker.recoveryUrl());
        assertTrue(tracker.shouldSuppressRecovery(STORAGE_30));

        // A second tick during the same download is the same answer, not a reset.
        assertEquals(WatchdogDecision.DOWNLOAD_IN_PROGRESS, tracker.checkWatchdog(STORAGE_199));
        assertEquals(ARTIFACTS, tracker.lastVerifiedUrl());

        // The download succeeds. The original approved page remains open.
        tracker.onDownloadFinished();
        assertFalse(tracker.isDownloadInProgress());
        assertEquals(WatchdogDecision.OK, tracker.checkWatchdog(ARTIFACTS));
        assertEquals(ARTIFACTS, tracker.lastVerifiedUrl());
    }

    @Test
    public void aFailedDownloadDoesNotTriggerAHomepageReload() {
        NavigationStateTracker tracker = tracker();
        assertTrue(tracker.onVerifiedNavigation(WORKFLOW));
        tracker.onDownloadStarted();
        assertEquals(WatchdogDecision.DOWNLOAD_IN_PROGRESS, tracker.checkWatchdog(STORAGE_30));
        tracker.onDownloadFinished();
        // After the failure the WebView is still on the workflow page, which is approved.
        assertEquals(WatchdogDecision.OK, tracker.checkWatchdog(WORKFLOW));
        assertEquals(WORKFLOW, tracker.lastVerifiedUrl());
        assertEquals(WORKFLOW, tracker.recoveryUrl());
    }

    @Test
    public void aCancelledDownloadDoesNotTriggerAHomepageReload() {
        NavigationStateTracker tracker = tracker();
        assertTrue(tracker.onVerifiedNavigation(WORKFLOW));
        tracker.onDownloadStarted();
        assertEquals(WatchdogDecision.DOWNLOAD_IN_PROGRESS, tracker.checkWatchdog(STORAGE_199));
        tracker.onDownloadFinished();
        assertEquals(WatchdogDecision.OK, tracker.checkWatchdog(WORKFLOW));
        assertEquals(WORKFLOW, tracker.lastVerifiedUrl());
    }

    @Test
    public void aRefusedDownloadDoesNotTriggerAHomepageReload() {
        NavigationStateTracker tracker = tracker();
        assertTrue(tracker.onVerifiedNavigation(WORKFLOW));
        tracker.onDownloadStarted();
        tracker.onDownloadFinished();
        assertEquals(WatchdogDecision.OK, tracker.checkWatchdog(WORKFLOW));
        assertEquals(WORKFLOW, tracker.lastVerifiedUrl());
    }

    @Test
    public void actualUnauthorizedNavigationIsStillBlockedDuringADownload() {
        NavigationStateTracker tracker = tracker();
        assertTrue(tracker.onVerifiedNavigation(WORKFLOW));
        tracker.onDownloadStarted();
        // An unauthorized host is not a resource host, so the download does not mask it.
        assertEquals(WatchdogDecision.UNAUTHORIZED, tracker.checkWatchdog(EVIL));
        assertFalse(tracker.shouldSuppressRecovery(EVIL));
        assertEquals(WORKFLOW, tracker.recoveryUrl());
        tracker.onDownloadFinished();
    }

    @Test
    public void actualUnauthorizedNavigationReturnsToTheLastVerifiedPageNotTheHomepage() {
        NavigationStateTracker tracker = tracker();
        assertTrue(tracker.onVerifiedNavigation(WORKFLOW));
        assertTrue(tracker.onVerifiedNavigation(ARTIFACTS));
        assertEquals(WatchdogDecision.UNAUTHORIZED, tracker.checkWatchdog(EVIL));
        assertEquals(ARTIFACTS, tracker.recoveryUrl());
        assertFalse(POLICY.primaryUrl().equals(tracker.recoveryUrl()));
    }

    @Test
    public void aResourceHostWithoutADownloadIsAnUnauthorizedNavigation() {
        NavigationStateTracker tracker = tracker();
        assertTrue(tracker.onVerifiedNavigation(WORKFLOW));
        // No download in progress: navigating to a resource host as a page is unauthorized.
        assertEquals(WatchdogDecision.UNAUTHORIZED, tracker.checkWatchdog(STORAGE_30));
        assertEquals(WORKFLOW, tracker.recoveryUrl());
        assertFalse(tracker.shouldSuppressRecovery(STORAGE_30));
    }

    @Test
    public void downloadStateIsCountedSoOneFinishDoesNotMaskAnotherDownload() {
        NavigationStateTracker tracker = tracker();
        tracker.onDownloadStarted();
        tracker.onDownloadStarted();
        assertEquals(2, tracker.activeDownloadCount());
        tracker.onDownloadFinished();
        assertTrue(tracker.isDownloadInProgress());
        tracker.onDownloadFinished();
        assertFalse(tracker.isDownloadInProgress());
        // An extra finish never drives the count negative.
        tracker.onDownloadFinished();
        assertFalse(tracker.isDownloadInProgress());
        assertEquals(0, tracker.activeDownloadCount());
    }

    @Test
    public void signInPagesAreVerifiedPagesButResourceHostsAreNot() {
        NavigationStateTracker tracker = tracker();
        assertTrue(tracker.onVerifiedNavigation("https://accounts.google.com/signin/v2/identifier"));
        assertEquals("https://accounts.google.com/signin/v2/identifier", tracker.lastVerifiedUrl());
        assertFalse(tracker.onVerifiedNavigation(STORAGE_30));
        assertEquals("https://accounts.google.com/signin/v2/identifier", tracker.lastVerifiedUrl());
    }

    @Test
    public void isResourceOnlyUrlDistinguishesStorageHostsFromUnauthorizedHosts() {
        NavigationStateTracker tracker = tracker();
        assertTrue(tracker.isResourceOnlyUrl(STORAGE_30));
        assertTrue(tracker.isResourceOnlyUrl(STORAGE_199));
        assertFalse(tracker.isResourceOnlyUrl(WORKFLOW));
        assertFalse(tracker.isResourceOnlyUrl(EVIL));
        assertFalse(tracker.isResourceOnlyUrl(null));
        assertFalse(tracker.isResourceOnlyUrl(""));
    }
}
