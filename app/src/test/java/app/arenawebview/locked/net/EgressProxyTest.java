package app.arenawebview.locked.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import app.arenawebview.locked.policy.GitHubAllowlist;
import app.arenawebview.locked.policy.BlockReason;
import app.arenawebview.locked.policy.PolicyConfig;
import app.arenawebview.locked.policy.PolicyMetrics;
import app.arenawebview.locked.policy.PolicyResult;

/**
 * The egress proxy on real loopback sockets, with a fake upstream so no network is needed.
 *
 * <p>What is asserted is the contract Chromium relies on and the contract the security argument relies
 * on: an allowed {@code CONNECT} gets {@code 200} and a transparent byte pipe in both directions; every
 * refused one gets {@code 403} without any upstream connection being attempted; anything that is not a
 * well-formed {@code CONNECT} is rejected; and every refusal is counted and recorded.</p>
 */
public class EgressProxyTest {

    private static final PolicyConfig POLICY = GitHubAllowlist.productionConfig();

    /** A local "far end" that answers every byte with its complement, so integrity in both directions is visible. */
    private ServerSocket echoServer;
    private final List<String> dialled = Collections.synchronizedList(new ArrayList<String>());
    private final List<PolicyResult> refused = Collections.synchronizedList(new ArrayList<PolicyResult>());
    private final List<Throwable> proxyErrors = Collections.synchronizedList(new ArrayList<Throwable>());
    private final List<String> badRequests = Collections.synchronizedList(new ArrayList<String>());
    private PolicyMetrics metrics;
    private EgressProxy proxy;

    @Before
    public void setUp() throws Exception {
        echoServer = new ServerSocket();
        echoServer.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        Thread echo = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!echoServer.isClosed()) {
                    try {
                        final Socket s = echoServer.accept();
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    InputStream in = s.getInputStream();
                                    OutputStream out = s.getOutputStream();
                                    byte[] buf = new byte[1024];
                                    int n;
                                    while ((n = in.read(buf)) >= 0) {
                                        // Reply with the complement of every byte, so a reply proves the
                                        // bytes went through the far end rather than being reflected.
                                        for (int i = 0; i < n; i++) {
                                            buf[i] = (byte) ~buf[i];
                                        }
                                        out.write(buf, 0, n);
                                        out.flush();
                                    }
                                    s.close();
                                } catch (IOException ignored) {
                                    // done
                                }
                            }
                        }).start();
                    } catch (IOException e) {
                        return;
                    }
                }
            }
        });
        echo.setDaemon(true);
        echo.start();

        metrics = new PolicyMetrics();
        EgressProxy.Dialer fakeUpstream = new EgressProxy.Dialer() {
            @Override
            public Socket connect(String host, int port) throws IOException {
                dialled.add(host + ":" + port);
                if ("api.github.com".equals(host)) {
                    throw new IOException("simulated unreachable host");
                }
                return new Socket(echoServer.getInetAddress(), echoServer.getLocalPort());
            }
        };
        proxy = EgressProxy.start(POLICY, metrics, fakeUpstream, new EgressProxy.Listener() {
            @Override
            public void onTunnelRefused(String host, int port, PolicyResult result) {
                refused.add(result);
            }

            @Override
            public void onTunnelOpened(String host) {
            }

            @Override
            public void onBadRequest(String requestLine) {
                badRequests.add(requestLine);
            }

            @Override
            public void onProxyError(String what, Throwable error) {
                proxyErrors.add(new AssertionError(what, error));
            }
        });
    }

    @After
    public void tearDown() throws Exception {
        if (proxy != null) {
            proxy.close();
        }
        if (echoServer != null) {
            echoServer.close();
        }
        assertTrue("proxy reported errors: " + proxyErrors, proxyErrors.isEmpty());
    }

    @Test
    public void listensOnIpv4LoopbackOnlyOnAnEphemeralPort() throws Exception {
        assertTrue(proxy.port() > 0);
        assertTrue(proxy.isRunning());
        assertEquals(0, proxy.openTunnelCount());
        assertEquals("127.0.0.1", EgressProxy.LOOPBACK_HOST);
        assertEquals("127.0.0.1", EgressProxy.loopbackAddress().getHostAddress());
        // The address Chromium is given is the one that answers (open() uses the literal 127.0.0.1).
        try (Socket client = open()) {
            assertTrue(client.isConnected());
        }
        // ... and the IPv6 loopback does not: the listener is not dual-stack / wildcard.
        try (Socket v6 = new Socket()) {
            v6.connect(new InetSocketAddress(InetAddress.getByName("::1"), proxy.port()), 1000);
            fail("the proxy must not be reachable on ::1");
        } catch (IOException expected) {
            // refused (or no IPv6 loopback on this machine): either way not reachable
        }
    }

    @Test
    public void allowedHostGetsATransparentTunnel() throws Exception {
        try (Socket client = open()) {
            String status = send(client, "CONNECT github.com:443 HTTP/1.1\r\nHost: github.com:443\r\n\r\n");
            assertEquals("HTTP/1.1 200 Connection established", status);

            OutputStream out = client.getOutputStream();
            out.write("hello".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            assertEquals("hello", complement(readBytes(client.getInputStream(), 5)));

            // Binary-safe and unmodified in both directions, across many segments.
            byte[] blob = new byte[70_000];
            for (int i = 0; i < blob.length; i++) {
                blob[i] = (byte) (i * 31);
            }
            out.write(blob);
            out.flush();
            byte[] back = readBytes(client.getInputStream(), blob.length);
            for (int i = 0; i < blob.length; i++) {
                assertEquals("byte " + i, blob[i], (byte) ~back[i]);
            }
        }
        assertEquals(Collections.singletonList("github.com:443"), dialled);
        assertEquals(1, metrics.count(PolicyMetrics.Counter.TUNNELS_OPENED));
        assertEquals(0, metrics.count(PolicyMetrics.Counter.BLOCKED_TUNNELS));
    }

    @Test
    public void hostIsCanonicalisedBeforeItIsDialled() throws Exception {
        try (Socket client = open()) {
            assertEquals("HTTP/1.1 200 Connection established",
                    send(client, "CONNECT GITHUB.GITHUBASSETS.COM:443 HTTP/1.1\r\n\r\n"));
        }
        assertEquals(Collections.singletonList("github.githubassets.com:443"), dialled);
    }

    @Test
    public void everyAllowListedHostCanBeTunnelledOnPort443Only() throws Exception {
        for (String host : GitHubAllowlist.allowedHosts()) {
            if ("api.github.com".equals(host)) {
                continue; // used below as the "unreachable" host
            }
            try (Socket client = open()) {
                assertEquals(host, "HTTP/1.1 200 Connection established",
                        send(client, "CONNECT " + host + ":443 HTTP/1.1\r\n\r\n"));
            }
            try (Socket client = open()) {
                assertEquals(host + ":8443", "HTTP/1.1 403 Forbidden",
                        send(client, "CONNECT " + host + ":8443 HTTP/1.1\r\n\r\n"));
            }
            try (Socket client = open()) {
                assertEquals(host + ":80", "HTTP/1.1 403 Forbidden",
                        send(client, "CONNECT " + host + ":80 HTTP/1.1\r\n\r\n"));
            }
        }
    }

    @Test
    public void refusedHostsGet403AndAreNeverDialled() throws Exception {
        String[][] cases = {
                {"evil.example:443", "HOST_NOT_ALLOWED"},
                {"github.com.evil.example:443", "HOST_NOT_ALLOWED"},
                {"github.com.:443", "INVALID_HOST"},
                {"evilgithub.com:443", "HOST_NOT_ALLOWED"},
                {"xn--gthub-5va.example:443", "HOST_NOT_ALLOWED"},
                {"github.com@evil.example:443", "USERINFO_PRESENT"},
                {"127.0.0.1:443", "INVALID_HOST"},
                {"[::1]:443", "IP_LITERAL_HOST"},
                {"localhost:443", "INVALID_HOST"},
                {"www.googleapis.com:443", "HOST_NOT_ALLOWED"},
                {"mail.google.com:443", "HOST_NOT_ALLOWED"},
                {"\u0430github.com:443", "NON_ASCII_HOST"},
        };
        for (String[] c : cases) {
            try (Socket client = open()) {
                assertEquals(c[0], "HTTP/1.1 403 Forbidden",
                        send(client, "CONNECT " + c[0] + " HTTP/1.1\r\nHost: " + c[0] + "\r\n\r\n"));
                // The proxy closes a refused connection.
                assertEquals(-1, readWithTimeout(client.getInputStream()));
            }
        }
        assertTrue("nothing refused may ever be dialled: " + dialled, dialled.isEmpty());
        assertEquals(cases.length, metrics.count(PolicyMetrics.Counter.BLOCKED_TUNNELS));
        assertEquals(cases.length, refused.size());
        for (int i = 0; i < cases.length; i++) {
            assertEquals(cases[i][0], BlockReason.valueOf(cases[i][1]), refused.get(i).reason());
        }
        // ... and each refusal is in the diagnostics report.
        assertEquals(cases.length, metrics.recordedBlockCount());
        assertTrue(metrics.recentBlocks().get(0).url().startsWith("connect://"));
    }

    @Test
    public void onlyWellFormedConnectRequestsAreServed() throws Exception {
        String[] bad = {
                "GET http://github.com/ HTTP/1.1\r\nHost: github.com\r\n\r\n",
                "GET / HTTP/1.1\r\nHost: github.com\r\n\r\n",
                "CONNECT github.com HTTP/1.1\r\n\r\n",
                "CONNECT github.com: HTTP/1.1\r\n\r\n",
                "CONNECT :443 HTTP/1.1\r\n\r\n",
                "CONNECT github.com:443 HTTP/2.0\r\n\r\n",
                "CONNECT github.com:443\r\n\r\n",
                "CONNECT  github.com:443 HTTP/1.1\r\n\r\n",
                "CONNECT github.com:4430000 HTTP/1.1\r\n\r\n",
                "CONNECT github.com:0 HTTP/1.1\r\n\r\n",
                "CONNECT github.com:443x HTTP/1.1\r\n\r\n",
                "connect github.com:443 HTTP/1.1\r\n\r\n",
                "\r\n\r\n",
        };
        for (String request : bad) {
            try (Socket client = open()) {
                assertEquals(request.replace("\r\n", "\\r\\n"), "HTTP/1.1 400 Bad Request", send(client, request));
            }
        }
        assertTrue(dialled.isEmpty());
        assertEquals(bad.length, metrics.count(PolicyMetrics.Counter.BLOCKED_TUNNELS));
        assertEquals(bad.length, badRequests.size());
        assertEquals("GET http://github.com/ HTTP/1.1", badRequests.get(0));
        assertEquals("", badRequests.get(badRequests.size() - 1));
    }

    @Test
    public void loggedRequestLinesAreSafeToPrint() {
        assertEquals("CONNECT a:1 HTTP/1.1", EgressProxy.printableRequestLine("CONNECT a:1 HTTP/1.1\r\nHost: a\r\n"));
        assertEquals("GET ? HTTP/1.1", EgressProxy.printableRequestLine("GET \u0007 HTTP/1.1\r\n"));
        assertEquals("", EgressProxy.printableRequestLine(null));
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            huge.append('x');
        }
        assertEquals(160, EgressProxy.printableRequestLine(huge.toString()).length());
    }

    @Test
    public void oversizedHeadersAndHalfRequestsAreDropped() throws Exception {
        try (Socket client = open()) {
            // One byte over the limit and still no blank line: the proxy gives up on the head.
            StringBuilder huge = new StringBuilder("CONNECT github.com:443 HTTP/1.1\r\nX-Padding: ");
            while (huge.length() < 16 * 1024 + 1) {
                huge.append('a');
            }
            assertEquals("HTTP/1.1 400 Bad Request", send(client, huge.toString()));
        }
        try (Socket client = open()) {
            // No terminating blank line, then EOF.
            client.getOutputStream().write("CONNECT github.com:443 HTTP/1.1\r\n".getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            client.shutdownOutput();
            assertEquals("HTTP/1.1 400 Bad Request", readStatusLine(client.getInputStream()));
        }
        assertTrue(dialled.isEmpty());
    }

    @Test
    public void unreachableUpstreamIs502NotAFallback() throws Exception {
        try (Socket client = open()) {
            assertEquals("HTTP/1.1 502 Bad Gateway",
                    send(client, "CONNECT api.github.com:443 HTTP/1.1\r\n\r\n"));
        }
        assertEquals(Collections.singletonList("api.github.com:443"), dialled);
        assertEquals(0, metrics.count(PolicyMetrics.Counter.TUNNELS_OPENED));
        awaitNoOpenTunnels();
    }

    @Test
    public void bytesSentWithTheRequestHeadAreForwardedIntoTheTunnel() throws Exception {
        try (Socket client = open()) {
            // Head and first tunnel bytes in one write (a client that does not wait for the 200).
            String status = send(client, "CONNECT github.com:443 HTTP/1.1\r\n\r\nearly");
            assertEquals("HTTP/1.1 200 Connection established", status);
            assertEquals("early", complement(readBytes(client.getInputStream(), 5)));
        }
    }

    @Test
    public void manyConcurrentTunnelsWork() throws Exception {
        final int n = 24;
        final CountDownLatch done = new CountDownLatch(n);
        final List<Throwable> failures = Collections.synchronizedList(new ArrayList<Throwable>());
        for (int i = 0; i < n; i++) {
            final int id = i;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try (Socket client = open()) {
                        assertEquals("HTTP/1.1 200 Connection established",
                                send(client, "CONNECT github.com:443 HTTP/1.1\r\n\r\n"));
                        String payload = "payload-" + id;
                        client.getOutputStream().write(payload.getBytes(StandardCharsets.US_ASCII));
                        client.getOutputStream().flush();
                        assertEquals(payload, complement(readBytes(client.getInputStream(), payload.length())));
                    } catch (Throwable e) {
                        failures.add(e);
                    } finally {
                        done.countDown();
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        }
        assertTrue(done.await(30, TimeUnit.SECONDS));
        assertTrue("failures: " + failures, failures.isEmpty());
        assertEquals(n, metrics.count(PolicyMetrics.Counter.TUNNELS_OPENED));
        // Tunnels close when their clients do.
        awaitNoOpenTunnels();
    }

    @Test
    public void closingTheProxyStopsTheListener() throws Exception {
        int port = proxy.port();
        proxy.close();
        assertFalse(proxy.isRunning());
        try (Socket client = new Socket()) {
            client.connect(new InetSocketAddress(EgressProxy.loopbackAddress(), port), 1000);
            // Some platforms accept and reset, others refuse outright; either way no tunnel is served.
            client.setSoTimeout(1000);
            client.getOutputStream().write("CONNECT github.com:443 HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            assertNull(readStatusLine(client.getInputStream()));
        } catch (IOException expected) {
            // refused: fine
        }
        assertTrue(dialled.isEmpty());
    }

    @Test
    public void connectRequestParserIsStrict() {
        EgressProxy.ConnectRequest ok = EgressProxy.ConnectRequest.parse("CONNECT github.com:443 HTTP/1.1\r\nHost: x\r\n\r\n");
        assertNotNull(ok);
        assertEquals("github.com", ok.host);
        assertEquals(443, ok.port);
        assertNull(EgressProxy.ConnectRequest.parse(null));
        assertNull(EgressProxy.ConnectRequest.parse(""));
        assertNull(EgressProxy.ConnectRequest.parse("CONNECT github.com:443 HTTP/1.1 extra\r\n"));
        assertNull(EgressProxy.ConnectRequest.parse("CONNECT github.com:65536 HTTP/1.1\r\n"));
        assertNull(EgressProxy.ConnectRequest.parse("CONNECT github.com:-1 HTTP/1.1\r\n"));
        EgressProxy.ConnectRequest v6 = EgressProxy.ConnectRequest.parse("CONNECT [::1]:443 HTTP/1.1\r\n");
        assertNotNull("syntactically fine; the policy refuses it", v6);
        assertEquals("[::1]", v6.host);
    }

    // --- helpers ----------------------------------------------------------------------------------

    private Socket open() throws IOException {
        Socket s = new Socket();
        // Exactly what Chromium is told: the IPv4 loopback literal, never the platform default.
        s.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), proxy.port()), 2000);
        s.setSoTimeout(5000);
        return s;
    }

    /** Write a request and return the status line of the reply. */
    private static String send(Socket client, String request) throws IOException {
        client.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
        client.getOutputStream().flush();
        String status = readStatusLine(client.getInputStream());
        assertNotNull("no reply to: " + request.replace("\r\n", "\\r\\n"), status);
        // Consume the rest of the head so that tunnel bytes are the next thing on the stream.
        drainHead(client.getInputStream(), status);
        return status;
    }

    private static String readStatusLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int prev = -1;
        int b;
        try {
            while ((b = in.read()) >= 0) {
                if (prev == '\r' && b == '\n') {
                    byte[] bytes = line.toByteArray();
                    return new String(bytes, 0, bytes.length - 1, StandardCharsets.ISO_8859_1);
                }
                line.write(b);
                prev = b;
            }
        } catch (java.net.SocketTimeoutException e) {
            return null;
        }
        return line.size() == 0 ? null : line.toString("ISO-8859-1");
    }

    /** Read header lines until the blank line (the status line has already been consumed). */
    private static void drainHead(InputStream in, String status) throws IOException {
        while (true) {
            String line = readStatusLine(in);
            if (line == null || line.isEmpty()) {
                return;
            }
        }
    }

    private void awaitNoOpenTunnels() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (proxy.openTunnelCount() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(0, proxy.openTunnelCount());
    }

    /** Undo the far end's transformation. */
    private static String complement(byte[] bytes) {
        byte[] out = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            out[i] = (byte) ~bytes[i];
        }
        return new String(out, StandardCharsets.US_ASCII);
    }

    private static byte[] readBytes(InputStream in, int length) throws IOException {
        byte[] out = new byte[length];
        int off = 0;
        while (off < length) {
            int n = in.read(out, off, length - off);
            if (n < 0) {
                throw new IOException("EOF after " + off + " of " + length + " bytes");
            }
            off += n;
        }
        return out;
    }

    private static int readWithTimeout(InputStream in) throws IOException {
        try {
            return in.read();
        } catch (java.net.SocketTimeoutException e) {
            return -2;
        }
    }
}
