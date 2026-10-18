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
import android.widget.ScrollView;
import android.widget.TextView;

import app.arenawebview.locked.R;

/**
 * Native "blocked / could not load" surface.
 *
 * <p>This is a plain Android view, not a WebView page: it is opaque, it is the last child of the root
 * layout (so it always covers the WebView), and it contains no remote content. Whenever it is visible
 * the user cannot be looking at an unverified third-party page - which is exactly what makes the
 * fail-closed behaviour user visible instead of silent.</p>
 */
public final class StatusOverlay extends FrameLayout {

    /** Callbacks the activity implements. */
    public interface Listener {
        /** User asked to go back to the policy's start page. */
        void onReloadRequested();

        /** User asked to copy the diagnostics report. */
        void onCopyDiagnosticsRequested();

        /** User dismissed the overlay. */
        void onDismissRequested();
    }

    private final TextView titleView;
    private final TextView bodyView;
    private final TextView detailView;
    private final Button primaryButton;

    public StatusOverlay(Context context, final Listener listener) {
        super(context);
        setId(R.id.status_overlay);
        setBackgroundResource(R.drawable.overlay_scrim);
        setClickable(true); // swallow taps so nothing behind the overlay can be interacted with
        setFocusable(true);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.overlay_card);
        int padding = dp(20);
        card.setPadding(padding, padding, padding, padding);

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(getResources().getColor(R.color.on_card));
        titleView.setText(R.string.overlay_title_blocked);
        card.addView(titleView);

        bodyView = new TextView(context);
        bodyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        bodyView.setTextColor(getResources().getColor(R.color.on_card));
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyParams.topMargin = dp(12);
        bodyView.setLayoutParams(bodyParams);
        card.addView(bodyView);

        detailView = new TextView(context);
        detailView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        detailView.setTypeface(Typeface.MONOSPACE);
        detailView.setTextColor(getResources().getColor(R.color.on_card_muted));
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailParams.topMargin = dp(10);
        detailView.setLayoutParams(detailParams);
        card.addView(detailView);

        ScrollView scroll = new ScrollView(context);
        scroll.addView(card, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollParams.gravity = Gravity.CENTER;
        int margin = dp(20);
        scrollParams.setMargins(margin, margin, margin, margin);
        scroll.setLayoutParams(scrollParams);
        addView(scroll);

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams buttonRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonRowParams.topMargin = dp(18);
        buttons.setLayoutParams(buttonRowParams);

        primaryButton = new Button(context);
        primaryButton.setText(R.string.overlay_reload);
        primaryButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onReloadRequested();
            }
        });
        buttons.addView(primaryButton);

        Button copyButton = new Button(context);
        copyButton.setText(R.string.overlay_copy);
        copyButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onCopyDiagnosticsRequested();
            }
        });
        buttons.addView(copyButton);

        Button dismissButton = new Button(context);
        dismissButton.setText(R.string.overlay_dismiss);
        dismissButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onDismissRequested();
            }
        });
        buttons.addView(dismissButton);

        card.addView(buttons);

        TextView footer = new TextView(context);
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        footer.setTextColor(getResources().getColor(R.color.on_card_muted));
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footerParams.topMargin = dp(14);
        footer.setLayoutParams(footerParams);
        footer.setText(R.string.overlay_footer);
        card.addView(footer);

        setVisibility(GONE);
    }

    /** Show the "blocked" state: which destination was refused and why. */
    public void showBlocked(String attemptedHost, String humanReason, String detail) {
        titleView.setText(R.string.overlay_title_blocked);
        primaryButton.setText(R.string.overlay_reload);
        StringBuilder body = new StringBuilder();
        if (attemptedHost != null && !attemptedHost.isEmpty()) {
            body.append("This app only opens GitHub.com. It refused to open:\n\n");
            body.append(attemptedHost).append('\n');
            if (humanReason != null && !humanReason.isEmpty()) {
                body.append("\nReason: ").append(humanReason).append('.');
            }
        } else {
            body.append("This app only opens GitHub.com and refused a destination that could not be verified.");
            if (humanReason != null && !humanReason.isEmpty()) {
                body.append(" Reason: ").append(humanReason).append('.');
            }
        }
        bodyView.setText(body.toString());
        detailView.setText(detail == null ? "" : detail);
        setVisibility(VISIBLE);
        bringToFront();
    }

    /** Show a load failure (no network, TLS refused, renderer lost...). */
    public void showError(String message) {
        titleView.setText(R.string.overlay_title_error);
        primaryButton.setText(R.string.overlay_reload);
        bodyView.setText(message);
        detailView.setText("");
        setVisibility(VISIBLE);
        bringToFront();
    }

    /** Hide the overlay (the WebView is showing an approved page again). */
    public void hide() {
        setVisibility(GONE);
    }

    /** Name avoids clashing with {@link View#isShown()}. */
    public boolean isOverlayVisible() {
        return getVisibility() == VISIBLE;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
