package app.githubwebview.locked;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Rect;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Test-only helpers. Nothing in this class is packaged into the installed app. */
final class LivePageSupport {
    private LivePageSupport() { }

    /** Use the actual in-app auth window if the site's handoff opened one. */
    static WebView activeView(MainActivity activity) {
        View auth = activity.findViewById(R.id.auth_window);
        ViewGroup content = activity.findViewById(R.id.auth_window_content);
        if (auth != null && auth.getVisibility() == View.VISIBLE && content != null) {
            for (int i = 0; i < content.getChildCount(); i++) {
                if (content.getChildAt(i) instanceof WebView) return (WebView) content.getChildAt(i);
            }
        }
        return activity.findViewById(R.id.github_webview);
    }

    static String evaluate(ActivityScenario<MainActivity> scenario, String script) throws Exception {
        AtomicReference<String> result = new AtomicReference<String>();
        CountDownLatch done = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            WebView view = activeView(activity);
            assertNotNull(view);
            view.evaluateJavascript(script, value -> { result.set(value); done.countDown(); });
        });
        assertTrue("WebView evaluation timed out", done.await(15, TimeUnit.SECONDS));
        assertNotNull("WebView returned no result", result.get());
        return result.get();
    }

    static String string(ActivityScenario<MainActivity> scenario, String script) throws Exception {
        return String.valueOf(new JSONTokener(evaluate(scenario, script)).nextValue());
    }

    static JSONObject object(ActivityScenario<MainActivity> scenario, String script) throws Exception {
        return new JSONObject(evaluate(scenario, script));
    }

    static boolean waitFor(ActivityScenario<MainActivity> scenario, String condition, long timeoutMs)
            throws Exception {
        long end = SystemClock.elapsedRealtime() + timeoutMs;
        do {
            if ("true".equals(evaluate(scenario, condition))) return true;
            Thread.sleep(300);
        } while (SystemClock.elapsedRealtime() < end);
        return false;
    }

    static void load(ActivityScenario<MainActivity> scenario, String url) {
        scenario.onActivity(a -> a.loadVerifiedDestination(url, "test:live-probe"));
    }

    static String url(ActivityScenario<MainActivity> scenario) {
        AtomicReference<String> url = new AtomicReference<String>();
        scenario.onActivity(a -> url.set(activeView(a).getUrl()));
        return url.get();
    }

    /** A real touch event, not element.value = text. Scroll only to make the target visible. */
    static void tap(ActivityScenario<MainActivity> scenario, String selector) throws Exception {
        tapElement(scenario, "document.querySelector(" + JSONObject.quote(selector) + ")");
    }

    static void tapElement(ActivityScenario<MainActivity> scenario, String expression) throws Exception {
        evaluate(scenario, "(function(){var e=" + expression
                + ";if(e)e.scrollIntoView({block:'center'});return !!e;})()");
        Thread.sleep(350);
        JSONObject rect = object(scenario, "(function(){var e=" + expression
                + ";if(!e)return {};var r=e.getBoundingClientRect(),v=window.visualViewport;"
                + "return {x:r.left+r.width/2-(v?v.offsetLeft:0),"
                + "y:r.top+r.height/2-(v?v.offsetTop:0),width:v?v.width:window.innerWidth};})()");
        assertTrue("missing tappable element", rect.has("x"));
        float[] xy = new float[2];
        scenario.onActivity(a -> {
            WebView view = activeView(a);
            int[] origin = new int[2];
            view.getLocationOnScreen(origin);
            float scale = view.getWidth() / (float) rect.optDouble("width", 1);
            xy[0] = origin[0] + (float) rect.optDouble("x") * scale;
            xy[1] = origin[1] + (float) rect.optDouble("y") * scale;
            Rect visible = new Rect();
            assertTrue("active WebView must be visible", view.getGlobalVisibleRect(visible));
            assertTrue("native tap must land inside the WebView, not the keyboard/system UI: "
                    + rect + " screen=" + xy[0] + "," + xy[1] + " bounds=" + visible,
                    visible.contains(Math.round(xy[0]), Math.round(xy[1])));
        });
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, xy[0], xy[1], 0);
        MotionEvent up = null;
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            // A genuine timed gesture through UiAutomation, not self-UID-targeted input. The IME
            // can create a different-UID window while a field gains focus between DOWN and UP.
            assertTrue("touch DOWN dispatched", instrumentation.getUiAutomation().injectInputEvent(down, true));
            SystemClock.sleep(100);
            up = MotionEvent.obtain(now, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, xy[0], xy[1], 0);
            up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            assertTrue("touch UP dispatched", instrumentation.getUiAutomation().injectInputEvent(up, true));
        } finally { down.recycle(); if (up != null) up.recycle(); }
        instrumentation.waitForIdleSync();
    }

    static void showKeyboard(ActivityScenario<MainActivity> scenario) {
        scenario.onActivity(a -> {
            WebView view = activeView(a);
            InputMethodManager ime = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
            EditorInfo info = new EditorInfo();
            InputConnection connection = view.onCreateInputConnection(info);
            android.util.Log.i("GitHubWebViewLocked", "input probe editor: type=" + info.inputType
                    + " options=" + info.imeOptions + " connection=" + (connection != null));
            // An active WebView is not necessarily a served *text editor*: the IMM may still hold
            // the connection from its initial empty document. Refresh that test-side connection
            // after real DOM/touch focus, then give the renderer/IME handshake a UI turn.
            ime.restartInput(view);
            view.postDelayed(() -> {
                boolean accepted = ime.showSoftInput(view, 0); // explicit normal request, NOT forced
                android.util.Log.i("GitHubWebViewLocked", "input probe IME request: windowFocus="
                        + view.hasWindowFocus() + " viewFocus=" + view.hasFocus()
                        + " served=" + ime.isActive(view) + " accepted=" + accepted);
            }, 300);
        });
    }

    /** Use Chromium's native IME path; no DOM value assignment or synthetic input event. */
    static void type(ActivityScenario<MainActivity> scenario, String text) {
        scenario.onActivity(a -> {
            WebView view = activeView(a);
            InputConnection connection = view.onCreateInputConnection(new EditorInfo());
            assertNotNull("focused field must expose an IME connection", connection);
            assertTrue("native IME commitText", connection.commitText(text, 1));
            connection.finishComposingText();
        });
    }

    static void replaceSelection(ActivityScenario<MainActivity> scenario, int start, int end, String text) {
        scenario.onActivity(a -> {
            WebView view = activeView(a);
            InputConnection connection = view.onCreateInputConnection(new EditorInfo());
            assertNotNull(connection);
            assertTrue(connection.setSelection(start, end));
            assertTrue(connection.commitText(text, 1));
            connection.finishComposingText();
        });
    }

    static int height(ActivityScenario<MainActivity> scenario) {
        int[] height = new int[1];
        scenario.onActivity(a -> height[0] = activeView(a).getHeight());
        return height[0];
    }

    static boolean keyboardVisible(ActivityScenario<MainActivity> scenario) {
        boolean[] visible = new boolean[1];
        scenario.onActivity(a -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                WindowInsets insets = activeView(a).getRootWindowInsets();
                visible[0] = insets != null && insets.isVisible(WindowInsets.Type.ime());
            }
        });
        return visible[0];
    }

    /** Only signed-out pages and the reserved example.invalid test address are captured. */
    static void screenshot(String name) throws Exception {
        assertTrue(name.matches("[a-z0-9-]+"));
        // Test instrumentation shell privilege, NOT an app permission or file-saving capability.
        // This directory survives connectedAndroidTest uninstall so CI can pull the evidence.
        String path = "/sdcard/Download/github-wrapper-evidence/" + name + ".png";
        // UiAutomation executes argv, not a shell command line: &&/redirection are NOT interpreted.
        shell("mkdir -p /sdcard/Download/github-wrapper-evidence");
        shell("screencap -p " + path);
        String size = shell("stat -c %s " + path).trim();
        assertTrue("screenshot must exist with PNG bytes, size=" + size,
                size.matches("[0-9]+") && Long.parseLong(size) > 1000);
    }

    static String shell(String command) throws Exception {
        ParcelFileDescriptor fd = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand(command);
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
