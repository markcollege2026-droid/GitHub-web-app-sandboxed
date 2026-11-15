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

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;

import app.githubwebview.locked.MainActivity;
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
