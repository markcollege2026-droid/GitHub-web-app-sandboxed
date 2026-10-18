package app.arenawebview.locked.net;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import app.arenawebview.locked.policy.PolicyConfig;
import app.arenawebview.locked.policy.PolicyMetrics;
import app.arenawebview.locked.policy.PolicyResult;

/**
 * The single point every byte the WebView sends leaves the app through.
 *
 * <p>The WebView's URL hooks ({@code shouldOverrideUrlLoading}, {@code shouldInterceptRequest}, the
 * service-worker client) see navigations and sub-resources, but not WebSockets and not anything Chromium's
 * network stack opens on its own. A proxy sees everything: with {@code ProxyController} pointing the
 * WebView at this loopback listener, Chromium asks it to open a tunnel ({@code CONNECT host:443}) for
 * <em>every</em> TLS connection - HTTPS, WSS, HTTP/2, whatever comes next - and this class only opens the
 * tunnel when {@link PolicyConfig#evaluateTunnel(String, int)} allows the host. The TLS session inside
 * the tunnel is end-to-end between Chromium and the site: nothing here can read it, and nothing here
 * tries to. Certificate validation stays Chromium's.</p>
 *
 * <p>What the proxy does <em>not</em> do: it does not inspect, rewrite, cache or log payloads; it does
 * not accept anything but {@code CONNECT}; it never falls back to a direct connection; it never tunnels
 * to a port other than 443. A refused tunnel is answered with {@code 403} and closed, and counted in
 * {@link PolicyMetrics} like every other refusal so it shows up in the diagnostics report.</p>
 *
 * <p>Plain Java on purpose: it is tested on the JVM with a fake upstream ({@link Dialer}) and runs
 * unchanged on the device. It listens on {@code 127.0.0.1} only. Other apps on the same device can also
 * connect to it; all they get is a tunnel to a host they could reach directly anyway.</p>
 */
public final class EgressProxy implements Closeable {

    /** How a tunnel's far end is opened. The default honours the system HTTP proxy, tests use a fake. */
    public interface Dialer {
        Socket connect(String host, int port) throws IOException;
    }

    /** Diagnostics hooks; everything here is optional and best effort. */
    public interface Listener {
        void onTunnelRefused(String host, int port, PolicyResult result);

        void onTunnelOpened(String host);

        /** Something that was not a well-formed {@code CONNECT} arrived; {@code requestLine} is sanitised. */
        void onBadRequest(String requestLine);

        void onProxyError(String what, Throwable error);
    }

    /** The default port for HTTPS, and the only one a tunnel may ever be opened to. */
    public static final int HTTPS_PORT = 443;

    /**
     * The address the proxy listens on, and the one the WebView is told to connect to. Spelled out as
     * IPv4 on purpose: on Android {@code InetAddress.getLoopbackAddress()} is {@code ::1}, and a proxy
     * rule of {@code 127.0.0.1:port} would then point at a port nobody listens on - every page would fail
     * with {@code ERR_PROXY_CONNECTION_FAILED} (fail-closed, but useless).
     */
    public static final String LOOPBACK_HOST = "127.0.0.1";

    private static final int MAX_HEAD_BYTES = 16 * 1024;
    private static final int HANDSHAKE_TIMEOUT_MS = 15_000;
    private static final int UPSTREAM_CONNECT_TIMEOUT_MS = 20_000;
    private static final int MAX_CONCURRENT_TUNNELS = 128;
    private static final int BUFFER_BYTES = 16 * 1024;

    private static final byte[] RESPONSE_ESTABLISHED =
            ascii("HTTP/1.1 200 Connection established\r\n\r\n");
    private static final byte[] RESPONSE_FORBIDDEN =
            ascii("HTTP/1.1 403 Forbidden\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");
    private static final byte[] RESPONSE_BAD_REQUEST =
            ascii("HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");
    private static final byte[] RESPONSE_BAD_GATEWAY =
            ascii("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");
    private static final byte[] RESPONSE_BUSY =
            ascii("HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");

    private final PolicyConfig policy;
    private final PolicyMetrics metrics;
    private final Dialer dialer;
    private final Listener listener;
    private final ServerSocket server;
    private final ExecutorService workers;
    private final AtomicInteger openTunnels = new AtomicInteger();
    private volatile boolean closed;

    private EgressProxy(PolicyConfig policy, PolicyMetrics metrics, Dialer dialer, Listener listener,
                        ServerSocket server) {
        this.policy = policy;
        this.metrics = metrics;
        this.dialer = dialer;
        this.listener = listener;
        this.server = server;
        this.workers = Executors.newCachedThreadPool(new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "egress-proxy-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
        Thread acceptor = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "egress-proxy-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /** Start listening on an ephemeral loopback port, dialling upstream through the system proxy if any. */
    public static EgressProxy start(PolicyConfig policy, PolicyMetrics metrics, Listener listener) throws IOException {
        return start(policy, metrics, new SystemProxyDialer(), listener);
    }

    /** Start with an explicit upstream dialer (tests). */
    public static EgressProxy start(PolicyConfig policy, PolicyMetrics metrics, Dialer dialer, Listener listener)
            throws IOException {
        if (policy == null || metrics == null || dialer == null) {
            throw new IllegalArgumentException("policy, metrics and dialer are required");
        }
        ServerSocket server = new ServerSocket();
        try {
            server.setReuseAddress(true);
            // Loopback only, port 0 = let the kernel pick one. Never 0.0.0.0.
            server.bind(new InetSocketAddress(loopbackAddress(), 0), 64);
        } catch (IOException e) {
            closeQuietly(server);
            throw e;
        }
        return new EgressProxy(policy, metrics, dialer, listener, server);
    }

    /** {@link #LOOPBACK_HOST} as an address, built without any lookup. */
    public static InetAddress loopbackAddress() throws IOException {
        return InetAddress.getByAddress(LOOPBACK_HOST, new byte[] {127, 0, 0, 1});
    }

    /** The loopback port Chromium must be pointed at (on {@link #LOOPBACK_HOST}). */
    public int port() {
        return server.getLocalPort();
    }

    /** True while the listener is accepting connections. */
    public boolean isRunning() {
        return !closed && !server.isClosed();
    }

    /** Tunnels currently open (diagnostics). */
    public int openTunnelCount() {
        return openTunnels.get();
    }

    @Override
    public void close() {
        closed = true;
        closeQuietly(server);
        workers.shutdownNow();
    }

    // ---------------------------------------------------------------------------------------------
    // Accepting
    // ---------------------------------------------------------------------------------------------

    private void acceptLoop() {
        while (!closed) {
            final Socket client;
            try {
                client = server.accept();
            } catch (IOException e) {
                if (!closed) {
                    report("accept", e);
                }
                break;
            }
            try {
                workers.execute(new Runnable() {
                    @Override
                    public void run() {
                        serve(client);
                    }
                });
            } catch (RuntimeException e) {
                // Executor shut down between the check and the submit: we are closing.
                closeQuietly(client);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // One connection
    // ---------------------------------------------------------------------------------------------

    private void serve(Socket client) {
        Socket upstream = null;
        boolean counted = false;
        try {
            client.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            client.setTcpNoDelay(true);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            Head head = readHead(in);
            if (head == null) {
                respond(out, RESPONSE_BAD_REQUEST);
                return;
            }
            ConnectRequest request = ConnectRequest.parse(head.text);
            if (request == null) {
                // Not CONNECT, or malformed. A plain-text proxy request (GET http://...) would mean
                // cleartext, which the policy and the manifest both refuse anyway.
                metrics.increment(PolicyMetrics.Counter.BLOCKED_TUNNELS);
                if (listener != null) {
                    listener.onBadRequest(printableRequestLine(head.text));
                }
                respond(out, RESPONSE_BAD_REQUEST);
                return;
            }

            PolicyResult decision = policy.evaluateTunnel(request.host, request.port);
            if (!decision.isAllowed()) {
                metrics.increment(PolicyMetrics.Counter.BLOCKED_TUNNELS);
                metrics.recordBlock(decision.reason(), decision.canonicalHost(), decision.rawUrl());
                if (listener != null) {
                    listener.onTunnelRefused(request.host, request.port, decision);
                }
                respond(out, RESPONSE_FORBIDDEN);
                return;
            }

            if (openTunnels.incrementAndGet() > MAX_CONCURRENT_TUNNELS) {
                openTunnels.decrementAndGet();
                respond(out, RESPONSE_BUSY);
                return;
            }
            counted = true;

            try {
                upstream = dialer.connect(decision.canonicalHost(), request.port);
            } catch (IOException e) {
                respond(out, RESPONSE_BAD_GATEWAY);
                return;
            }
            upstream.setTcpNoDelay(true);
            upstream.setKeepAlive(true);
            upstream.setSoTimeout(0);
            client.setSoTimeout(0);
            client.setKeepAlive(true);

            metrics.increment(PolicyMetrics.Counter.TUNNELS_OPENED);
            if (listener != null) {
                listener.onTunnelOpened(decision.canonicalHost());
            }

            out.write(RESPONSE_ESTABLISHED);
            out.flush();
            if (head.leftover.length > 0) {
                // Bytes that arrived in the same packet as the request head belong to the tunnel.
                upstream.getOutputStream().write(head.leftover);
                upstream.getOutputStream().flush();
            }

            // Pump upstream -> client on a second worker, client -> upstream on this one.
            final Socket up = upstream;
            final Socket down = client;
            try {
                workers.execute(new Runnable() {
                    @Override
                    public void run() {
                        pump(up, down);
                    }
                });
            } catch (RejectedExecutionException e) {
                // close() ran between the handshake and the pump: both sockets are closed in finally.
                return;
            }
            pump(down, up);
        } catch (IOException e) {
            // A connection that fails mid-way is simply over; nothing to recover.
        } catch (RuntimeException e) {
            if (!closed) {
                report("serve", e);
            }
        } finally {
            if (counted) {
                openTunnels.decrementAndGet();
            }
            closeQuietly(upstream);
            closeQuietly(client);
        }
    }

    /** Copy bytes from one socket to the other until EOF or error, then half-close the destination. */
    private static void pump(Socket from, Socket to) {
        byte[] buffer = new byte[BUFFER_BYTES];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buffer)) >= 0) {
                if (n > 0) {
                    out.write(buffer, 0, n);
                    out.flush();
                }
            }
            try {
                to.shutdownOutput();
            } catch (IOException ignored) {
                // Already closed by the other direction.
            }
        } catch (IOException e) {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Request parsing
    // ---------------------------------------------------------------------------------------------

    private static final class Head {
        final String text;
        final byte[] leftover;

        Head(String text, byte[] leftover) {
            this.text = text;
            this.leftover = leftover;
        }
    }

    /** Read up to and including the blank line that ends the request head; null if malformed/too long. */
    private static Head readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream(512);
        byte[] buffer = new byte[1024];
        int matched = 0; // how many bytes of "\r\n\r\n" have been seen in a row
        while (true) {
            int n = in.read(buffer);
            if (n < 0) {
                return null;
            }
            for (int i = 0; i < n; i++) {
                byte b = buffer[i];
                head.write(b);
                if (head.size() > MAX_HEAD_BYTES) {
                    return null;
                }
                boolean expectCr = matched == 0 || matched == 2;
                if (b == (expectCr ? '\r' : '\n')) {
                    matched++;
                } else {
                    matched = b == '\r' ? 1 : 0;
                }
                if (matched == 4) {
                    int leftoverLength = n - i - 1;
                    byte[] leftover = new byte[leftoverLength];
                    System.arraycopy(buffer, i + 1, leftover, 0, leftoverLength);
                    return new Head(new String(head.toByteArray(), StandardCharsets.ISO_8859_1), leftover);
                }
            }
        }
    }

    /** A parsed {@code CONNECT host:port HTTP/1.x} request line. Package-private for tests. */
    static final class ConnectRequest {
        final String host;
        final int port;

        private ConnectRequest(String host, int port) {
            this.host = host;
            this.port = port;
        }

        /** @return the request, or null if this is not a well-formed CONNECT request */
        static ConnectRequest parse(String head) {
            if (head == null) {
                return null;
            }
            int eol = head.indexOf("\r\n");
            String line = eol < 0 ? head : head.substring(0, eol);
            String[] parts = line.split(" ");
            if (parts.length != 3 || !"CONNECT".equals(parts[0]) || !parts[2].startsWith("HTTP/1.")) {
                return null;
            }
            String target = parts[1];
            int colon = target.lastIndexOf(':');
            if (colon <= 0 || colon == target.length() - 1) {
                return null;
            }
            String host = target.substring(0, colon);
            String portText = target.substring(colon + 1);
            if (portText.length() > 5) {
                return null;
            }
            for (int i = 0; i < portText.length(); i++) {
                char c = portText.charAt(i);
                if (c < '0' || c > '9') {
                    return null;
                }
            }
            int port = Integer.parseInt(portText);
            if (port < 1 || port > 65535) {
                return null;
            }
            return new ConnectRequest(host, port);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Upstream
    // ---------------------------------------------------------------------------------------------

    /**
     * Opens the far end of a tunnel. If the device's network has an HTTP proxy configured (Wi-Fi proxy
     * settings), the connection is chained through it with a {@code CONNECT} of its own - the override
     * installed in the WebView hides the system proxy from Chromium, so honouring it here is what keeps
     * the app working on such networks. There is no other path: a failure here is a failed connection,
     * never a direct one from Chromium.
     */
    public static final class SystemProxyDialer implements Dialer {
        @Override
        public Socket connect(String host, int port) throws IOException {
            InetSocketAddress systemProxy = systemHttpProxy(host, port);
            if (systemProxy == null) {
                Socket socket = new Socket(Proxy.NO_PROXY);
                socket.connect(new InetSocketAddress(host, port), UPSTREAM_CONNECT_TIMEOUT_MS);
                return socket;
            }
            Socket socket = new Socket(Proxy.NO_PROXY);
            try {
                socket.connect(systemProxy, UPSTREAM_CONNECT_TIMEOUT_MS);
                socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
                OutputStream out = socket.getOutputStream();
                out.write(ascii("CONNECT " + host + ":" + port + " HTTP/1.1\r\nHost: " + host + ":" + port
                        + "\r\nProxy-Connection: keep-alive\r\n\r\n"));
                out.flush();
                Head reply = readHead(socket.getInputStream());
                if (reply == null || !isSuccess(reply.text)) {
                    throw new IOException("upstream proxy refused the tunnel");
                }
                if (reply.leftover.length > 0) {
                    // Nothing may precede the TLS handshake from the far side.
                    throw new IOException("unexpected bytes after the upstream proxy reply");
                }
                socket.setSoTimeout(0);
                return socket;
            } catch (IOException e) {
                closeQuietly(socket);
                throw e;
            }
        }

        private static boolean isSuccess(String replyHead) {
            int eol = replyHead.indexOf("\r\n");
            String line = eol < 0 ? replyHead : replyHead.substring(0, eol);
            return line.startsWith("HTTP/1.") && line.length() >= 12 && line.charAt(8) == ' '
                    && line.charAt(9) == '2';
        }

        private static InetSocketAddress systemHttpProxy(String host, int port) {
            try {
                ProxySelector selector = ProxySelector.getDefault();
                if (selector == null) {
                    return null;
                }
                List<Proxy> proxies = selector.select(URI.create("https://" + host + ":" + port + "/"));
                if (proxies == null) {
                    return null;
                }
                for (Proxy proxy : proxies) {
                    if (proxy != null && proxy.type() == Proxy.Type.HTTP
                            && proxy.address() instanceof InetSocketAddress) {
                        return (InetSocketAddress) proxy.address();
                    }
                }
            } catch (RuntimeException ignored) {
                // No usable selector: connect directly.
            }
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private void report(String what, Throwable error) {
        if (listener != null) {
            try {
                listener.onProxyError(what, error);
            } catch (RuntimeException ignored) {
                // A diagnostics hook must never take the proxy down.
            }
        }
    }

    /** First line of a request head, control characters replaced, capped - safe to log. */
    static String printableRequestLine(String head) {
        if (head == null) {
            return "";
        }
        int eol = head.indexOf('\n');
        String line = eol < 0 ? head : head.substring(0, eol);
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        StringBuilder sb = new StringBuilder(Math.min(line.length(), 160));
        for (int i = 0; i < line.length() && sb.length() < 160; i++) {
            char c = line.charAt(i);
            sb.append(c < 0x20 || c > 0x7e ? '?' : c);
        }
        return sb.toString();
    }

    private static void respond(OutputStream out, byte[] response) {
        try {
            out.write(response);
            out.flush();
        } catch (IOException ignored) {
            // The client went away; nothing to tell it.
        }
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException | RuntimeException ignored) {
            // ignore
        }
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "EgressProxy{127.0.0.1:%d, open=%d, running=%s}",
                port(), openTunnels.get(), isRunning());
    }
}
