package app.arenawebview.locked.ui;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import app.arenawebview.locked.R;

/**
 * Pull-to-refresh for the primary WebView, started from the top edge only.
 *
 * <p>A stock {@code SwipeRefreshLayout} takes over any downward drag as soon as its child cannot scroll
 * up. A WebView reports that for the <em>document</em> scroll position, so on a page whose content lives
 * in its own scroll container (a chat transcript, say) every drag towards the top of that container would
 * become a refresh instead. Two rules keep the gesture out of the page's way: it only counts when it
 * <em>starts</em> within the top {@link #EDGE_DP} of the WebView and the document itself is already at the
 * top, and it needs a long pull ({@link #TRIGGER_DP}) before it fires. Everything else goes to the page
 * untouched.</p>
 *
 * <p>What a refresh does is decided by the activity: the current URL is verified again before it is
 * reloaded, and anything that no longer passes is replaced by the start page. This view only reports the
 * gesture and shows the spinner.</p>
 */
public final class PullToRefreshFrame extends SwipeRefreshLayout {

    /** The gesture must begin within this distance from the top of the WebView. */
    private static final int EDGE_DP = 72;
    /** How far the finger has to travel before a refresh fires. */
    private static final int TRIGGER_DP = 128;

    public interface Listener {
        void onRefreshRequested();
    }

    private final int edgePx;
    private boolean gestureDeclined;

    public PullToRefreshFrame(Context context, final Listener listener) {
        super(context);
        edgePx = dp(EDGE_DP);
        setDistanceToTriggerSync(dp(TRIGGER_DP));
        setColorSchemeColors(context.getColor(R.color.accent));
        setProgressBackgroundColorSchemeColor(context.getColor(R.color.card));
        setOnRefreshListener(new OnRefreshListener() {
            @Override
            public void onRefresh() {
                listener.onRefreshRequested();
            }
        });
        setOnChildScrollUpCallback(new OnChildScrollUpCallback() {
            @Override
            public boolean canChildScrollUp(SwipeRefreshLayout parent, View child) {
                // Only the document scroll position is knowable here; the edge rule above covers the rest.
                return child != null && child.canScrollVertically(-1);
            }
        });
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                gestureDeclined = event.getY() > edgePx;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (gestureDeclined) {
                    gestureDeclined = false;
                    return false;
                }
                break;
            default:
                break;
        }
        if (gestureDeclined) {
            return false;
        }
        return super.onInterceptTouchEvent(event);
    }

    /** End the spinner (the page finished loading, or the reload was replaced by something else). */
    public void finishRefreshing() {
        if (isRefreshing()) {
            setRefreshing(false);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
