package com.titanmq.store;

import com.titanmq.common.TitanMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Append-only commit log with crash recovery and segment retention.
 *
 * <p>Each partition has its own CommitLog composed of multiple {@link LogSegment}s.
 * Messages are appended sequentially and assigned monotonically increasing offsets.
 *
 * <p>Key features:
 * <ul>
 *   <li>Crash recovery: scans existing segments on startup, rebuilds index</li>
 *   <li>Configurable fsync: EVERY_MESSAGE, PERIODIC, or NONE</li>
 *   <li>Segment retention: time-based and size-based cleanup</li>
 *   <li>Periodic flush thread for PERIODIC fsync policy</li>
 * </ul>
 */
public class CommitLog {

    private static final Logger log = LoggerFactory.getLogger(CommitLog.class);

    private final Path directory;
    private final long maxSegmentSize;
    private final FlushPolicy flushPolicy;
    private final long flushIntervalMs;
    private final List<LogSegment> segments = new ArrayList<>();
    private volatile LogSegment activeSegment;
    private final AtomicLong nextOffset = new AtomicLong(0);

    // Retention settings
    private long retentionMs = -1;       // -1 = infinite
    private long retentionBytes = -1;    // -1 = infinite
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "commitlog-maintenance");
        t.setDaemon(true);
        return t;
    });

    public CommitLog(Path directory, long maxSegmentSize) throws IOException {
        this(directory, maxSegmentSize, FlushPolicy.NONE, 1000);
    }

    public CommitLog(Path directory, long maxSegmentSize, FlushPolicy flushPolicy, long flushIntervalMs) throws IOException {
        this.directory = directory;
        this.maxSegmentSize = maxSegmentSize;
        this.flushPolicy = flushPolicy;
        this.flushIntervalMs = flushIntervalMs;
        this.directory.toFile().mkdirs();

        // Recover existing segments or create a fresh one
        recover();

        // Start periodic flush if configured
        if (flushPolicy == FlushPolicy.PERIODIC && flushIntervalMs > 0) {
            scheduler.scheduleAtFixedRate(this::periodicFlush,
                    flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Scan the data directory for existing segment files and recover state.
     * If no segments exist, create a fresh one.
     */
    private void recover() throws IOException {
        List<Path> segmentFiles = new ArrayList<>();

        if (Files.exists(directory)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.log")) {
                for (Path path : stream) {
                    segmentFiles.add(path);
                }
            }
        }

        if (segmentFiles.isEmpty()) {
            // Fresh start
            rollNewSegment();
            log.info("No existing segments in {}, starting fresh", directory);
            return;
        }

        // Sort by base offset (filename is the zero-padded offset)
        segmentFiles.sort(Comparator.comparing(p -> p.getFileName().toString()));

        for (Path segFile : segmentFiles) {
            String fileName = segFile.getFileName().toString();
            long baseOffset = Long.parseLong(fileName.replace(".log", ""));
            LogSegment segment = new LogSegment(directory, baseOffset, maxSegmentSize, flushPolicy);
            segment.recover();
            segments.add(segment);
        }

        // Set nextOffset from the last segment
        LogSegment lastSegment = segments.getLast();
        if (lastSegment.endOffset() >= 0) {
            nextOffset.set(lastSegment.endOffset() + 1);
        } else {
            nextOffset.set(lastSegment.baseOffset());
        }
        activeSegment = lastSegment;

        log.info("Recovered {} segments in {}, nextOffset={}", segments.size(), directory, nextOffset.get());
    }

    /**
     * Append a message to the log. Returns the assigned offset.
     */
    public synchronized long append(TitanMessage message) throws IOException {
        ByteBuffer data = message.serialize();
        if (activeSegment.size() + data.remaining() > maxSegmentSize) {
            rollNewSegment();
        }
        long offset = nextOffset.getAndIncrement();
        activeSegment.append(offset, data);
        return offset;
    }

    /**
     * Read messages starting from the given offset, up to maxMessages.
     */
    public List<TitanMessage> read(long fromOffset, int maxMessages) throws IOException {
        List<TitanMessage> result = new ArrayList<>();
        for (LogSegment segment : segments) {
            if (segment.endOffset() < fromOffset) continue;
            List<TitanMessage> msgs = segment.read(fromOffset, maxMessages - result.size());
            result.addAll(msgs);
            if (result.size() >= maxMessages) break;
        }
        return result;
    }

    /**
     * Get the latest offset (exclusive — next offset to be assigned).
     */
    public long latestOffset() {
        return nextOffset.get();
    }

    // ─── Retention / Cleanup (Fix #9) ───

    /**
     * Configure time-based retention. Segments older than this are deleted.
     */
    public CommitLog retentionTime(Duration retention) {
        this.retentionMs = retention.toMillis();
        startRetentionCleanup();
        return this;
    }

    /**
     * Configure size-based retention. Oldest segments deleted when total exceeds this.
     */
    public CommitLog retentionBytes(long maxBytes) {
        this.retentionBytes = maxBytes;
        startRetentionCleanup();
        return this;
    }

    private void startRetentionCleanup() {
        // Run cleanup every 60 seconds
        scheduler.scheduleAtFixedRate(this::cleanupExpiredSegments,
                60, 60, TimeUnit.SECONDS);
    }

    /**
     * Delete segments that exceed retention policies.
     * Never deletes the active (last) segment.
     */
    synchronized void cleanupExpiredSegments() {
        if (segments.size() <= 1) return;

        List<LogSegment> toDelete = new ArrayList<>();
        long now = System.currentTimeMillis();

        // Time-based retention
        if (retentionMs > 0) {
            for (int i = 0; i < segments.size() - 1; i++) {
                LogSegment seg = segments.get(i);
                try {
                    long lastModified = Files.getLastModifiedTime(seg.filePath()).toMillis();
                    if (now - lastModified > retentionMs) {
                        toDelete.add(seg);
                    }
                } catch (IOException e) {
                    log.warn("Failed to check segment age: {}", seg.filePath(), e);
                }
            }
        }

        // Size-based retention
        if (retentionBytes > 0) {
            long totalSize = segments.stream().mapToLong(LogSegment::size).sum();
            for (int i = 0; i < segments.size() - 1 && totalSize > retentionBytes; i++) {
                LogSegment seg = segments.get(i);
                if (!toDelete.contains(seg)) {
                    toDelete.add(seg);
                }
                totalSize -= seg.size();
            }
        }

        // Delete
        for (LogSegment seg : toDelete) {
            try {
                seg.delete();
                segments.remove(seg);
                log.info("Deleted expired segment {} (baseOffset={})", seg.filePath().getFileName(), seg.baseOffset());
            } catch (IOException e) {
                log.error("Failed to delete segment {}", seg.filePath(), e);
            }
        }
    }

    // ─── Flush ───

    private void periodicFlush() {
        try {
            if (activeSegment != null) {
                activeSegment.flush();
            }
        } catch (IOException e) {
            log.error("Periodic flush failed", e);
        }
    }

    private void rollNewSegment() throws IOException {
        long baseOffset = nextOffset.get();
        LogSegment segment = new LogSegment(directory, baseOffset, maxSegmentSize, flushPolicy);
        segments.add(segment);
        activeSegment = segment;
        log.info("Rolled new segment at offset {} in {}", baseOffset, directory);
    }

    public void close() throws IOException {
        scheduler.shutdownNow();
        for (LogSegment segment : segments) {
            segment.close();
        }
    }
}
