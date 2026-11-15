package app.githubwebview.locked;

import static org.junit.Assert.assertEquals;

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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

import app.githubwebview.locked.net.DownloadStateStore;
import app.githubwebview.locked.web.EgressGuard;

/**
 * The download flow exactly as a person drives it: tap a download link in the page, get the
 * "Save this file?" offer, tap Save once, keep the page.
 *
 * <p>Every other download test starts the transfer through the service or the downloader directly.
 * None of them taps a link, so none of them sees what the screen does between the tap and the saved
 * file. This one records every window this app shows (the page, each dialog) and when each appears,
 * disappears, gains or loses focus, counts how many taps on Save it takes, and checks whether the
 * page behind was reloaded.</p>
 */
@RunWith(AndroidJUnit4.class)
public class DownloadTapFlowTest {

    private static final String TAG = "GitHubWebViewLocked";
    private static final String REPO = "/markcollege2026-droid/GitHub-web-app-sandboxed";
    private static final String OFFER = "Save this file?";

    private static Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private static String clock() {
        return new SimpleDateFormat("mm:ss.SSS", Locale.ROOT).format(new Date());
    }

    /** Samples, on the main thread, every window of this process and the download surfaces. */
    private static final class Timeline implements Runnable {
        final Handler main = new Handler(Looper.getMainLooper());
        final List<String> entries = Collections.synchronizedList(new ArrayList<String>());
        /** Every dialog window ever seen: identity -> title. */
        final Map<Integer, String> dialogs = Collections.synchronizedMap(new LinkedHashMap<Integer, String>());
        final MainActivity activity;
        final int alertTitleId = android.content.res.Resources.getSystem().getIdentifier("alertTitle", "id", "android");
        final int stripId;
        final int stripTextId;
        final int overlayId;
        volatile boolean running = true;
        volatile int offersNow;
        volatile int dialogsNow;
        volatile int maxDialogs;
        volatile int stripShownCount;
        volatile int overlayShownCount;
        private String last = "";
        private boolean stripWasVisible;
        private boolean overlayWasVisible;

        Timeline(MainActivity activity) {
            this.activity = activity;
            String pkg = activity.getPackageName();
            stripId = activity.getResources().getIdentifier("download_strip", "id", pkg);
            stripTextId = activity.getResources().getIdentifier("download_strip_text", "id", pkg);
            overlayId = activity.getResources().getIdentifier("status_overlay", "id", pkg);
        }

        void begin() {
            main.post(this);
        }

        void end() {
            running = false;
        }

        void note(String what) {
            entries.add(clock() + " " + what);
        }

        int offersCreated() {
            int count = 0;
            synchronized (dialogs) {
                for (String title : dialogs.values()) if (OFFER.equals(title)) count++;
            }
            return count;
        }

        String dialogTitles() {
            synchronized (dialogs) {
                return dialogs.values().toString();
            }
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
                entries.add(clock() + " " + now);
            }
            main.postDelayed(this, 12L);
        }

        private String sample() {
            StringBuilder out = new StringBuilder();
            int count = 0;
            int offers = 0;
            for (View root : WindowInspector.getGlobalWindowViews()) {
                ViewGroup.LayoutParams raw = root.getLayoutParams();
                int type = raw instanceof WindowManager.LayoutParams ? ((WindowManager.LayoutParams) raw).type : -1;
                String focus = root.hasWindowFocus() ? "*" : "";
                if (type == WindowManager.LayoutParams.TYPE_BASE_APPLICATION) {
                    out.append("[page").append(focus).append("] ");
                } else {
                    count++;
                    TextView titleView = alertTitleId == 0 ? null : (TextView) root.findViewById(alertTitleId);
                    String title = titleView == null ? "window type " + type : String.valueOf(titleView.getText());
                    dialogs.put(System.identityHashCode(root), title);
                    if (OFFER.equals(title)) offers++;
                    out.append("[dialog '").append(title).append("'").append(focus).append("] ");
                }
            }
            dialogsNow = count;
            offersNow = offers;
            if (count > maxDialogs) maxDialogs = count;

            View strip = stripId == 0 ? null : activity.findViewById(stripId);
            boolean stripVisible = strip != null && strip.getVisibility() == View.VISIBLE;
            if (stripVisible && !stripWasVisible) stripShownCount++;
            stripWasVisible = stripVisible;
            if (stripVisible) {
                TextView text = activity.findViewById(stripTextId);
                String first = text == null ? "" : text.getText().toString();
                out.append("strip=").append(first.startsWith("Downloading") ? "progress" : "failure").append(' ');
            }
            View overlay = overlayId == 0 ? null : activity.findViewById(overlayId);
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

    /** Screen centre of a visible button of the save offer, or null when it is not on screen. */
    private static float[] offerButton(final int buttonId) {
        final float[][] found = new float[1][];
        final int alertTitleId = android.content.res.Resources.getSystem().getIdentifier("alertTitle", "id", "android");
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (View root : WindowInspector.getGlobalWindowViews()) {
                TextView title = alertTitleId == 0 ? null : (TextView) root.findViewById(alertTitleId);
                if (title == null || !OFFER.contentEquals(title.getText())) continue;
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

    private static boolean await(long timeoutMs, Callable<Boolean> condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        do {
            if (condition.call()) return true;
            SystemClock.sleep(20L);
        } while (SystemClock.uptimeMillis() < deadline);
        return condition.call();
    }

    private static void placeLink(ActivityScenario<MainActivity> scenario, String href) throws Exception {
        LivePageSupport.evaluate(scenario, "(function(){var old=document.getElementById('dl-probe');"
                + "if(old)old.remove();var a=document.createElement('a');a.id='dl-probe';"
                + "a.href=" + JSONObject.quote(href) + ";a.textContent='Download probe';"
                + "a.style.position='fixed';a.style.top='180px';a.style.left='24px';"
                + "a.style.zIndex='2147483647';a.style.background='#fff';a.style.color='#00f';"
                + "a.style.padding='24px';a.style.fontSize='20px';document.body.appendChild(a);"
                + "window.__dlProbePage=(window.__dlProbePage||Math.random().toString(36).slice(2));return true;})()");
    }

    /** What the app itself logged since {@code marker}: loads, recoveries, refusals. */
    private static List<String> appLogSince(String marker) throws Exception {
        List<String> out = new ArrayList<String>();
        boolean after = false;
        for (String line : LivePageSupport.shell("logcat -d -v time -s " + TAG + ":V").split("\n")) {
            if (line.contains(marker)) {
                after = true;
                out.clear();
                continue;
            }
            if (!after || line.contains("dialog probe")) continue;
            int at = line.indexOf("): ");
            String message = at < 0 ? line : line.substring(at + 3);
            if (!message.matches(".*(recovery|load \\(|watchdog|refused document|download|not a navigation|saved|Blocked).*")) continue;
            // "11-14 00:54:27.123 I/Tag(pid): msg" -> "54:27.123 msg"
            String time = line.length() > 18 ? line.substring(9, 18) : "";
            out.add(time + " APP " + (message.length() > 150 ? message.substring(0, 150) : message));
        }
        return out;
    }

    @Test
    public void oneTapOnALinkShowsOneOfferAndOneTapOnSaveStartsTheDownload() throws Exception {
        Assume.assumeTrue("window inspection needs Android 10+", Build.VERSION.SDK_INT >= 29);
        if (Build.VERSION.SDK_INT >= 33) {
            LivePageSupport.shell("pm grant " + context().getPackageName() + " android.permission.POST_NOTIFICATIONS");
        }
        // {name, link, ms a person takes before pressing Save, link taps, "cancel-first" or ""}
        String[][] cases = {
                {"zip", REPO + "/archive/refs/heads/main.zip", "350", "1", ""},
                {"zip-slow", REPO + "/archive/refs/heads/main.zip", "1600", "1", ""},
                {"apk", REPO + "/releases/download/v1.0.0/app-release.apk", "700", "1", ""},
                {"raw", REPO + "/raw/main/README.md", "350", "1", ""},
                {"twice", REPO + "/archive/refs/heads/main.zip", "700", "2", ""},
                {"again", REPO + "/raw/main/README.md", "700", "1", "cancel-first"},
        };
        List<String> problems = new ArrayList<String>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            Assume.assumeTrue("the egress guard did not become ready", awaitGuard());
            for (String[] each : cases) {
                final String name = each[0];
                await(20_000L, () -> DownloadStateStore.read(context()).state != DownloadStateStore.State.ACTIVE);
                LivePageSupport.load(scenario, "https://github.com" + REPO);
                Assume.assumeTrue("the repository page did not render", LivePageSupport.waitFor(scenario,
                        "location.hostname==='github.com' && location.pathname===" + JSONObject.quote(REPO)
                                + " && document.readyState==='complete' && !!document.body"
                                + " && !document.getElementById('dl-probe')", 45_000));
                SystemClock.sleep(2_000L);

                final MainActivity[] activity = new MainActivity[1];
                scenario.onActivity(a -> activity[0] = a);
                final Timeline timeline = new Timeline(activity[0]);
                String marker = "dialog probe BEGIN " + name + " " + SystemClock.uptimeMillis();
                Log.i(TAG, marker);
                timeline.begin();
                long statusBefore = DownloadStateStore.read(context()).updatedAt;
                placeLink(scenario, each[1]);

                int linkTaps = Integer.parseInt(each[3]);
                for (int i = 1; i <= linkTaps; i++) {
                    timeline.note("TAP link" + (linkTaps > 1 ? " #" + i : ""));
                    LivePageSupport.tap(scenario, "#dl-probe");
                    if (i < linkTaps) SystemClock.sleep(600L);
                }
                boolean offered = await(25_000L, () -> timeline.offersNow > 0);
                timeline.note(offered ? "offer is on screen" : "NO offer appeared");

                if (offered && "cancel-first".equals(each[4])) {
                    SystemClock.sleep(600L);
                    float[] cancel = offerButton(android.R.id.button2);
                    timeline.note("TAP Cancel");
                    if (cancel != null) touch(cancel[0], cancel[1]);
                    await(2_000L, () -> timeline.offersNow == 0);
                    SystemClock.sleep(1_200L);
                    String kept = LivePageSupport.evaluate(scenario, "!!document.getElementById('dl-probe')");
                    timeline.note("after Cancel the link is " + ("true".equals(kept) ? "still there" : "GONE (page reloaded)"));
                    if (!"true".equals(kept)) {
                        LivePageSupport.waitFor(scenario, "document.readyState==='complete' && !!document.body", 30_000);
                        placeLink(scenario, each[1]);
                    }
                    timeline.note("TAP link again");
                    LivePageSupport.tap(scenario, "#dl-probe");
                    offered = await(25_000L, () -> timeline.offersNow > 0);
                    timeline.note(offered ? "offer is on screen" : "NO offer appeared");
                }

                int saveTaps = 0;
                if (offered) {
                    SystemClock.sleep(Long.parseLong(each[2]));
                    while (saveTaps < 6) {
                        // A further offer may still be on its way (it arrives with the server's redirect).
                        if (timeline.offersNow == 0 && !await(saveTaps == 0 ? 0L : 2_500L, () -> timeline.offersNow > 0)) break;
                        float[] save = offerButton(android.R.id.button1);
                        if (save == null) {
                            SystemClock.sleep(100L);
                            continue;
                        }
                        saveTaps++;
                        timeline.note("TAP Save #" + saveTaps);
                        touch(save[0], save[1]);
                        final int offersBefore = timeline.offersNow;
                        await(1_500L, () -> timeline.offersNow < offersBefore || timeline.offersNow == 0);
                    }
                }
                boolean finished = await(150_000L, () -> {
                    DownloadStateStore.Snapshot s = DownloadStateStore.read(context());
                    return s.updatedAt > statusBefore && s.state != DownloadStateStore.State.ACTIVE;
                });
                SystemClock.sleep(3_500L);
                String kept = "?";
                try {
                    kept = LivePageSupport.evaluate(scenario, "!!document.getElementById('dl-probe')");
                } catch (Throwable t) {
                    kept = "unknown (" + t.getClass().getSimpleName() + ")";
                }
                String url = LivePageSupport.url(scenario);
                DownloadStateStore.Snapshot end = DownloadStateStore.read(context());
                final boolean[] focused = new boolean[1];
                scenario.onActivity(a -> focused[0] = a.hasWindowFocus());
                timeline.end();

                String summary = name + ": link taps=" + linkTaps + " offers created=" + timeline.offersCreated()
                        + " dialogs=" + timeline.dialogTitles() + " most at once=" + timeline.maxDialogs
                        + " taps on Save=" + saveTaps + " strip shown=" + timeline.stripShownCount
                        + "x overlay shown=" + timeline.overlayShownCount + "x state=" + end.state
                        + " file=" + end.fileName + " bytes=" + end.bytesRead + " page kept=" + kept
                        + " page focused=" + focused[0] + " path=" + (url == null ? "" : url.replaceAll("^https://[^/]+|\\?.*", ""));
                Log.i(TAG, "dialog probe SUMMARY " + summary);
                List<String> merged = new ArrayList<String>();
                synchronized (timeline.entries) {
                    merged.addAll(timeline.entries);
                }
                merged.addAll(appLogSince(marker));
                Collections.sort(merged);
                for (String entry : merged) Log.i(TAG, "dialog probe " + name + " " + entry);

                if (!offered) problems.add(name + ": no save offer appeared");
                if (linkTaps == 1 && each[4].isEmpty()) {
                    if (timeline.offersCreated() != 1) problems.add(name + ": " + timeline.offersCreated() + " offers for one tap");
                    if (saveTaps != 1) problems.add(name + ": Save needed " + saveTaps + " taps");
                    if (timeline.dialogs.size() != 1) problems.add(name + ": dialogs shown " + timeline.dialogTitles());
                }
                if (timeline.maxDialogs > 1) problems.add(name + ": " + timeline.maxDialogs + " dialogs were stacked");
                if (timeline.overlayShownCount > 0) problems.add(name + ": the blocked overlay appeared");
                if (!finished || end.state != DownloadStateStore.State.COMPLETE) problems.add(name + ": ended as " + end.state);
                if (!"true".equals(kept)) problems.add(name + ": the page behind was reloaded");
                if (!focused[0]) problems.add(name + ": the page did not get the screen back");
            }
        }
        assertEquals("the tap flow misbehaved (timelines are in the 'Download tap flow' notices)",
                "", String.join("\n", problems));
    }
}
