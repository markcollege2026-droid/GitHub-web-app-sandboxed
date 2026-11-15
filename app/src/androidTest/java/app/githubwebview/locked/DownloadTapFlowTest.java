package app.githubwebview.locked;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inspector.WindowInspector;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.githubwebview.locked.net.DownloadStateStore;
import app.githubwebview.locked.web.EgressGuard;

/**
 * The download flow exactly as a person drives it: tap a download link in the page, get the
 * "Save this file?" offer, tap Save once, keep the page.
 *
 * <p>Every other download test starts the transfer through the service or the downloader directly.
 * None of them taps a link, so none of them sees what the screen does between the tap and the saved
 * file. This one records every window this app shows (the page, each dialog) and when each appears,
 * disappears, gains or loses focus, and counts how many taps on Save it takes.</p>
 */
@RunWith(AndroidJUnit4.class)
public class DownloadTapFlowTest {

    private static final String TAG = "GitHubWebViewLocked";
    private static final String REPO = "/markcollege2026-droid/GitHub-web-app-sandboxed";

    private static Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    /** Samples, on the main thread, every window of this process and the download surfaces. */
    private static final class Timeline implements Runnable {
        final Handler main = new Handler(Looper.getMainLooper());
        final List<String> entries = Collections.synchronizedList(new ArrayList<String>());
        final Set<Integer> dialogInstances = Collections.synchronizedSet(new LinkedHashSet<Integer>());
        final MainActivity activity;
        final long start = SystemClock.uptimeMillis();
        final int alertTitleId = android.content.res.Resources.getSystem().getIdentifier("alertTitle", "id", "android");
        volatile boolean running = true;
        volatile int dialogsNow;
        volatile int maxDialogs;
        volatile int stripShownCount;
        volatile int overlayShownCount;
        private String last = "";
        private boolean stripWasVisible;
        private boolean overlayWasVisible;

        Timeline(MainActivity activity) {
            this.activity = activity;
        }

        void begin() {
            main.post(this);
        }

        void end() {
            running = false;
        }

        long now() {
            return SystemClock.uptimeMillis() - start;
        }

        void note(String what) {
            entries.add(now() + "ms " + what);
        }

        @Override
        public void run() {
            if (!running) return;
            String now;
            try {
                now = sample();
            } catch (Throwable t) {
                now = "sample failed: " + t;
            }
            if (!now.equals(last)) {
                last = now;
                entries.add(now() + "ms " + now);
            }
            main.postDelayed(this, 12L);
        }

        private String sample() {
            StringBuilder out = new StringBuilder();
            int dialogs = 0;
            for (View root : WindowInspector.getGlobalWindowViews()) {
                ViewGroup.LayoutParams raw = root.getLayoutParams();
                int type = raw instanceof WindowManager.LayoutParams ? ((WindowManager.LayoutParams) raw).type : -1;
                String focus = root.hasWindowFocus() ? "*" : "";
                String shown = root.getVisibility() == View.VISIBLE ? "" : "(hidden)";
                if (type == WindowManager.LayoutParams.TYPE_BASE_APPLICATION) {
                    out.append("[page").append(focus).append(shown).append("] ");
                } else {
                    dialogs++;
                    dialogInstances.add(System.identityHashCode(root));
                    TextView title = alertTitleId == 0 ? null : (TextView) root.findViewById(alertTitleId);
                    out.append("[dialog#").append(Integer.toHexString(System.identityHashCode(root) & 0xfff))
                            .append(" '").append(title == null ? "type " + type : title.getText()).append("'")
                            .append(focus).append(shown).append("] ");
                }
            }
            dialogsNow = dialogs;
            if (dialogs > maxDialogs) maxDialogs = dialogs;

            View strip = activity.findViewById(R.id.download_strip);
            boolean stripVisible = strip != null && strip.getVisibility() == View.VISIBLE;
            if (stripVisible && !stripWasVisible) stripShownCount++;
            stripWasVisible = stripVisible;
            if (stripVisible) {
                TextView text = activity.findViewById(R.id.download_strip_text);
                String first = text == null ? "" : text.getText().toString();
                out.append("strip=").append(first.startsWith("Downloading") ? "progress" : "failure").append(' ');
            }
            View overlay = activity.findViewById(R.id.status_overlay);
            boolean overlayVisible = overlay != null && overlay.getVisibility() == View.VISIBLE;
            if (overlayVisible && !overlayWasVisible) overlayShownCount++;
            overlayWasVisible = overlayVisible;
            if (overlayVisible) out.append("overlay ");
            out.append("state=").append(DownloadStateStore.read(activity).state);
            return out.toString();
        }
    }

    private static boolean awaitGuard() {
        long deadline = SystemClock.uptimeMillis() + 60_000L;
        while (SystemClock.uptimeMillis() < deadline) {
            if (EgressGuard.isReady()) return true;
            SystemClock.sleep(200L);
        }
        return false;
    }

    /** Screen centre of a visible button of the dialog in front, or null when no dialog has it. */
    private static float[] dialogButton(final int buttonId) {
        final float[][] found = new float[1][];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (View root : WindowInspector.getGlobalWindowViews()) {
                ViewGroup.LayoutParams raw = root.getLayoutParams();
                int type = raw instanceof WindowManager.LayoutParams ? ((WindowManager.LayoutParams) raw).type : -1;
                if (type == WindowManager.LayoutParams.TYPE_BASE_APPLICATION) continue;
                View button = root.findViewById(buttonId);
                if (button != null && button.isShown() && button.getWidth() > 0) {
                    int[] at = new int[2];
                    button.getLocationOnScreen(at);
                    found[0] = new float[]{at[0] + button.getWidth() / 2f, at[1] + button.getHeight() / 2f};
                }
            }
        });
        return found[0];
    }

    /** One real touch through the system input pipeline, the same way a finger arrives. */
    private static void touch(float x, float y) {
        long down = SystemClock.uptimeMillis();
        MotionEvent press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        press.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent(press, true);
        press.recycle();
        SystemClock.sleep(80L);
        MotionEvent release = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0);
        release.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent(release, true);
        release.recycle();
    }

    private static boolean await(long timeoutMs, java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        do {
            if (condition.call()) return true;
            SystemClock.sleep(25L);
        } while (SystemClock.uptimeMillis() < deadline);
        return condition.call();
    }

    @Test
    public void oneTapOnALinkShowsOneOfferAndOneTapOnSaveStartsTheDownload() throws Exception {
        Assume.assumeTrue("window inspection needs Android 10+", Build.VERSION.SDK_INT >= 29);
        if (Build.VERSION.SDK_INT >= 33) {
            LivePageSupport.shell("pm grant " + context().getPackageName() + " android.permission.POST_NOTIFICATIONS");
        }
        // {name, link the page offers, how long a person takes before pressing Save}
        String[][] cases = {
                {"zip", REPO + "/archive/refs/heads/main.zip", "900"},
                {"raw", REPO + "/raw/main/README.md", "250"},
        };
        List<String> problems = new ArrayList<String>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            Assume.assumeTrue("the egress guard did not become ready", awaitGuard());
            await(15_000L, () -> DownloadStateStore.read(context()).state != DownloadStateStore.State.ACTIVE);
            LivePageSupport.load(scenario, "https://github.com" + REPO);
            Assume.assumeTrue("the repository page did not render", LivePageSupport.waitFor(scenario,
                    "location.hostname==='github.com' && location.pathname===" + JSONObject.quote(REPO)
                            + " && document.readyState==='complete' && !!document.body", 45_000));
            SystemClock.sleep(1_500L);

            for (String[] each : cases) {
                final String name = each[0];
                final MainActivity[] activity = new MainActivity[1];
                scenario.onActivity(a -> activity[0] = a);
                final Timeline timeline = new Timeline(activity[0]);
                timeline.begin();
                long statusBefore = DownloadStateStore.read(context()).updatedAt;

                LivePageSupport.evaluate(scenario, "(function(){var old=document.getElementById('dl-probe');"
                        + "if(old)old.remove();var a=document.createElement('a');a.id='dl-probe';"
                        + "a.href=" + JSONObject.quote(each[1]) + ";a.textContent='Download probe';"
                        + "a.style.position='fixed';a.style.top='180px';a.style.left='24px';"
                        + "a.style.zIndex='2147483647';a.style.background='#fff';a.style.color='#00f';"
                        + "a.style.padding='24px';a.style.fontSize='20px';document.body.appendChild(a);return true;})()");
                timeline.note("TAP link " + each[1]);
                LivePageSupport.tap(scenario, "#dl-probe");

                boolean offered = await(25_000L, () -> timeline.dialogsNow > 0);
                timeline.note(offered ? "offer is on screen" : "NO offer appeared");
                int taps = 0;
                if (offered) {
                    SystemClock.sleep(Long.parseLong(each[2]));
                    while (timeline.dialogsNow > 0 && taps < 5) {
                        float[] save = dialogButton(android.R.id.button1);
                        if (save == null) {
                            timeline.note("a dialog is up but has no positive button");
                            SystemClock.sleep(300L);
                            taps++;
                            continue;
                        }
                        taps++;
                        timeline.note("TAP Save #" + taps);
                        touch(save[0], save[1]);
                        await(1_500L, () -> timeline.dialogsNow == 0);
                    }
                }
                boolean finished = await(120_000L, () -> {
                    DownloadStateStore.Snapshot s = DownloadStateStore.read(context());
                    return s.updatedAt > statusBefore && s.state != DownloadStateStore.State.ACTIVE;
                });
                SystemClock.sleep(3_000L);
                String probeStillThere = LivePageSupport.evaluate(scenario, "!!document.getElementById('dl-probe')");
                String url = LivePageSupport.url(scenario);
                DownloadStateStore.Snapshot end = DownloadStateStore.read(context());
                boolean pageFocused = LivePageSupport.appWindowHasFocus(scenario);
                timeline.end();

                String summary = name + ": offers shown=" + timeline.dialogInstances.size()
                        + " at once=" + timeline.maxDialogs + " taps on Save=" + taps
                        + " strip shown=" + timeline.stripShownCount + "x overlay shown=" + timeline.overlayShownCount
                        + "x finished=" + finished + " state=" + end.state + " file=" + end.fileName
                        + " page kept=" + probeStillThere + " page focused=" + pageFocused
                        + " url=" + (url == null ? "" : url.replaceAll("\\?.*", ""));
                Log.i(TAG, "dialog probe SUMMARY " + summary);
                synchronized (timeline.entries) {
                    for (String entry : timeline.entries) Log.i(TAG, "dialog probe " + name + " " + entry);
                }

                if (!offered) problems.add(name + ": no save offer appeared");
                if (timeline.dialogInstances.size() != 1) problems.add(name + ": " + timeline.dialogInstances.size() + " dialogs were created for one tap");
                if (timeline.maxDialogs > 1) problems.add(name + ": " + timeline.maxDialogs + " dialogs were stacked");
                if (taps != 1) problems.add(name + ": Save needed " + taps + " taps");
                if (timeline.stripShownCount > 1) problems.add(name + ": the progress strip appeared " + timeline.stripShownCount + " times");
                if (timeline.overlayShownCount > 0) problems.add(name + ": the blocked overlay appeared");
                if (!finished || end.state != DownloadStateStore.State.COMPLETE) problems.add(name + ": ended as " + end.state);
                if (!"true".equals(probeStillThere)) problems.add(name + ": the page was reloaded");
                if (!pageFocused) problems.add(name + ": the page did not get the screen back");
                StringBuilder trace = new StringBuilder();
                synchronized (timeline.entries) {
                    for (String entry : timeline.entries) trace.append('\n').append(entry);
                }
                if (!problems.isEmpty() && problems.size() < 40) problems.add(name + " timeline:" + trace);
            }
        }
        assertEquals("the tap flow misbehaved", "", String.join("\n", problems));
        assertTrue(true);
    }
}
