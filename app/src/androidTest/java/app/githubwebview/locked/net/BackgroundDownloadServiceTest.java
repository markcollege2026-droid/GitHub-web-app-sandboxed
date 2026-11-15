package app.githubwebview.locked.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;

import app.githubwebview.locked.MainActivity;
import app.githubwebview.locked.R;
import app.githubwebview.locked.policy.GitHubAllowlist;
import app.githubwebview.locked.web.EgressGuard;

/** Device coverage for download ownership independent of MainActivity. */
@RunWith(AndroidJUnit4.class)
public class BackgroundDownloadServiceTest {
    private static final long TIMEOUT_MS = TimeUnit.MINUTES.toMillis(2);
    private static final String SMALL_PUBLIC_FILE =
            "https://raw.githubusercontent.com/markcollege2026-droid/GitHub-web-app-sandboxed/main/README.md";
    private static final String LARGER_PUBLIC_ARCHIVE =
            "https://codeload.github.com/markcollege2026-droid/GitHub-web-app-sandboxed/zip/refs/heads/main";

    private Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private void grantNotifications() throws Exception {
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(
                    "pm grant " + context().getPackageName() + " android.permission.POST_NOTIFICATIONS").close();
        }
    }

    private boolean waitForGuard() {
        long deadline = SystemClock.uptimeMillis() + 60_000L;
        while (SystemClock.uptimeMillis() < deadline) {
            if (EgressGuard.isReady()) return true;
            SystemClock.sleep(200L);
        }
        return false;
    }

    private DownloadStateStore.Snapshot awaitTerminal() {
        long deadline = SystemClock.uptimeMillis() + TIMEOUT_MS;
        DownloadStateStore.Snapshot state;
        do {
            state = DownloadStateStore.read(context());
            if (state.state != DownloadStateStore.State.ACTIVE) return state;
            SystemClock.sleep(200L);
        } while (SystemClock.uptimeMillis() < deadline);
        return DownloadStateStore.read(context());
    }

    private DownloadStateStore.Snapshot awaitActive() {
        long deadline = SystemClock.uptimeMillis() + 30_000L;
        DownloadStateStore.Snapshot state;
        do {
            state = DownloadStateStore.read(context());
            if (state.state == DownloadStateStore.State.ACTIVE) return state;
            SystemClock.sleep(100L);
        } while (SystemClock.uptimeMillis() < deadline);
        return DownloadStateStore.read(context());
    }

    private void resetSnapshot() {
        DownloadStateStore.update(context(), DownloadStateStore.State.IDLE, "", 0L, -1L,
                "", "", "", null, null, null, -1L);
    }

    /**
     * A finished or simulated status must not outlive its test: the next Activity (in any test class)
     * would otherwise render it. Never touches the status of a transfer that is really running.
     */
    @After
    public void leaveNoStatusBehind() {
        if (!DownloadStateStore.read(context()).isActive() || !BackgroundDownloadService.isServiceAlive()) {
            resetSnapshot();
        }
    }

    private void awaitServiceStopped() {
        long deadline = SystemClock.uptimeMillis() + 10_000L;
        while (BackgroundDownloadService.isServiceAlive() && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100L);
        }
    }

    private boolean awaitStrip(ActivityScenario<MainActivity> scenario, int visibility) {
        long deadline = SystemClock.uptimeMillis() + 10_000L;
        int[] seen = new int[1];
        do {
            scenario.onActivity(a -> seen[0] = a.findViewById(R.id.download_strip).getVisibility());
            if (seen[0] == visibility) return true;
            SystemClock.sleep(100L);
        } while (SystemClock.uptimeMillis() < deadline);
        return false;
    }

    /**
     * True when the Activity's own window has input focus, i.e. no dialog of this app is in front of
     * the page. An emulator "isn't responding" dialog for another app is not the app's doing; it is
     * closed (system dialogs only - this cannot close an app dialog) and the check is repeated.
     */
    private boolean pageKeepsWindowFocus(ActivityScenario<MainActivity> scenario) throws Exception {
        boolean[] focus = new boolean[1];
        for (int attempt = 1; attempt <= 50; attempt++) {
            scenario.onActivity(a -> focus[0] = a.hasWindowFocus());
            if (focus[0]) return true;
            if (attempt % 10 == 0) {
                InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(
                        "am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS").close();
            }
            SystemClock.sleep(200L);
        }
        return false;
    }

    /**
     * The reported defect: while a file downloaded, a modal dialog owned the screen, so GitHub could
     * not be used, and dismissing the dialog cancelled the transfer. The status the service publishes
     * is written directly here, so the test does not depend on how fast a real file arrives.
     */
    @Test
    public void aRunningDownloadIsShownWithoutTakingThePageAway() throws Exception {
        awaitServiceStopped();
        resetSnapshot();
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            assertTrue("no strip while nothing is downloading", awaitStrip(activity, View.GONE));

            DownloadStateStore.update(context(), DownloadStateStore.State.ACTIVE, "repo.zip",
                    512L * 1024L, 2048L * 1024L, "", "", "", null, null, null, -1L);
            assertTrue("a running transfer must be visible in the app", awaitStrip(activity, View.VISIBLE));
            assertTrue("the page must keep input focus while a file downloads (no modal dialog)",
                    pageKeepsWindowFocus(activity));
            // Read on the main thread, assert on the test thread (a failed assertion on the main
            // thread would take the whole instrumentation down instead of failing this test).
            String[] seenText = new String[2];
            int[] seen = new int[3];
            activity.onActivity(a -> {
                View strip = a.findViewById(R.id.download_strip);
                View page = a.findViewById(R.id.github_webview);
                seenText[0] = ((TextView) a.findViewById(R.id.download_strip_text)).getText().toString();
                seenText[1] = ((TextView) a.findViewById(R.id.download_strip_primary)).getText().toString();
                seen[0] = page == null ? View.GONE : page.getVisibility();
                seen[1] = strip.getHeight();
                seen[2] = page == null ? 0 : page.getHeight();
            });
            assertTrue("names the file: " + seenText[0], seenText[0].contains("repo.zip"));
            assertTrue("shows progress: " + seenText[0], seenText[0].contains("25%"));
            assertEquals("the page stays on screen", View.VISIBLE, seen[0]);
            assertTrue("the strip is a slim bar, not a cover: " + seen[1] + " of " + seen[2],
                    seen[1] > 0 && seen[1] * 4 < seen[2]);
            assertEquals("Cancel", seenText[1]);
            // Putting the strip away is not Cancel.
            activity.onActivity(a -> a.findViewById(R.id.download_strip_secondary).performClick());
            assertTrue(awaitStrip(activity, View.GONE));
            assertEquals("hiding the strip must not cancel the transfer",
                    DownloadStateStore.State.ACTIVE, DownloadStateStore.read(context()).state);

            DownloadStateStore.update(context(), DownloadStateStore.State.ACTIVE, "repo.zip",
                    1024L * 1024L, 2048L * 1024L, "", "", "", null, null, null, -1L);
            SystemClock.sleep(600L);
            assertTrue("a hidden strip stays hidden for the rest of that transfer", awaitStrip(activity, View.GONE));
            assertTrue(pageKeepsWindowFocus(activity));
        } finally {
            resetSnapshot();
        }
    }

    /**
     * A failure used to be a modal dialog, and because "already shown" lived in an Activity field,
     * every newly created Activity showed the last failure again. It is now a strip with Retry that
     * does not block the page, and once dismissed it stays dismissed across Activity instances.
     */
    @Test
    public void aFailedDownloadOffersRetryWithoutADialogAndIsNotReplayedOnceDismissed() throws Exception {
        awaitServiceStopped();
        resetSnapshot();
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            DownloadStateStore.update(context(), DownloadStateStore.State.FAILED, "repo.zip", 128L, 1024L,
                    "The download failed.", "", "", SMALL_PUBLIC_FILE, null, "text/plain", -1L);
            assertTrue("the failure must be visible in the app", awaitStrip(activity, View.VISIBLE));
            assertTrue("a failure must not take the page away (no modal dialog)", pageKeepsWindowFocus(activity));
            String[] seenText = new String[2];
            int[] retryVisibility = new int[1];
            activity.onActivity(a -> {
                TextView retry = a.findViewById(R.id.download_strip_primary);
                retryVisibility[0] = retry.getVisibility();
                seenText[0] = retry.getText().toString();
                seenText[1] = ((TextView) a.findViewById(R.id.download_strip_text)).getText().toString();
            });
            assertEquals(View.VISIBLE, retryVisibility[0]);
            assertEquals("Retry", seenText[0]);
            assertTrue(seenText[1], seenText[1].contains("Download failed") && seenText[1].contains("repo.zip"));
        }
        // Not dismissed yet: a new Activity still offers Retry, again without a dialog.
        try (ActivityScenario<MainActivity> second = ActivityScenario.launch(MainActivity.class)) {
            assertTrue("an unanswered failure keeps Retry reachable", awaitStrip(second, View.VISIBLE));
            assertTrue(pageKeepsWindowFocus(second));
            second.onActivity(a -> a.findViewById(R.id.download_strip_secondary).performClick());
            assertTrue(awaitStrip(second, View.GONE));
        }
        assertTrue("the dismissal is persisted",
                DownloadStateStore.acknowledgedAt(context()) >= DownloadStateStore.read(context()).updatedAt);
        try (ActivityScenario<MainActivity> third = ActivityScenario.launch(MainActivity.class)) {
            SystemClock.sleep(1_500L);
            assertTrue("a dismissed failure must not be shown again by a later Activity",
                    awaitStrip(third, View.GONE));
            assertTrue(pageKeepsWindowFocus(third));
        } finally {
            resetSnapshot();
        }
    }

    @Test
    public void transferCompletesAfterActivityIsDestroyedAndStatusSurvivesRecreation() throws Exception {
        grantNotifications();
        ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class);
        try {
            Assume.assumeTrue("the existing egress guard did not become ready", waitForGuard());
            resetSnapshot();
            assertTrue("the foreground service request must be accepted",
                    BackgroundDownloadService.start(context(), SMALL_PUBLIC_FILE, null, "text/plain", -1L));
            DownloadStateStore.Snapshot started = awaitActive();
            assertEquals(DownloadStateStore.State.ACTIVE, started.state);
            assertTrue("the service, not MainActivity, must own the active worker",
                    BackgroundDownloadService.isServiceAlive());
        } finally {
            // This closes/destroys MainActivity. The foreground service is deliberately not stopped.
            activity.close();
        }

        DownloadStateStore.Snapshot completed = awaitTerminal();
        assertEquals("the transfer should continue after Activity destruction",
                DownloadStateStore.State.COMPLETE, completed.state);
        assertEquals("README.md", completed.fileName);
        assertTrue("persisted bytes should show progress", completed.bytesRead > 0L);

        try (ActivityScenario<MainActivity> recreated = ActivityScenario.launch(MainActivity.class)) {
            DownloadStateStore.Snapshot restored = DownloadStateStore.read(context());
            assertEquals(DownloadStateStore.State.COMPLETE, restored.state);
            assertEquals("README.md", restored.fileName);
            assertTrue(restored.bytesRead > 0L);
        }
    }

    @Test
    public void notificationCancelActionStopsTheTransfer() throws Exception {
        grantNotifications();
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            Assume.assumeTrue("the existing egress guard did not become ready", waitForGuard());
            resetSnapshot();
            assertTrue(BackgroundDownloadService.start(context(), LARGER_PUBLIC_ARCHIVE,
                    "attachment; filename=repo.zip", "application/zip", -1L));
            DownloadStateStore.Snapshot active = awaitActive();
            assertEquals(DownloadStateStore.State.ACTIVE, active.state);

            NotificationManager manager = (NotificationManager) context().getSystemService(Context.NOTIFICATION_SERVICE);
            assertNotNull(manager);
            Notification downloadNotification = null;
            for (StatusBarNotification activeNotification : manager.getActiveNotifications()) {
                Notification notification = activeNotification.getNotification();
                if (notification.actions != null) {
                    for (Notification.Action action : notification.actions) {
                        if ("Cancel".contentEquals(action.title)) downloadNotification = notification;
                    }
                }
            }
            assertNotNull("active notification with Cancel action must be visible", downloadNotification);
            boolean sent = false;
            for (Notification.Action action : downloadNotification.actions) {
                if ("Cancel".contentEquals(action.title)) {
                    action.actionIntent.send();
                    sent = true;
                    break;
                }
            }
            assertTrue("Cancel action must be sent", sent);
            assertEquals(DownloadStateStore.State.CANCELLED, awaitTerminal().state);
        }
    }

    @Test
    public void inAppCancellationSignalsTheService() throws Exception {
        grantNotifications();
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            Assume.assumeTrue("the existing egress guard did not become ready", waitForGuard());
            resetSnapshot();
            assertTrue(BackgroundDownloadService.start(context(), LARGER_PUBLIC_ARCHIVE,
                    "attachment; filename=repo.zip", "application/zip", -1L));
            assertEquals(DownloadStateStore.State.ACTIVE, awaitActive().state);
            activity.onActivity(MainActivity::cancelBackgroundDownload);
            assertEquals(DownloadStateStore.State.CANCELLED, awaitTerminal().state);
        }
    }

    @Test
    public void processLossIsReportedAsInterruptedAndRetryIsRevalidated() {
        long serviceDeadline = SystemClock.uptimeMillis() + 10_000L;
        while (BackgroundDownloadService.isServiceAlive() && SystemClock.uptimeMillis() < serviceDeadline) {
            SystemClock.sleep(100L);
        }
        assertFalse("terminal foreground service should stop before simulated recovery",
                BackgroundDownloadService.isServiceAlive());
        DownloadStateStore.update(context(), DownloadStateStore.State.ACTIVE, "interrupted.zip", 128L, 1024L,
                "", "", "", "https://raw.githubusercontent.com/o/r/main/file.zip", null,
                "application/zip", -1L);
        assertTrue(DownloadStateStore.recoverInterrupted(context()));
        DownloadStateStore.Snapshot interrupted = DownloadStateStore.read(context());
        assertEquals(DownloadStateStore.State.INTERRUPTED, interrupted.state);
        assertFalse("interrupted data is never a complete file", interrupted.state == DownloadStateStore.State.COMPLETE);
        DownloadStateStore.RetryRequest retry = DownloadStateStore.retryRequest(context());
        assertNotNull(retry);
        assertTrue("retry URL must pass the current policy again",
                DownloadPlan.plan(retry.url, retry.disposition, retry.mimeType,
                        GitHubAllowlist.productionConfig()).isAllowed());
    }
}
