package app.githubwebview.locked;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import app.githubwebview.locked.policy.GitHubAllowlist;
import app.githubwebview.locked.policy.PolicyMetrics;

/**
 * Real signed-out forms in the real app. Test-only IME input and DOM observations, never production
 * input overrides. Uses reserved example.invalid addresses; no password, account creation or CAPTCHA
 * solution. A provider refusal is recorded/skipped, not made into a successful authentication claim.
 */
@RunWith(AndroidJUnit4.class)
public class EmailInputRegressionTest {
    private static final String TAG = "GitHubWebViewLocked";
    private static final String ADDRESS = "probe@example.invalid";
    private static final String EDITED = "edited@example.invalid";
    private static final String GOOGLE_BUTTON = "[...document.querySelectorAll('button,a,input[type=submit]')]"
            + ".find(function(e){var text=(e.getAttribute('aria-label')||e.innerText||e.value||e.textContent||'')"
            + ".replace(/\\s+/g,' ').trim();return /^(?:Continue|Sign in|Sign up) with Google(?:$|[, .])/i.test(text)"
            + " && e.getClientRects().length>0 && getComputedStyle(e).visibility!=='hidden'})";

    @Test
    public void githubEmailEntryEditingKeyboardResizeAndClientValidation() throws Exception {
        long since = System.currentTimeMillis();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            LivePageSupport.load(scenario, "https://github.com/login");
            boolean form = LivePageSupport.waitFor(scenario,
                    "location.hostname==='github.com' && !!document.querySelector('#login_field')", 45_000);
            Log.i(TAG, "input probe GitHub form present=" + form + " url=" + LivePageSupport.url(scenario));
            LivePageSupport.screenshot("github-login-empty");
            assertTrue("real GitHub login form must load; do not count a blank/error page as coverage", form);
            assertTrue("the site's own login JavaScript must initialize",
                    LivePageSupport.waitFor(scenario,
                            "!!document.querySelector('input[name=javascript-support]') && "
                                    + "document.querySelector('input[name=javascript-support]').value !== 'unknown'", 20_000));
            exerciseNativeInput(scenario, "#login_field", "github");
            assertNoRefusedApprovedResourcesSince(since);

            // A real click on Sign in with the password intentionally EMPTY. Native form validation
            // must run; this never sends a real/fake password or attempts to access an account.
            assertEquals("false", LivePageSupport.evaluate(scenario,
                    "document.querySelector('#login_field').form.checkValidity()"));
            LivePageSupport.evaluate(scenario,
                    "window.__wrapperInvalidEvents=0; document.querySelector('#login_field').form"
                            + ".addEventListener('invalid',function(){window.__wrapperInvalidEvents++;},true);");
            LivePageSupport.tap(scenario, "input[name=commit], form[action='/session'] button[type=submit]");
            assertTrue("native submit must trigger browser validation for missing password",
                    LivePageSupport.waitFor(scenario, "window.__wrapperInvalidEvents>0", 8_000));
            assertEquals("true", LivePageSupport.evaluate(scenario,
                    "!!document.querySelector('#password:invalid') && location.pathname==='/login'"));
            LivePageSupport.screenshot("github-login-validation");
            Log.i(TAG, "input probe GitHub: PASS native typing, selection/replacement, keyboard resize, "
                    + "prompt geometry and client-validation submit; NO password or authenticated submission tested");
        }
    }

    @Test
    public void googleIdentifierInputAndValidationOnlyWhenProviderServesTheForm() throws Exception {
        long since = System.currentTimeMillis();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            // Use the site's real handoff, with its real state/CSRF/callback. Never fabricate OAuth
            // URLs or fall back to a different sign-in flow to get past a provider rejection.
            LivePageSupport.load(scenario, "https://github.com/login");
            assertTrue("GitHub login should load before its Google handoff",
                    LivePageSupport.waitFor(scenario,
                            "location.hostname==='github.com' && !!document.querySelector('#login_field')", 45_000));
            boolean offered = LivePageSupport.waitFor(scenario, "!!(" + GOOGLE_BUTTON + ")", 10_000);
            Log.i(TAG, "input probe GitHub Google handoff offered=" + offered);
            if (!offered) {
                Log.i(TAG, "input probe Google control evidence=" + LivePageSupport.object(scenario, """
                        (function(){return {ua:navigator.userAgent,
                          controls:[...document.querySelectorAll('button,a,input[type=submit]')].map(function(e){
                            return {text:(e.getAttribute('aria-label')||e.innerText||e.value||e.textContent||'').trim(),
                              visible:e.getClientRects().length>0 && getComputedStyle(e).visibility!=='hidden'};
                          }).filter(function(c){return /google/i.test(c.text)})};})()
                        """));
                LivePageSupport.screenshot("github-google-option-unavailable");
            }
            assumeTrue("GitHub did not offer Google sign-in in this WebView; not tested", offered);
            LivePageSupport.tapElement(scenario, GOOGLE_BUTTON);
            boolean document = LivePageSupport.waitFor(scenario,
                    "location.hostname==='accounts.google.com' && document.readyState==='complete'"
                            + " && !!document.body && document.body.innerText.length>20", 45_000);
            LivePageSupport.screenshot("google-identifier-served");
            Log.i(TAG, "input probe Google document=" + document + " url=" + safeUrl(LivePageSupport.url(scenario)));
            assumeTrue("Google document unavailable; not a tested sign-in", document);
            String selector = "input[type=email], input[name=identifier], #identifierId";
            boolean form = LivePageSupport.waitFor(scenario,
                    "!!document.querySelector(" + JSONObject.quote(selector) + ")", 6_000);
            if (!form) {
                Log.i(TAG, "input probe Google: NO_IDENTIFIER_FORM; provider page="
                        + LivePageSupport.string(scenario, "document.title")
                        + "; no UA change, bypass, credential submission or browser fallback attempted");
                assumeTrue("Google did not serve an identifier form; input/submission NOT tested", false);
            }
            exerciseNativeInput(scenario, selector, "google");
            assertNoRefusedApprovedResourcesSince(since);
            boolean next = "true".equals(LivePageSupport.evaluate(scenario, "!!document.querySelector('#identifierNext')"));
            assumeTrue("Google Next control unavailable; native input was tested, submission was not", next);
            LivePageSupport.tap(scenario, "#identifierNext");
            boolean outcome = LivePageSupport.waitFor(scenario, """
                    (function(){
                      var text=(document.body ? document.body.innerText : '').toLowerCase();
                      return /couldn.t find|could not find|account.*not found|enter a valid|may not be secure|couldn.t sign|not supported|verify.*human|unusual traffic|try again|something went wrong/.test(text)
                        || !!document.querySelector('iframe[src*="recaptcha"]')
                        || !!document.querySelector('input[type=password]');
                    })()
                    """, 25_000);
            JSONObject state = LivePageSupport.object(scenario, """
                    (function(){
                      var text=(document.body ? document.body.innerText : '').toLowerCase();
                      return {title:document.title, path:location.pathname,
                        rejected:/may not be secure|couldn.t sign|not supported/.test(text),
                        validation:/couldn.t find|could not find|account.*not found|enter a valid/.test(text),
                        challenge:/verify.*human|unusual traffic/.test(text) || !!document.querySelector('iframe[src*="recaptcha"]'),
                        passwordField:!!document.querySelector('input[type=password]')};
                    })()
                    """);
            Log.i(TAG, "input probe Google identifier-only submission outcome=" + state + " changed=" + outcome);
            LivePageSupport.screenshot("google-identifier-response");
            assertTrue("Google Next must produce validation/challenge/refusal, not a dead pre-JS form: " + state, outcome);
            // No password is ever entered, even if a password field is unexpectedly shown.
            assertTrue(GitHubAllowlist.productionConfig().evaluateNavigation(LivePageSupport.url(scenario)).isAllowed());
        }
    }

    static void exerciseNativeInput(ActivityScenario<MainActivity> scenario, String selector,
                                             String name) throws Exception {
        // GitHub autofocuses this field on load. Clear that test precondition so the assertion below
        // proves the physical tap focused it, rather than accepting an already-focused DOM element.
        LivePageSupport.evaluate(scenario, "if(document.activeElement)document.activeElement.blur();");
        scenario.onActivity(a -> {
            WebView w = LivePageSupport.activeView(a);
            ((InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(w.getWindowToken(), 0);
        });
        Thread.sleep(900);
        int before = LivePageSupport.height(scenario);
        LivePageSupport.tap(scenario, selector);
        assertTrue("tap must focus the real input", LivePageSupport.waitFor(scenario,
                "document.activeElement===document.querySelector(" + JSONObject.quote(selector) + ")", 8_000));
        LivePageSupport.showKeyboard(scenario);
        long until = SystemClock.elapsedRealtime() + 20_000;
        while (SystemClock.elapsedRealtime() < until && (!LivePageSupport.keyboardVisible(scenario)
                || LivePageSupport.height(scenario) >= before)) Thread.sleep(200);
        Thread.sleep(350); // record settled window bounds, not the first frame of the IME transition
        int resized = LivePageSupport.height(scenario);
        Log.i(TAG, "input probe " + name + " keyboard visible=" + LivePageSupport.keyboardVisible(scenario)
                + " WebViewHeight=" + before + " -> " + resized);
        boolean shown = LivePageSupport.keyboardVisible(scenario);
        if (!shown) {
            JSONObject field = LivePageSupport.object(scenario,
                    "(function(){var e=document.querySelector(" + JSONObject.quote(selector)
                            + ");return {type:e.type,readOnly:e.readOnly,disabled:e.disabled,inputMode:e.inputMode,"
                            + "focused:document.activeElement===e,documentFocus:document.hasFocus()};})()");
            Log.i(TAG, "input probe missing IME field state=" + field);
            String dump = LivePageSupport.shell("dumpsys input_method");
            StringBuilder selected = new StringBuilder();
            for (String line : dump.split("\n")) {
                if (line.matches(".*(mShowRequested|mInputShown|mImeWindowVis|mShowExplicitlyRequested|mShowForced|mCurMethodId|inputType=).*"))
                    selected.append(line.trim()).append(" ; ");
                if (selected.length() > 1600) break;
            }
            Log.i(TAG, "input probe missing IME service state=" + selected);
        }
        LivePageSupport.type(scenario, ADDRESS);
        assertTrue("native typed email must reach the form", LivePageSupport.waitFor(scenario,
                "document.querySelector(" + JSONObject.quote(selector) + ").value===" + JSONObject.quote(ADDRESS), 8_000));
        assertClearPrompt(scenario, selector, name);
        LivePageSupport.screenshot(name + "-email-typed");
        LivePageSupport.replaceSelection(scenario, 0, 5, "edited");
        assertTrue("native selection/replacement must edit the email", LivePageSupport.waitFor(scenario,
                "document.querySelector(" + JSONObject.quote(selector) + ").value===" + JSONObject.quote(EDITED), 8_000));
        assertClearPrompt(scenario, selector, name);
        LivePageSupport.screenshot(name + "-email-edited");
        // Do not abort typing/overlap observations merely because keyboard automation failed, but
        // still fail the test (never turn an unverified keyboard into a pass).
        assertTrue("the real software keyboard must be visible", shown);
        assertTrue("adjustResize must reduce WebView height", resized > 0 && resized < before);
    }

    private static void assertClearPrompt(ActivityScenario<MainActivity> scenario, String selector, String name) throws Exception {
        Thread.sleep(450); // allow the real site floating-label transition to settle
        String script = """
                (function(){
                  var e=document.querySelector(SELECTOR), r=e.getBoundingClientRect(), s=getComputedStyle(e);
                  var size=parseFloat(s.fontSize), left=r.left+parseFloat(s.paddingLeft)+parseFloat(s.borderLeftWidth);
                  var canvas=document.createElement('canvas'), ctx=canvas.getContext('2d');
                  ctx.font=s.font || (s.fontSize+' '+s.fontFamily);
                  var right=Math.min(r.right,left+ctx.measureText(e.value).width);
                  var top=r.top+r.height/2-size/2, bottom=top+size, overlaps=[];
                  var candidates=[...document.querySelectorAll('label,span,div')].filter(function(n){
                    return n.children.length===0 && /^(enter your email|email or phone|email address|username or email address)$/i.test(n.textContent.trim());
                  });
                  function visible(n){
                    var opacity=1;
                    for(var p=n;p && p.nodeType===1;p=p.parentElement){
                      var st=getComputedStyle(p);opacity*=parseFloat(st.opacity);
                      if(st.display==='none'||st.visibility==='hidden'||opacity<0.05)return false;
                    }
                    return true;
                  }
                  candidates.forEach(function(n){
                    var b=n.getBoundingClientRect();
                    if(visible(n)
                      && b.width>0 && b.height>0 && b.right>left+2 && b.left<right-2
                      && b.bottom>top+2 && b.top<bottom-2) overlaps.push(n.textContent.trim());
                  });
                  return {valueLength:e.value.length, placeholderShown:e.matches(':placeholder-shown'),
                    promptOverlaps:overlaps, inputHeight:r.height, fontSize:size};
                })()
                """.replace("SELECTOR", JSONObject.quote(selector));
        JSONObject geometry = LivePageSupport.object(scenario, script);
        Log.i(TAG, "input probe " + name + " prompt geometry=" + geometry);
        assertFalse("placeholder must not remain shown over a typed value", geometry.getBoolean("placeholderShown"));
        assertTrue("visible email prompt must not overlap typed text: " + geometry,
                geometry.getJSONArray("promptOverlaps").length() == 0);
    }

    private static void assertNoRefusedApprovedResourcesSince(long since) {
        for (PolicyMetrics.BlockEvent event : PolicyMetrics.get().recentBlocks()) {
            if (event.timestampMs() >= since && GitHubAllowlist.thirdPartyHosts().contains(event.host())) {
                assertTrue("approved login resource was refused: " + event.describe(), false);
            }
        }
    }

    private static String safeUrl(String raw) {
        if (raw == null) return "null";
        return raw.split("[?#]", 2)[0];
    }
}
