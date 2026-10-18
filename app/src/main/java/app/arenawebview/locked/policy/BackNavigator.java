package app.arenawebview.locked.policy;

/**
 * Decides what the Android back gesture does, without ever leaving the wrapper.
 *
 * <p>The WebView's own history is consulted through {@code WebView.copyBackForwardList()} (the caller
 * supplies the URL of the previous entry). A previous entry is only trusted if it still passes the
 * policy, so a history entry left behind by a blocked or partially-loaded navigation can never be
 * rendered by pressing back.</p>
 *
 * <p>Pure logic, no Android imports - unit tested by {@code BackNavigatorTest}.</p>
 */
public final class BackNavigator {

    /** What the activity should do with a back press. */
    public enum Action {
        /** The WebView can go back into its own verified history. */
        GO_BACK,
        /** History is unusable; return to the policy's start page instead (still inside the app). */
        SHOW_START,
        /** Nothing left to go back to: the activity may finish. Never opens another app. */
        EXIT
    }

    private BackNavigator() {
        // no instances
    }

    /**
     * @param canGoBack        value of {@code WebView.canGoBack()}
     * @param previousEntryUrl URL of the history entry that a "go back" would land on, or null if the
     *                         caller could not determine it
     * @param config           the active policy
     */
    public static Action decide(boolean canGoBack, String previousEntryUrl, PolicyConfig config) {
        if (!canGoBack) {
            return Action.EXIT;
        }
        if (previousEntryUrl == null || previousEntryUrl.isEmpty()) {
            // We cannot prove the destination is safe, so we do not navigate to it. Going back to the
            // start page is always inside the app and is the fail-closed choice.
            return Action.SHOW_START;
        }
        PolicyResult result = config.evaluateNavigation(previousEntryUrl);
        if (result.isAllowed()) {
            return Action.GO_BACK;
        }
        return Action.SHOW_START;
    }
}
