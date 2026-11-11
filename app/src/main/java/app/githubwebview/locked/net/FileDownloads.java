package app.githubwebview.locked.net;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebSettings;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

import app.githubwebview.locked.policy.PolicyConfig;
import app.githubwebview.locked.policy.PolicyMetrics;
import app.githubwebview.locked.web.EgressGuard;

/**
 * Fetches a file the user asked to download, and saves it - inside the app.
 *
 * <p><b>Where the bytes come from.</b> Not from the WebView: a {@code DownloadListener} only reports that
 * a download started, so the app fetches the URL itself. That fetch is deliberately <em>not</em> a
 * second, unguarded network path. It is opened through the same in-app loopback CONNECT proxy the WebView
 * uses ({@link EgressGuard#port()}), so every hop is still checked by
 * {@link app.githubwebview.locked.policy.PolicyConfig#evaluateTunnel(String, int)}: allow-listed host,
 * port 443, no direct fallback. On top of that, each URL - including every redirect target - is
 * re-validated with {@link DownloadPlan} before it is opened, so a redirect into an unapproved host is
 * refused by the app before the proxy would have to refuse it too. The redirect chain is followed by
 * hand ({@link DownloadPlan#MAX_REDIRECTS} at most) rather than by {@code HttpURLConnection}, so no hop
 * can be invisible to the policy.</p>
 *
 * <p><b>Where the bytes go.</b> Android 10+ writes into the system Downloads collection through
 * {@code MediaStore}, which needs no storage permission at all. Android 7-9 writes into the app's own
 * external Downloads folder, which also needs no permission (the platform grants an app its own
 * directories). The app never asks for a storage permission, never uses the platform download manager,
 * never starts another activity and never hands the file to another app.</p>
 *
 * <p><b>What it will not do.</b> No {@code blob:}/{@code data:} URLs (they would need a JavaScript
 * bridge), no files above {@link DownloadPlan#MAX_BYTES}, no partial file left behind on failure or
 * cancel, and no cookies sent anywhere except a URL that has just passed the policy.</p>
 */
public final class FileDownloads {

    private static final String TAG = "GitHubWebViewLocked";

    /** How often progress is reported to the UI while copying. */
    private static final long PROGRESS_INTERVAL_MS = 250L;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int BUFFER_BYTES = 64 * 1024;

    /** Callbacks. Every one is delivered on the main thread. */
    /** A facade can hand accepted requests to a lifecycle-independent Android component. */
    public interface BackgroundStarter {
        boolean start(String url, String contentDisposition, String mimeType, long contentLength);
        void cancel();
    }

    public interface Listener {
        /** The policy refused the URL. Nothing was opened, fetched or written. */
        void onDownloadRefused(String url, String host, String reason);

        /** Bytes are about to be fetched. {@code totalBytes} is -1 when the server did not say. */
        void onDownloadStarted(String fileName, long totalBytes);

        void onDownloadProgress(String fileName, long bytesRead, long totalBytes);

        /** The file is complete and closed. {@code location} is user-facing ("Downloads" or a path). */
        void onDownloadSaved(String fileName, String location);

        /** The transfer failed; nothing was left on disk. */
        void onDownloadFailed(String fileName, String reason);

        /** The user cancelled; the partial file was deleted. */
        void onDownloadCancelled(String fileName);
    }

    private final Context context;
    private final PolicyConfig policy;
    private final PolicyMetrics metrics;
    private final Handler ui;
    private final Listener listener;
    private final BackgroundStarter backgroundStarter;
    private final ExecutorService worker;

    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicReference<HttpURLConnection> open = new AtomicReference<>();

    public FileDownloads(Context context, PolicyConfig policy, PolicyMetrics metrics, Handler ui,
                         Listener listener) {
        this(context, policy, metrics, ui, listener, null);
    }

    /** Client facade used by the Activity; the service, not this facade, owns the transfer worker. */
    public FileDownloads(Context context, PolicyConfig policy, PolicyMetrics metrics, Handler ui,
                         Listener listener, BackgroundStarter backgroundStarter) {
        if (context == null || policy == null || metrics == null || ui == null || listener == null) {
            throw new IllegalArgumentException("downloads need a context, policy, metrics, handler and listener");
        }
        this.context = context.getApplicationContext() == null ? context : context.getApplicationContext();
        this.policy = policy;
        this.metrics = metrics;
        this.ui = ui;
        this.listener = listener;
        this.backgroundStarter = backgroundStarter;
        this.worker = backgroundStarter == null ? Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "github-download");
            thread.setDaemon(true);
            return thread;
        }) : null;
    }

    /**
     * Start (or refuse) a download. Returns true when a transfer was started.
     *
     * <p>The policy decision is synchronous, so the caller knows immediately; the transfer itself runs on
     * a worker thread and reports through {@link Listener}.</p>
     */
    public boolean download(String url, String contentDisposition, String mimeType, long contentLength) {
        DownloadPlan.Plan plan = DownloadPlan.plan(url, contentDisposition, mimeType, policy);
        if (!plan.isAllowed()) {
            refused(plan);
            return false;
        }
        if (DownloadPlan.tooLarge(contentLength)) {
            refused(DownloadPlan.tooLarge(plan));
            return false;
        }
        if (EgressGuard.port() <= 0) {
            // Fail closed: without the loopback guard there is no vetted path to the network, and this
            // class never opens a direct connection.
            Log.w(TAG, "refused download: the egress guard is not running");
            postFailed(plan.fileName(), "the network guard is not running");
            return false;
        }
        if (backgroundStarter != null) {
            // This client facade never owns work. The Activity reports a synchronous failure if
            // notification permission or foreground-service startup prevents accepting the request.
            return backgroundStarter.start(url, contentDisposition, mimeType, contentLength);
        }
        if (!busy.compareAndSet(false, true)) {
            postFailed(plan.fileName(), "another download is already running");
            return false;
        }
        cancelled.set(false);
        try {
            worker.execute(() -> transfer(plan));
        } catch (RejectedExecutionException e) {
            busy.set(false);
            postFailed(plan.fileName(), "the download worker is not available");
            return false;
        }
        return true;
    }

    /** Cancel the running transfer. The partial file is deleted; a no-op when nothing is running. */
    public void cancel() {
        if (backgroundStarter != null) {
            backgroundStarter.cancel();
            return;
        }
        cancelled.set(true);
        HttpURLConnection connection = open.getAndSet(null);
        if (connection != null) {
            try {
                connection.disconnect();
            } catch (Throwable ignored) {
                // disconnecting a dead connection is not an error worth reporting
            }
        }
    }

    /** Stop the worker when the activity goes away. Idempotent. */
    public void shutdown() {
        // The Activity-facing facade deliberately owns no worker and must never cancel the service.
        if (backgroundStarter != null) return;
        cancel();
        worker.shutdownNow();
    }

    // ---------------------------------------------------------------------------------------------
    // The transfer
    // ---------------------------------------------------------------------------------------------

    private void transfer(DownloadPlan.Plan initial) {
        String currentUrl = initial.url();
        String name = initial.fileName();
        String disposition = initial.contentDisposition();
        String pageMime = initial.mimeType();
        Destination destination = null;
        boolean saved = false;
        try {
            for (int hop = 0; hop <= DownloadPlan.MAX_REDIRECTS; hop++) {
                checkCancelled(name);
                // Every hop, including the original URL, is asked the policy question again.
                DownloadPlan.Plan hopPlan = DownloadPlan.plan(currentUrl, disposition, pageMime, policy);
                if (!hopPlan.isAllowed()) {
                    throw new RefusedException(hopPlan);
                }
                HttpURLConnection connection = open(hopPlan.url());
                try {
                    int status = connection.getResponseCode();
                    if (DownloadPlan.isRedirect(status)) {
                        String location = connection.getHeaderField("Location");
                        if (location == null || location.trim().isEmpty()) {
                            throw new IOException("HTTP " + status + " without a location");
                        }
                        // Resolved against the URL that produced it, then re-checked at the top of the loop.
                        currentUrl = new URL(new URL(hopPlan.url()), location.trim()).toString();
                        continue;
                    }
                    if (status != HttpURLConnection.HTTP_OK) {
                        throw new IOException("the server answered HTTP " + status);
                    }
                    long declared = connection.getContentLengthLong();
                    if (DownloadPlan.tooLarge(declared)) {
                        refused(DownloadPlan.tooLarge(hopPlan));
                        return;
                    }
                    String responseDisposition = connection.getHeaderField("Content-Disposition");
                    String mime = DownloadPlan.mimeType(connection.getContentType(), pageMime);
                    name = DownloadPlan.fileName(
                            responseDisposition != null ? responseDisposition : disposition,
                            hopPlan.url(), mime);
                    postStarted(name, declared);
                    destination = openDestination(name, mime);
                    long written = copy(connection.getInputStream(), destination.output(), name, declared);
                    checkCancelled(name);
                    destination.commit();
                    saved = true;
                    Log.i(TAG, "saved download " + name + " (" + written + " bytes) via " + hopPlan.host());
                    postSaved(name, destination.location());
                    return;
                } finally {
                    open.set(null);
                    connection.disconnect();
                }
            }
            throw new IOException("too many redirects");
        } catch (RefusedException e) {
            refused(e.plan);
        } catch (CancelledException e) {
            Log.i(TAG, "download cancelled: " + e.fileName);
            postCancelled(e.fileName);
        } catch (IOException | RuntimeException e) {
            if (cancelled.get()) {
                // A cancel closes the stream under the reader, which surfaces as an IOException. Report it
                // as the cancel the user asked for, not as a failure.
                Log.i(TAG, "download cancelled while reading: " + name);
                postCancelled(name);
            } else {
                Log.w(TAG, "download failed: " + name, e);
                postFailed(name, describeFailure(e));
            }
        } finally {
            if (!saved && destination != null) {
                destination.abort();
            }
            busy.set(false);
        }
    }

    /** Opens one hop through the loopback guard. There is no direct-connection branch on purpose. */
    private HttpURLConnection open(String url) throws IOException {
        int port = EgressGuard.port();
        if (port <= 0) {
            throw new IOException("the network guard is not running");
        }
        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(EgressProxy.LOOPBACK_HOST, port));
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection(proxy);
        open.set(connection);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("Accept", "*/*");
        connection.setRequestProperty("User-Agent", userAgent());
        // A repository file behind a private session only exists for the signed-in user, so the same
        // cookies the WebView holds are sent - and only to a URL that has just passed the policy.
        String cookie = cookieFor(url);
        if (cookie != null && !cookie.isEmpty()) {
            connection.setRequestProperty("Cookie", cookie);
        }
        return connection;
    }

    /** The same identity the WebView sends; this file never impersonates a different browser. */
    private String userAgent() {
        try {
            return WebSettings.getDefaultUserAgent(context);
        } catch (Throwable ignored) {
            try {
                return System.getProperty("http.agent", "Android");
            } catch (Throwable alsoIgnored) {
                return "Android";
            }
        }
    }

    private static String cookieFor(String url) {
        try {
            return CookieManager.getInstance().getCookie(url);
        } catch (Throwable ignored) {
            return null; // a public download still works without cookies
        }
    }

    /** Copies the stream, enforcing the size ceiling and reporting progress to the UI. */
    private long copy(InputStream rawIn, OutputStream rawOut, String name, long declared) throws IOException {
        InputStream in = new BufferedInputStream(rawIn, BUFFER_BYTES);
        OutputStream out = new BufferedOutputStream(rawOut, BUFFER_BYTES);
        byte[] buffer = new byte[BUFFER_BYTES];
        long total = 0L;
        long lastReport = 0L;
        try {
            int read;
            while ((read = in.read(buffer)) != -1) {
                checkCancelled(name);
                total += read;
                if (DownloadPlan.tooLarge(total)) {
                    throw new IOException("the download exceeded the "
                            + (DownloadPlan.MAX_BYTES / (1024L * 1024L)) + " MB limit");
                }
                out.write(buffer, 0, read);
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                    lastReport = now;
                    postProgress(name, total, declared);
                }
            }
            out.flush();
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
        postProgress(name, total, declared);
        return total;
    }

    /** Remove files/rows left pending if Android killed the app process during a transfer. */
    public static void cleanupInterruptedDestinations(Context context) {
        if (context == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            String[] projection = {MediaStore.Downloads._ID};
            try (Cursor cursor = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection,
                    MediaStore.Downloads.IS_PENDING + "=1", null, null)) {
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        Uri item = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                String.valueOf(cursor.getLong(0)));
                        resolver.delete(item, null, null);
                    }
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "could not clean interrupted pending Downloads rows", e);
            }
        }
        File folder = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (folder == null) folder = new File(context.getFilesDir(), "downloads");
        File[] partials = folder.listFiles((dir, name) -> name.startsWith(".github-download-")
                && name.endsWith(".part"));
        if (partials != null) {
            for (File partial : partials) {
                if (partial.isFile() && !partial.delete()) {
                    Log.w(TAG, "could not remove interrupted partial download " + partial.getName());
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Where it is saved
    // ---------------------------------------------------------------------------------------------

    /** One open destination: write here, then commit or abort. Aborting never leaves a partial file. */
    private interface Destination {
        OutputStream output();

        void commit();

        void abort();

        /** User-facing location ("Downloads", or a path on older Android). */
        String location();
    }

    private Destination openDestination(String name, String mimeType) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return MediaStoreDestination.open(context, name, mimeType);
        }
        return AppFolderDestination.open(context, name);
    }

    /**
     * Android 10+: the system Downloads collection through {@code MediaStore}.
     *
     * <p>The row is inserted as pending, so the file is invisible to other apps until it is complete -
     * and deleted outright if the transfer fails, which is the whole point of pending.</p>
     */
    private static final class MediaStoreDestination implements Destination {
        private final ContentResolver resolver;
        private final Uri item;
        private final OutputStream output;
        private final String name;

        private MediaStoreDestination(ContentResolver resolver, Uri item, OutputStream output, String name) {
            this.resolver = resolver;
            this.item = item;
            this.output = output;
            this.name = name;
        }

        static Destination open(Context context, String name, String mimeType) throws IOException {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri item;
            try {
                item = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            } catch (RuntimeException e) {
                throw new IOException("the Downloads folder is not available", e);
            }
            if (item == null) {
                throw new IOException("the Downloads folder refused the file");
            }
            OutputStream output = null;
            try {
                output = resolver.openOutputStream(item);
            } catch (RuntimeException e) {
                resolver.delete(item, null, null);
                throw new IOException("could not write to the Downloads folder", e);
            }
            if (output == null) {
                resolver.delete(item, null, null);
                throw new IOException("could not write to the Downloads folder");
            }
            return new MediaStoreDestination(resolver, item, output, name);
        }

        @Override
        public OutputStream output() {
            return output;
        }

        @Override
        public void commit() {
            closeQuietly(output);
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            try {
                int updated = resolver.update(item, values, null, null);
                if (updated != 1) {
                    throw new IllegalStateException("the Downloads row could not be published");
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "could not finish the MediaStore row", e);
                throw e;
            }
        }

        @Override
        public void abort() {
            closeQuietly(output);
            try {
                resolver.delete(item, null, null);
            } catch (RuntimeException e) {
                Log.w(TAG, "could not remove the partial download", e);
            }
        }

        @Override
        public String location() {
            return "Downloads (" + name + ")";
        }
    }

    /**
     * Android 7-9: the app's own external Downloads folder, which needs no storage permission.
     *
     * <p>Files there are visible over USB and to file managers as {@code Android/data/<package>/files},
     * which is honest but not the shared Downloads collection - that one would require
     * {@code WRITE_EXTERNAL_STORAGE}, and this app declares no storage permission at all.</p>
     */
    private static final class AppFolderDestination implements Destination {
        private final File file;
        private final File partial;
        private final OutputStream output;

        private AppFolderDestination(File file, File partial, OutputStream output) {
            this.file = file;
            this.partial = partial;
            this.output = output;
        }

        static Destination open(Context context, String name) throws IOException {
            File folder = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (folder == null) {
                folder = new File(context.getFilesDir(), "downloads");
            }
            if (!folder.isDirectory() && !folder.mkdirs()) {
                throw new IOException("could not create the app's Downloads folder");
            }
            File file = unique(folder, name);
            File partial = new File(folder, ".github-download-" + UUID.randomUUID() + ".part");
            OutputStream output = null;
            try {
                output = new FileOutputStream(partial);
            } catch (IOException e) {
                throw new IOException("could not create a temporary download file", e);
            }
            return new AppFolderDestination(file, partial, output);
        }

        private static File unique(File folder, String name) {
            File candidate = new File(folder, name);
            if (!candidate.exists()) {
                return candidate;
            }
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            String extension = dot > 0 ? name.substring(dot) : "";
            for (int i = 1; i < 1000; i++) {
                candidate = new File(folder, base + " (" + i + ")" + extension);
                if (!candidate.exists()) {
                    return candidate;
                }
            }
            return candidate;
        }

        @Override
        public OutputStream output() {
            return output;
        }

        @Override
        public void commit() {
            closeQuietly(output);
            if (!partial.renameTo(file)) {
                throw new IllegalStateException("could not publish the completed download");
            }
        }

        @Override
        public void abort() {
            closeQuietly(output);
            if (partial.exists() && !partial.delete()) {
                Log.w(TAG, "could not delete the partial download " + partial.getName());
            }
        }

        @Override
        public String location() {
            return file.getAbsolutePath();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Callbacks, refusals and small helpers
    // ---------------------------------------------------------------------------------------------

    private void refused(DownloadPlan.Plan plan) {
        metrics.increment(PolicyMetrics.Counter.BLOCKED_DOWNLOADS);
        metrics.recordBlock(plan.reason(), plan.host(), plan.url());
        Log.i(TAG, "refused download: " + plan.refusal() + " host=" + plan.host());
        final String url = plan.url();
        final String host = plan.host();
        final String reason = plan.describe();
        ui.post(() -> listener.onDownloadRefused(url, host, reason));
    }

    private void checkCancelled(String name) throws CancelledException {
        if (cancelled.get()) {
            throw new CancelledException(name);
        }
    }

    private static String describeFailure(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "the transfer did not complete";
        }
        return message;
    }

    private void postStarted(final String name, final long total) {
        ui.post(() -> listener.onDownloadStarted(name, total));
    }

    private void postProgress(final String name, final long read, final long total) {
        ui.post(() -> listener.onDownloadProgress(name, read, total));
    }

    private void postSaved(final String name, final String location) {
        ui.post(() -> listener.onDownloadSaved(name, location));
    }

    private void postFailed(final String name, final String reason) {
        ui.post(() -> listener.onDownloadFailed(name, reason));
    }

    private void postCancelled(final String name) {
        ui.post(() -> listener.onDownloadCancelled(name));
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable ignored) {
            // closing twice, or a stream that already failed, is not an error worth reporting
        }
    }

    /** A redirect hop that the policy refused. Carries the plan so the UI gets the real reason. */
    private static final class RefusedException extends IOException {
        private final DownloadPlan.Plan plan;

        RefusedException(DownloadPlan.Plan plan) {
            super("refused: " + plan.refusal());
            this.plan = plan;
        }
    }

    private static final class CancelledException extends IOException {
        private final String fileName;

        CancelledException(String fileName) {
            super("cancelled");
            this.fileName = fileName;
        }
    }
}
