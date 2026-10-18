package app.arenawebview.locked.policy;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Back navigation must only ever move between verified pages, fall back to the start page, or close the
 * app. It must never be a way to reach a page that the policy refused.
 */
public class BackNavigatorTest {

    private static final PolicyConfig CONFIG = GitHubAllowlist.productionConfig();

    @Test
    public void goesBackThroughVerifiedHistory() {
        assertEquals(BackNavigator.Action.GO_BACK,
                BackNavigator.decide(true, "https://github.com/chat", CONFIG));
    }

    @Test
    public void exitsWhenThereIsNoHistory() {
        assertEquals(BackNavigator.Action.EXIT, BackNavigator.decide(false, null, CONFIG));
        // Even with a (stale) previous entry, no history means there is nothing to go back to.
        assertEquals(BackNavigator.Action.EXIT,
                BackNavigator.decide(false, "https://github.com/chat", CONFIG));
    }

    @Test
    public void returnsToStartWhenThePreviousEntryCannotBeVerified() {
        assertEquals(BackNavigator.Action.SHOW_START,
                BackNavigator.decide(true, "https://evil.example/", CONFIG));
        assertEquals(BackNavigator.Action.SHOW_START,
                BackNavigator.decide(true, "http://github.com/", CONFIG));
        assertEquals(BackNavigator.Action.SHOW_START,
                BackNavigator.decide(true, "javascript:alert(1)", CONFIG));
        assertEquals(BackNavigator.Action.SHOW_START,
                BackNavigator.decide(true, "https://github.com.evil.example/", CONFIG));
    }

    @Test
    public void returnsToStartWhenThePreviousEntryIsUnknown() {
        assertEquals(BackNavigator.Action.SHOW_START, BackNavigator.decide(true, null, CONFIG));
        assertEquals(BackNavigator.Action.SHOW_START, BackNavigator.decide(true, "", CONFIG));
    }

    @Test
    public void neverReturnsAnActionThatCouldLeaveTheApp() {
        String[] hostile = {
                "https://evil.example/", "intent://x/#Intent;end", "market://details?id=x",
                "file:///etc/hosts", "content://x/y", "https://github.com@evil.example/",
        };
        for (String url : hostile) {
            BackNavigator.Action action = BackNavigator.decide(true, url, CONFIG);
            assertEquals("must not navigate to " + url, BackNavigator.Action.SHOW_START, action);
        }
    }
}
