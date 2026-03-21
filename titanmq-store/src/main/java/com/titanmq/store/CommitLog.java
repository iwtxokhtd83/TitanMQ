package com.titanmq.store;

import com.titanmq.common.TitanMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Append-only commit log inspired by Kafka's log structure.
 *
 * <p>Each partition has its own CommitLog composed of multiple {@link LogSegment}s.
 * Messages are appended sequentially and assigned monotonically increasing offsets.
 * Old segments are rolled when they exceed the configured size threshold.
 *
 * <p>Key design choices:
 * <ul>
 *   <li>Memory-mapped I/O for zero-copy reads</li>
 *   <li>Append-only writes for sequential disk I/O (maximizes throughput)</li>
 *   <li>Segment-based rolling for efficient cleanup and compaction</li>
 * </ul>
 */
public class CommitLog {

    private static final Logger log = LoggerFactory.getLogger(CommitLog.class);

    private final Path directory;
    private final long maxSegmentSize;
    private final List<LogSegment> segments = new ArrayList<>();
    private volatile LogSegment activeSegment;
    private final AtomicLong nextOffset = new AtomicLong(0);

    public CommitLog(Path directory, long maxSegmentSize) throws IOException {
        this.directory = directory;
        this.maxSegmentSize = maxSegmentSize;
        this.directory.toFile().mkdirs();
        rollNewSegment();
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

    private void rollNewSegment() throws IOException {
        long baseOffset = nextOffset.get();
        LogSegment segment = new LogSegment(directory, baseOffset, maxSegmentSize);
        segments.add(segment);
        activeSegment = segment;
        log.info("Rolled new segment at offset {} in {}", baseOffset, directory);
    }

    public void close() throws IOException {
        for (LogSegment segment : segments) {
            segment.close();
        }
    }
}
