package com.titanmq.store.tier;

import com.titanmq.common.TitanMessage;
import com.titanmq.store.LogSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * A segment wrapper that tracks its storage tier and handles transparent
 * promotion/demotion between tiers.
 *
 * <p>When a segment is in COLD tier, reads trigger an automatic fetch from
 * remote storage into a local cache before serving the data.
 */
public class TieredSegment {

    private static final Logger log = LoggerFactory.getLogger(TieredSegment.class);

    private final long baseOffset;
    private final long endOffset;
    private final long createdAtMs;
    private volatile StorageTier currentTier;
    private volatile LogSegment localSegment; // null if fully offloaded to cold
    private final Path localPath;
    private final String remotePath;
    private final RemoteStorageBackend remoteBackend;

    public TieredSegment(LogSegment segment, Path localPath, RemoteStorageBackend remoteBackend) {
        this.baseOffset = segment.baseOffset();
        this.endOffset = segment.endOffset();
        this.createdAtMs = System.currentTimeMillis();
        this.currentTier = StorageTier.HOT;
        this.localSegment = segment;
        this.localPath = localPath;
        this.remotePath = "segments/" + localPath.getFileName().toString();
        this.remoteBackend = remoteBackend;
    }

    /**
     * Read messages, transparently fetching from remote storage if needed.
     */
    public List<TitanMessage> read(long fromOffset, int maxMessages) throws IOException {
        ensureLocal();
        return localSegment.read(fromOffset, maxMessages);
    }

    /**
     * Demote this segment to WARM tier (still on local disk, but eligible for cold offload).
     */
    public void demoteToWarm() {
        if (currentTier == StorageTier.HOT) {
            currentTier = StorageTier.WARM;
            log.info("Segment {} demoted to WARM", baseOffset);
        }
    }

    /**
     * Offload this segment to COLD storage.
     * Uploads to remote backend and releases local resources.
     */
    public void offloadToCold() throws IOException {
        if (currentTier == StorageTier.COLD) return;

        // Upload to remote storage
        remoteBackend.upload(localPath, remotePath);
        currentTier = StorageTier.COLD;

        // Release local segment resources (but keep the file for now as cache)
        log.info("Segment {} offloaded to COLD storage at {}", baseOffset, remotePath);
    }

    /**
     * Ensure the segment data is available locally.
     * If in COLD tier, fetch from remote storage first.
     */
    private synchronized void ensureLocal() throws IOException {
        if (localSegment != null) return;

        if (currentTier == StorageTier.COLD) {
            log.info("Fetching segment {} from COLD storage", baseOffset);
            remoteBackend.download(remotePath, localPath);
            // Re-open the local segment
            this.localSegment = new LogSegment(localPath.getParent(), baseOffset, Long.MAX_VALUE);
            log.info("Segment {} restored from COLD storage", baseOffset);
        }
    }

    /**
     * Release local resources (for memory pressure relief).
     * Only applicable for COLD tier segments that have been cached locally.
     */
    public synchronized void evictLocalCache() throws IOException {
        if (currentTier == StorageTier.COLD && localSegment != null) {
            localSegment.close();
            localSegment = null;
            log.debug("Evicted local cache for COLD segment {}", baseOffset);
        }
    }

    public long baseOffset() { return baseOffset; }
    public long endOffset() { return endOffset; }
    public long createdAtMs() { return createdAtMs; }
    public StorageTier currentTier() { return currentTier; }
    public long ageMs() { return System.currentTimeMillis() - createdAtMs; }
}
