package app.githubwebview.locked.net;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import app.githubwebview.locked.MainActivity;
import app.githubwebview.locked.R;
import app.githubwebview.locked.policy.GitHubAllowlist;
import app.githubwebview.locked.policy.PolicyMetrics;
import app.githubwebview.locked.web.EgressGuard;

/** Owns the actual long-running transfer, independently of every Activity instance. */
public final class BackgroundDownloadService extends Service implements FileDownloads.Listener {
    private static final String TAG = "GitHubWebViewLocked";
    private static final String CHANNEL_ID = "background_downloads";
    private static final int NOTIFICATION_ID = 6812;
    private static final String ACTION_START = "app.githubwebview.locked.download.START";
    private static final String ACTION_CANCEL = "app.githubwebview.locked.download.CANCEL";
    private static final String ACTION_RETRY = "app.githubwebview.locked.download.RETRY";
    private static final String EXTRA_URL = "url";
    private static final String EXTRA_DISPOSITION = "disposition";
    private static final String EXTRA_MIME = "mime";
    private static final String EXTRA_LENGTH = "length";
    private static volatile boolean serviceAlive;

    private Handler main;
    private FileDownloads downloads;
    private NotificationManager notifications;
    private DownloadStateStore.RetryRequest request;
    private String fileName = "Download";
    private long bytesRead;
    private long totalBytes = -1L;
    private boolean foreground;
    private boolean transferActive;
    private boolean destroying;
    private int latestStartId;

    public static boolean isServiceAlive() { return serviceAlive; }

    public static boolean start(Context context, String url, String disposition, String mime, long length) {
        if (serviceAlive && DownloadStateStore.read(context).isActive()) return false;
        Intent intent = new Intent(context, BackgroundDownloadService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_DISPOSITION, disposition)
                .putExtra(EXTRA_MIME, mime)
                .putExtra(EXTRA_LENGTH, length);
        try {
            startService(context, intent);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "background download service could not be started", e);
            return false;
        }
    }

    public static boolean cancel(Context context) {
        if (!serviceAlive) return false;
        try {
            startService(context, new Intent(context, BackgroundDownloadService.class).setAction(ACTION_CANCEL));
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "could not signal download cancellation", e);
            return false;
        }
    }

    public static boolean retry(Context context) {
        try {
            startService(context, new Intent(context, BackgroundDownloadService.class).setAction(ACTION_RETRY));
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "could not request download retry", e);
            return false;
        }
    }

    private static void startService(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && ACTION_START.equals(intent.getAction())) {
            app.startForegroundService(intent);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && serviceAlive) {
            app.startService(intent);
        } else {
            app.startForegroundService(intent);
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        serviceAlive = true;
        main = new Handler(Looper.getMainLooper());
        notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createChannel();
        downloads = new FileDownloads(getApplicationContext(), GitHubAllowlist.productionConfig(),
                PolicyMetrics.get(), main, this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            if (downloads != null) downloads.cancel();
            return START_NOT_STICKY;
        }
        if (ACTION_RETRY.equals(action)) {
            request = DownloadStateStore.retryRequest(this);
            if (request == null) {
                finishWithoutTransfer(DownloadStateStore.State.FAILED, "No retryable download is available.");
                return START_NOT_STICKY;
            }
            if (EgressGuard.isReady()) {
                begin(request.url, request.disposition, request.mimeType, request.contentLength, startId);
            } else {
                // A notification retry can start a fresh process. Reinstall the exact same guard
                // before touching the saved URL; no direct-network fallback is introduced.
                try {
                    startForegroundCompat(buildNotification("Preparing retry", 0L, -1L, true));
                    foreground = true;
                } catch (RuntimeException e) {
                    finishWithoutTransfer(DownloadStateStore.State.FAILED,
                            "Background retry could not start its notification.");
                    return START_NOT_STICKY;
                }
                EgressGuard.install(GitHubAllowlist.productionConfig(), PolicyMetrics.get(), new EgressGuard.Callback() {
                    @Override public void onReady(int port) {
                        begin(request.url, request.disposition, request.mimeType, request.contentLength, startId);
                    }
                    @Override public void onUnavailable(String reason) {
                        finishWithoutTransfer(DownloadStateStore.State.FAILED,
                                "The network guard could not restart. Nothing was fetched.");
                    }
                });
            }
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action) || intent == null) return START_NOT_STICKY;
        String url = intent.getStringExtra(EXTRA_URL);
        String disposition = intent.getStringExtra(EXTRA_DISPOSITION);
        String mime = intent.getStringExtra(EXTRA_MIME);
        long length = intent.getLongExtra(EXTRA_LENGTH, -1L);
        begin(url, disposition, mime, length, startId);
        return START_NOT_STICKY;
    }

    private void begin(String url, String disposition, String mime, long length, int startId) {
        if (transferActive) return; // one active transfer; never replace its persisted status or notification
        // Promote immediately for every service start path, before validation or policy work.
        try {
            startForegroundCompat(buildNotification("Preparing download", 0L, -1L, true));
            foreground = true;
        } catch (RuntimeException e) {
            Log.e(TAG, "could not start the required download notification", e);
            finishWithoutTransfer(DownloadStateStore.State.FAILED,
                    "Background download could not start because its notification is unavailable.");
            stopSelf(startId);
            return;
        }
        if (url == null || url.isEmpty()) {
            finishWithoutTransfer(DownloadStateStore.State.FAILED, "The download request had no address.");
            stopSelfResult(startId);
            return;
        }
        if (!EgressGuard.isReady() || EgressGuard.port() <= 0) {
            finishWithoutTransfer(DownloadStateStore.State.FAILED,
                    "The network guard is unavailable. Nothing was downloaded.");
            stopSelfResult(startId);
            return;
        }
        request = new DownloadStateStore.RetryRequest(url, disposition, mime, length);
        DownloadStateStore.update(this, DownloadStateStore.State.ACTIVE, "Preparing download", 0L, -1L,
                "", "", "", url, disposition, mime, length);
        fileName = DownloadPlan.plan(url, disposition, mime, GitHubAllowlist.productionConfig()).fileName();
        bytesRead = 0L;
        totalBytes = -1L;
        transferActive = true;
        if (!downloads.download(url, disposition, mime, length)) {
            // FileDownloads reports the exact safe refusal/failure asynchronously to this service.
        }
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Background downloads",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Progress and controls for files saved by the app");
            notifications.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String name, long read, long total, boolean active) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 1, open, pendingFlags());
        builder.setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(name == null || name.isEmpty() ? "Download" : name)
                .setContentText(active ? "Downloading in the background" : notificationText())
                .setContentIntent(content)
                .setOnlyAlertOnce(true)
                .setOngoing(active)
                .setCategory(Notification.CATEGORY_PROGRESS);
        if (active) {
            builder.setProgress(total > 0 && total <= Integer.MAX_VALUE ? (int) total : 0,
                    total > 0 && total <= Integer.MAX_VALUE ? (int) Math.min(read, total) : 0,
                    total <= 0 || total > Integer.MAX_VALUE);
            Intent cancel = new Intent(this, BackgroundDownloadService.class).setAction(ACTION_CANCEL);
            PendingIntent cancelPending = PendingIntent.getService(this, 2, cancel, pendingFlags());
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPending);
        } else if (DownloadStateStore.read(this).state == DownloadStateStore.State.FAILED
                || DownloadStateStore.read(this).state == DownloadStateStore.State.INTERRUPTED) {
            Intent retry = new Intent(this, BackgroundDownloadService.class).setAction(ACTION_RETRY);
            PendingIntent retryPending = PendingIntent.getService(this, 3, retry, pendingFlags());
            builder.addAction(android.R.drawable.ic_menu_rotate, "Retry", retryPending);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    private int pendingFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    private String notificationText() {
        DownloadStateStore.Snapshot state = DownloadStateStore.read(this);
        switch (state.state) {
            case COMPLETE: return "Download complete";
            case CANCELLED: return "Download cancelled; partial file removed";
            case REFUSED: return "Download refused by policy";
            default: return "Download failed. Retry from the app.";
        }
    }

    private void publish(DownloadStateStore.State state, String message, String location, String host) {
        DownloadStateStore.update(this, state, fileName, bytesRead, totalBytes,
                message, location, host, null, null, null, -1L);
        if (notifications != null) notifications.notify(NOTIFICATION_ID,
                buildNotification(fileName, bytesRead, totalBytes, state == DownloadStateStore.State.ACTIVE));
        if (state != DownloadStateStore.State.ACTIVE) {
            transferActive = false;
            if (foreground) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_DETACH);
                else stopForeground(false);
                foreground = false;
            }
            stopSelfResult(latestStartId);
        }
    }

    private void finishWithoutTransfer(DownloadStateStore.State state, String message) {
        transferActive = false;
        DownloadStateStore.update(this, state, fileName, bytesRead, totalBytes,
                message, "", "", null, null, null, -1L);
        if (notifications != null) notifications.notify(NOTIFICATION_ID,
                buildNotification(fileName, bytesRead, totalBytes, false));
        if (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_DETACH);
        foreground = false;
        stopSelfResult(latestStartId);
    }

    @Override public void onDownloadRefused(String url, String host, String reason) {
        if (destroying) return;
        publish(DownloadStateStore.State.REFUSED, "This file is not permitted by the download policy.", "", host);
    }

    @Override public void onDownloadStarted(String fileName, long totalBytes) {
        if (destroying) return;
        this.fileName = fileName;
        this.totalBytes = totalBytes;
        bytesRead = 0L;
        publish(DownloadStateStore.State.ACTIVE, "", "", "");
    }

    @Override public void onDownloadProgress(String fileName, long bytesRead, long totalBytes) {
        if (destroying) return;
        this.fileName = fileName;
        this.bytesRead = bytesRead;
        this.totalBytes = totalBytes;
        publish(DownloadStateStore.State.ACTIVE, "", "", "");
    }

    @Override public void onDownloadSaved(String fileName, String location) {
        if (destroying) return;
        this.fileName = fileName;
        publish(DownloadStateStore.State.COMPLETE, "Download complete", location, "");
    }

    @Override public void onDownloadFailed(String fileName, String reason) {
        if (destroying) return;
        this.fileName = fileName;
        publish(DownloadStateStore.State.FAILED, "The download failed. Retry from the GitHub page.", "", "");
    }

    @Override public void onDownloadCancelled(String fileName) {
        if (destroying) return;
        this.fileName = fileName;
        publish(DownloadStateStore.State.CANCELLED, "Download cancelled; the partial file was removed.", "", "");
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        destroying = true;
        if (DownloadStateStore.read(this).isActive()) {
            DownloadStateStore.Snapshot state = DownloadStateStore.read(this);
            DownloadStateStore.update(this, DownloadStateStore.State.INTERRUPTED, state.fileName,
                    state.bytesRead, state.totalBytes,
                    "Android stopped the transfer before it completed. Retry from the page.", "", state.host,
                    null, null, null, -1L);
        }
        if (downloads != null) downloads.shutdown();
        serviceAlive = false;
        super.onDestroy();
    }
}
