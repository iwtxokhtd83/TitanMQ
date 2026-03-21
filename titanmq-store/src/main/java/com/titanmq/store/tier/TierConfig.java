package com.titanmq.store.tier;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Configuration for tiered storage.
 */
public class TierConfig {

    private Duration hotRetention = Duration.ofHours(1);
    private Duration warmRetention = Duration.ofDays(7);
    private Path warmStoragePath = Path.of("data/titanmq/warm");
    private Path coldStoragePath = Path.of("data/titanmq/cold");
    private long tierCheckIntervalMs = 60_000; // 1 minute
    private boolean coldTierEnabled = true;

    public Duration hotRetention() { return hotRetention; }
    public TierConfig hotRetention(Duration d) { this.hotRetention = d; return this; }

    public Duration warmRetention() { return warmRetention; }
    public TierConfig warmRetention(Duration d) { this.warmRetention = d; return this; }

    public Path warmStoragePath() { return warmStoragePath; }
    public TierConfig warmStoragePath(Path p) { this.warmStoragePath = p; return this; }

    public Path coldStoragePath() { return coldStoragePath; }
    public TierConfig coldStoragePath(Path p) { this.coldStoragePath = p; return this; }

    public long tierCheckIntervalMs() { return tierCheckIntervalMs; }
    public TierConfig tierCheckIntervalMs(long ms) { this.tierCheckIntervalMs = ms; return this; }

    public boolean coldTierEnabled() { return coldTierEnabled; }
    public TierConfig coldTierEnabled(boolean enabled) { this.coldTierEnabled = enabled; return this; }
}
