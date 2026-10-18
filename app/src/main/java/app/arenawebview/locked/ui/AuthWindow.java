package app.arenawebview.locked.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.arenawebview.locked.R;

/**
 * The in-app window that a sign-in hand-off is shown in.
 *
 * <p>This existing UI is reserved for an explicitly approved provider whose sign-in flow returns its
 * result through {@code window.opener}/postMessage, so the pop-up has to stay alive. Hosting Chromium's
 * pop-up WebView here keeps any approved hand-off inside this app; the app never sends it to a system
 * browser.</p>
 *
 * <p>Properties that matter:</p>
 * <ul>
 *   <li>The hosted WebView is the one Chromium created for the pop-up, so {@code window.opener} works
 *       and the site's own JavaScript completes the sign-in.</li>
 *   <li>The window is a normal child of this activity's view hierarchy - no second activity, no
 *       {@code startActivity}, no system browser.</li>
 *   <li>It is policed by the same policy as the main view: the pop-up's client refuses anything that is
 *       not an approved sign-in host or a GitHub.com page (see {@code PopupRouterClient}).</li>
 *   <li>It can always be closed by the user, and closing it destroys the WebView rather than parking it.</li>
 * </ul>
 */
public final class AuthWindow extends FrameLayout {

    /** Callbacks the activity implements. */
    public interface Listener {
        /** User asked to close the sign-in window. */
        void onCloseRequested();
    }

    private final TextView titleView;
    private final FrameLayout content;
    private View hostedWebView;

    public AuthWindow(Context context, final Listener listener) {
        super(context);
        setId(R.id.auth_window);
        setBackgroundResource(R.drawable.overlay_scrim);
        setClickable(true); // swallow taps: the main view behind must not be reachable while signing in
        setFocusable(true);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.overlay_card);
        int padding = dp(12);
        card.setPadding(padding, padding, padding, padding);
        card.setId(R.id.auth_window_card);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(getResources().getColor(R.color.on_card));
        titleView.setText(R.string.auth_window_title);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleView.setLayoutParams(titleParams);
        header.addView(titleView);

        Button close = new Button(context);
        close.setId(R.id.auth_window_close);
        close.setText(R.string.auth_window_close);
        close.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onCloseRequested();
            }
        });
        header.addView(close);

        card.addView(header);

        content = new FrameLayout(context);
        content.setId(R.id.auth_window_content);
        content.setBackgroundColor(getResources().getColor(R.color.card_stroke));
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        contentParams.topMargin = dp(10);
        content.setLayoutParams(contentParams);
        card.addView(content);

        TextView footer = new TextView(context);
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        footer.setTextColor(getResources().getColor(R.color.on_card_muted));
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footerParams.topMargin = dp(8);
        footer.setLayoutParams(footerParams);
        footer.setText(R.string.auth_window_footer);
        card.addView(footer);

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        int margin = dp(12);
        cardParams.setMargins(margin, margin, margin, margin);
        addView(card, cardParams);

        setVisibility(GONE);
    }

    /**
     * Show the sign-in window with a WebView in it.
     *
     * <p>Idempotent for the same WebView: the hand-off navigates several times (identifier, password,
     * consent) and each navigation must not re-parent the view.</p>
     */
    public void show(View webView, String urlHint) {
        if (webView == null) {
            return;
        }
        if (hostedWebView != webView) {
            content.removeAllViews();
            content.addView(webView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            hostedWebView = webView;
        }
        titleView.setText(urlHint == null || urlHint.isEmpty()
                ? getContext().getString(R.string.auth_window_title)
                : urlHint);
        setVisibility(VISIBLE);
        bringToFront();
    }

    /** Detach whatever is hosted and hide. The hosted WebView is destroyed by its owner. */
    public void hide() {
        content.removeAllViews();
        hostedWebView = null;
        setVisibility(GONE);
    }

    /** True while a sign-in window is on screen. */
    public boolean isWindowVisible() {
        return getVisibility() == VISIBLE;
    }

    /** The WebView currently hosted, or null. */
    public View hostedWebView() {
        return hostedWebView;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
