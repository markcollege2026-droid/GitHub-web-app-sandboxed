package app.githubwebview.locked.ui;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import app.githubwebview.locked.R;

/**
 * The in-app view of a background download: one slim strip at the bottom edge of the page.
 *
 * <p>It replaces the modal "Downloading" dialog. A dialog owns the window: while it was up the page
 * could not be scrolled or tapped, and dismissing it (Back, or a tap beside it) cancelled the
 * transfer. The strip is an ordinary child view instead:</p>
 * <ul>
 *   <li>it never takes window focus and never covers more than its own height, so the page behind
 *       it stays fully usable while a file downloads;</li>
 *   <li>nothing about it is dismissible-by-accident: a transfer stops only when the user presses
 *       <em>Cancel</em> here or in the notification;</li>
 *   <li>it can be put away with <em>Hide</em> without affecting the transfer, which the foreground
 *       service owns and reports in its notification either way.</li>
 * </ul>
 *
 * <p>A failed or interrupted transfer is shown in the same place with <em>Retry</em> and
 * <em>Dismiss</em>, again without blocking the page. The strip holds no download state and performs
 * no network or storage work; it only renders what {@code MainActivity} tells it.</p>
 */
public final class DownloadStrip extends LinearLayout {

    /** Callbacks the activity implements. */
    public interface Listener {
        /** The user pressed Cancel on a running transfer. */
        void onCancelRequested();

        /** The user pressed Retry on a failed or interrupted transfer. */
        void onRetryRequested();

        /** The user put the strip away. {@code wasFailure} is true when it was showing a failure. */
        void onHidden(boolean wasFailure);
    }

    private static final int MODE_NONE = 0;
    private static final int MODE_PROGRESS = 1;
    private static final int MODE_FAILURE = 2;

    private final TextView text;
    private final ProgressBar progress;
    private final Button primary;
    private final Button secondary;
    private int mode = MODE_NONE;

    public DownloadStrip(Context context, final Listener listener) {
        super(context);
        setId(R.id.download_strip);
        setOrientation(VERTICAL);
        setBackgroundResource(R.drawable.overlay_card);
        // Taps on the strip itself must not fall through to the link underneath it; everything
        // outside the strip is untouched. The container never holds focus (its buttons can).
        setClickable(true);
        setFocusable(false);
        int padding = dp(12);
        setPadding(padding, dp(6), dp(4), dp(6));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        text = new TextView(context);
        text.setId(R.id.download_strip_text);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        text.setTextColor(getResources().getColor(R.color.on_card));
        text.setMaxLines(3);
        text.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        secondary = button(context, R.id.download_strip_secondary);
        secondary.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean wasFailure = mode == MODE_FAILURE;
                hide();
                listener.onHidden(wasFailure);
            }
        });
        row.addView(secondary);

        primary = button(context, R.id.download_strip_primary);
        primary.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mode == MODE_PROGRESS) {
                    listener.onCancelRequested();
                } else if (mode == MODE_FAILURE) {
                    hide();
                    listener.onRetryRequested();
                }
            }
        });
        row.addView(primary);
        addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        progressParams.rightMargin = dp(8);
        addView(progress, progressParams);

        setVisibility(GONE);
    }

    /** A running transfer. {@code totalBytes <= 0} means the server did not say how large the file is. */
    public void showProgress(String fileName, long bytesRead, long totalBytes) {
        mode = MODE_PROGRESS;
        StringBuilder line = new StringBuilder("Downloading ").append(name(fileName)).append('\n')
                .append(humanBytes(bytesRead));
        if (totalBytes > 0) {
            int percent = (int) Math.min(100L, (bytesRead * 100L) / totalBytes);
            line.append(" of ").append(humanBytes(totalBytes)).append(" - ").append(percent).append('%');
            progress.setIndeterminate(false);
            progress.setProgress(percent);
        } else {
            progress.setIndeterminate(true);
        }
        text.setText(line.toString());
        progress.setVisibility(VISIBLE);
        secondary.setText("Hide");
        primary.setText("Cancel");
        primary.setVisibility(VISIBLE);
        setVisibility(VISIBLE);
    }

    /** A transfer that ended without a file. {@code retryable} offers Retry; nothing was saved either way. */
    public void showFailure(String title, String detail, boolean retryable) {
        mode = MODE_FAILURE;
        StringBuilder line = new StringBuilder(title == null || title.isEmpty() ? "Download failed" : title);
        if (detail != null && !detail.isEmpty()) {
            line.append('\n').append(detail);
        }
        text.setText(line.toString());
        progress.setVisibility(GONE);
        secondary.setText("Dismiss");
        primary.setText("Retry");
        primary.setVisibility(retryable ? VISIBLE : GONE);
        setVisibility(VISIBLE);
    }

    public void hide() {
        mode = MODE_NONE;
        setVisibility(GONE);
    }

    public boolean isShowingProgress() {
        return mode == MODE_PROGRESS;
    }

    public boolean isShowingFailure() {
        return mode == MODE_FAILURE;
    }

    private Button button(Context context, int id) {
        Button button = new Button(context, null, android.R.attr.borderlessButtonStyle);
        button.setId(id);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        button.setTextColor(getResources().getColor(R.color.accent));
        button.setMinWidth(dp(48));
        button.setMinimumWidth(dp(48));
        button.setPadding(dp(10), 0, dp(10), 0);
        return button;
    }

    private static String name(String fileName) {
        return fileName == null || fileName.isEmpty() ? "file" : fileName;
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return (bytes / 1024L) + " KB";
        }
        long tenths = (bytes * 10L) / (1024L * 1024L);
        return (tenths / 10L) + "." + (tenths % 10L) + " MB";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
