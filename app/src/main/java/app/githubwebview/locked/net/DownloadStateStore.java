package app.githubwebview.locked.net;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Persistent, URL-redacted status shared between the foreground service and Activities. */
public final class DownloadStateStore {
    public enum State { IDLE, ACTIVE, COMPLETE, FAILED, CANCELLED, REFUSED, INTERRUPTED }

    public static final String ACTION_CHANGED = "app.githubwebview.locked.DOWNLOAD_STATE_CHANGED";
    private static final String PREFS = "background_download";
    private static final String K_STATE = "state";
    private static final String K_NAME = "name";
    private static final String K_READ = "read";
    private static final String K_TOTAL = "total";
    private static final String K_MESSAGE = "message";
    private static final String K_LOCATION = "location";
    private static final String K_HOST = "host";
    // Kept only in the app-private, non-backed-up preferences for a user-requested retry.
    // Never included in snapshots, broadcasts, notifications, or diagnostics.
    private static final String K_URL = "retry_url";
    private static final String K_DISPOSITION = "retry_disposition";
    private static final String K_MIME = "retry_mime";
    private static final String K_LENGTH = "retry_length";
    private static final String K_UPDATED = "updated_at";

    public static final class Snapshot {
        public final State state;
        public final String fileName;
        public final long bytesRead;
        public final long totalBytes;
        public final String message;
        public final String location;
        public final String host;
        public final long updatedAt;

        Snapshot(State state, String fileName, long bytesRead, long totalBytes,
                 String message, String location, String host, long updatedAt) {
            this.state = state;
            this.fileName = fileName;
            this.bytesRead = bytesRead;
            this.totalBytes = totalBytes;
            this.message = message;
            this.location = location;
            this.host = host;
            this.updatedAt = updatedAt;
        }

        public boolean isActive() { return state == State.ACTIVE; }
    }

    public static final class RetryRequest {
        public final String url;
        public final String disposition;
        public final String mimeType;
        public final long contentLength;

        RetryRequest(String url, String disposition, String mimeType, long contentLength) {
            this.url = url;
            this.disposition = disposition;
            this.mimeType = mimeType;
            this.contentLength = contentLength;
        }
    }

    private DownloadStateStore() { }

    public static Snapshot read(Context context) {
        SharedPreferences p = prefs(context);
        State state;
        try { state = State.valueOf(p.getString(K_STATE, State.IDLE.name())); }
        catch (IllegalArgumentException e) { state = State.IDLE; }
        return new Snapshot(state, p.getString(K_NAME, ""), p.getLong(K_READ, 0L),
                p.getLong(K_TOTAL, -1L), p.getString(K_MESSAGE, ""),
                p.getString(K_LOCATION, ""), p.getString(K_HOST, ""), p.getLong(K_UPDATED, 0L));
    }

    public static RetryRequest retryRequest(Context context) {
        SharedPreferences p = prefs(context);
        String url = p.getString(K_URL, "");
        if (url.isEmpty()) return null;
        return new RetryRequest(url, p.getString(K_DISPOSITION, null), p.getString(K_MIME, null),
                p.getLong(K_LENGTH, -1L));
    }

    static void update(Context context, State state, String name, long read, long total,
                       String message, String location, String host, String url,
                       String disposition, String mime, long length) {
        SharedPreferences.Editor e = prefs(context).edit()
                .putString(K_STATE, state.name())
                .putString(K_NAME, name == null ? "" : name)
                .putLong(K_READ, read)
                .putLong(K_TOTAL, total)
                .putString(K_MESSAGE, message == null ? "" : message)
                .putString(K_LOCATION, location == null ? "" : location)
                .putString(K_HOST, host == null ? "" : host)
                .putLong(K_UPDATED, System.currentTimeMillis());
        if (url != null) {
            e.putString(K_URL, url)
                    .putString(K_DISPOSITION, disposition)
                    .putString(K_MIME, mime)
                    .putLong(K_LENGTH, length);
        }
        e.apply();
        context.sendBroadcast(new Intent(ACTION_CHANGED).setPackage(context.getPackageName()));
    }

    /** Mark a process-lost transfer interrupted; stale storage remains invisible/partial and is cleaned. */
    public static boolean recoverInterrupted(Context context) {
        Snapshot current = read(context);
        if (!current.isActive() || BackgroundDownloadService.isServiceAlive()) return false;
        FileDownloads.cleanupInterruptedDestinations(context);
        update(context, State.INTERRUPTED, current.fileName, current.bytesRead, current.totalBytes,
                "Android stopped the transfer before it completed. Retry from the page.", "", current.host,
                null, null, null, -1L);
        return true;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
