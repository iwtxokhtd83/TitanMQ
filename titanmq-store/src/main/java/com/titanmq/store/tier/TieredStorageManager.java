package com.titanmq.store.tier;

import com.titanmq.common.TitanMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Manages the lifecycle of segments across storage tiers.
 *
 * <p>Tiered storage flow:
 * <pre>
 *   HOT (active, memory-mapped)
 *     │ after hotRetention expires
 *     ▼
 *   WARM (local SSD, read-optimized)
 *     │ after warmRetention expires
 *     ▼
 *   COLD (remote/object storage, cost-optimized)
 * </pre>
 *
 * <p>Key benefits over Kafka's single-tier approach:
 * <ul>
 *   <li>Dramatically lower storage costs for long retention</li>
 *   <li>Hot data stays fast, cold data stays cheap</li>
 *   <li>Transparent to consumers — reads work across all tiers</li>
 * </ul>
 */
public class TieredStorageManager {

    private static final Logger log = LoggerFactory.getLogger(TieredStorageManager.class);

    private final TierConfig config;
    private final RemoteStorageBackend remoteBackend;
    private final List<TieredSegment> segments = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public TieredStorageManager(TierConfig config, RemoteStorageBackend remoteBackend) {
        this.config = config;
        this.remoteBackend = remoteBackend;
    }

    /**
     * Start the background tier management process.
     */
    public void start() {
        scheduler.scheduleAtFixedRate(this::runTierCheck,
                config.tierCheckIntervalMs(), config.tierCheckIntervalMs(), TimeUnit.MILLISECONDS);
        log.info("Tiered storage manager started. Hot={}h, Warm={}d, Cold={}",
                config.hotRetention().toHours(),
                config.warmRetention().toDays(),
                config.coldTierEnabled() ? "enabled" : "disabled");
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    /**
     * Register a segment for tier management.
     */
    public void addSegment(TieredSegment segment) {
        segments.add(segment);
    }

    /**
     * Read messages across all tiers transparently.
     */
    public List<TitanMessage> read(long fromOffset, int maxMessages) throws IOException {
        List<TitanMessage> result = new ArrayList<>();
        for (TieredSegment segment : segments) {
            if (segment.endOffset() < fromOffset) continue;
            List<TitanMessage> msgs = segment.read(fromOffset, maxMessages - result.size());
            result.addAll(msgs);
            if (result.size() >= maxMessages) break;
        }
        return result;
    }

    /**
     * Periodic check to move segments between tiers based on age.
     */
    private void runTierCheck() {
        try {
            long hotThresholdMs = config.hotRetention().toMillis();
            long warmThresholdMs = config.warmRetention().toMillis();

            for (TieredSegment segment : segments) {
                long age = segment.ageMs();

                if (segment.currentTier() == StorageTier.HOT && age > hotThresholdMs) {
                    segment.demoteToWarm();
                }

                if (config.coldTierEnabled() &&
                        segment.currentTier() == StorageTier.WARM && age > warmThresholdMs) {
                    segment.offloadToCold();
                }
            }
        } catch (Exception e) {
            log.error("Error during tier check", e);
        }
    }

    /**
     * Get storage statistics across all tiers.
     */
    public TierStats getStats() {
        int hot = 0, warm = 0, cold = 0;
        for (TieredSegment segment : segments) {
            switch (segment.currentTier()) {
                case HOT -> hot++;
                case WARM -> warm++;
                case COLD -> cold++;
            }
        }
        return new TierStats(hot, warm, cold, segments.size());
    }

    public record TierStats(int hotSegments, int warmSegments, int coldSegments, int totalSegments) {
        @Override
        public String toString() {
            return "TierStats{hot=%d, warm=%d, cold=%d, total=%d}"
                    .formatted(hotSegments, warmSegments, coldSegments, totalSegments);
        }
    }
}
