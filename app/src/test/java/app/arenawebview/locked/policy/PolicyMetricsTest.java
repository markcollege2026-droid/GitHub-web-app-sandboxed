package app.arenawebview.locked.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * The diagnostics counters are what the on-device tests assert against, so they need to be trustworthy:
 * exact counts, bounded memory and no user data (query strings/fragments are stripped).
 */
public class PolicyMetricsTest {

    @Test
    public void countersStartAtZeroAndIncrement() {
        PolicyMetrics metrics = new PolicyMetrics();
        for (PolicyMetrics.Counter counter : PolicyMetrics.Counter.values()) {
            assertEquals(0, metrics.count(counter));
        }
        metrics.increment(PolicyMetrics.Counter.BLOCKED_NAVIGATIONS);
        metrics.increment(PolicyMetrics.Counter.BLOCKED_NAVIGATIONS, 4);
        assertEquals(5, metrics.count(PolicyMetrics.Counter.BLOCKED_NAVIGATIONS));
        assertEquals(0, metrics.count(PolicyMetrics.Counter.BLOCKED_POPUPS));
    }

    @Test
    public void recentBlocksAreNewestFirstAndBounded() {
        PolicyMetrics metrics = new PolicyMetrics();
        for (int i = 0; i < 120; i++) {
            metrics.recordBlock(BlockReason.HOST_NOT_ALLOWED, "evil" + i + ".example", "https://evil" + i + ".example/");
        }
        List<PolicyMetrics.BlockEvent> events = metrics.recentBlocks();
        assertEquals(50, events.size());
        assertEquals("evil119.example", events.get(0).host());
        assertEquals("evil70.example", events.get(49).host());
        assertEquals(120, metrics.recordedBlockCount());
    }

    @Test
    public void recordedUrlsDropQueryStringAndFragment() {
        PolicyMetrics metrics = new PolicyMetrics();
        metrics.recordBlock(BlockReason.HOST_NOT_ALLOWED, "evil.example",
                "https://evil.example/path?token=SECRET#private");
        String recorded = metrics.recentBlocks().get(0).url();
        assertEquals("https://evil.example/path", recorded);
        assertFalse(recorded.contains("SECRET"));
        assertFalse(recorded.contains("private"));
    }

    @Test
    public void veryLongUrlsAreTruncated() {
        PolicyMetrics metrics = new PolicyMetrics();
        StringBuilder longPath = new StringBuilder("https://evil.example/");
        for (int i = 0; i < 500; i++) {
            longPath.append('x');
        }
        metrics.recordBlock(BlockReason.HOST_NOT_ALLOWED, "evil.example", longPath.toString());
        assertTrue(metrics.recentBlocks().get(0).url().length() <= 203);
    }

    @Test
    public void snapshotMentionsEveryCounterAndTheRecentBlocks() {
        PolicyMetrics metrics = new PolicyMetrics();
        metrics.increment(PolicyMetrics.Counter.BLOCKED_POPUPS, 3);
        metrics.recordBlock(BlockReason.HOST_NOT_ALLOWED, "evil.example", "https://evil.example/");
        String snapshot = metrics.snapshot();
        for (PolicyMetrics.Counter counter : PolicyMetrics.Counter.values()) {
            assertTrue("snapshot must mention " + counter, snapshot.contains(counter.name()));
        }
        assertTrue(snapshot.contains("evil.example"));
        assertTrue(snapshot.contains("HOST_NOT_ALLOWED"));
    }

    @Test
    public void resetClearsEverything() {
        PolicyMetrics metrics = new PolicyMetrics();
        metrics.increment(PolicyMetrics.Counter.BLOCKED_REQUESTS, 7);
        metrics.recordBlock(BlockReason.HOST_NOT_ALLOWED, "evil.example", "https://evil.example/");
        metrics.reset();
        assertEquals(0, metrics.count(PolicyMetrics.Counter.BLOCKED_REQUESTS));
        assertTrue(metrics.recentBlocks().isEmpty());
        assertEquals(0, metrics.recordedBlockCount());
    }
}
