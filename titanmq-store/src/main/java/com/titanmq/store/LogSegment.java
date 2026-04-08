package com.titanmq.store;

import com.titanmq.common.TitanMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A single segment of the commit log backed by a file with optional fsync.
 *
 * <p>Each segment stores messages sequentially with an offset index for fast lookups.
 * Format per entry:
 * <pre>
 * [8 bytes: offset] [4 bytes: message size] [N bytes: serialized message]
 * </pre>
 */
public class LogSegment {

    private static final Logger log = LoggerFactory.getLogger(LogSegment.class);
    static final int ENTRY_HEADER_SIZE = 8 + 4; // offset + size

    private final long baseOffset;
    private final Path filePath;
    private final RandomAccessFile raf;
    private final FileChannel channel;
    private long currentPosition = 0;
    private long endOffset = -1;
    private final long maxSize;
    private final FlushPolicy flushPolicy;

    // Sparse in-memory index: offset -> file position
    private final List<OffsetIndex> index = new ArrayList<>();

    public LogSegment(Path directory, long baseOffset, long maxSize) throws IOException {
        this(directory, baseOffset, maxSize, FlushPolicy.NONE);
    }

    public LogSegment(Path directory, long baseOffset, long maxSize, FlushPolicy flushPolicy) throws IOException {
        this.baseOffset = baseOffset;
        this.maxSize = maxSize;
        this.flushPolicy = flushPolicy;
        this.filePath = directory.resolve(String.format("%020d.log", baseOffset));
        this.raf = new RandomAccessFile(filePath.toFile(), "rw");
        this.channel = raf.getChannel();
    }

    public synchronized void append(long offset, ByteBuffer data) throws IOException {
        int messageSize = data.remaining();
        ByteBuffer header = ByteBuffer.allocate(ENTRY_HEADER_SIZE);
        header.putLong(offset);
        header.putInt(messageSize);
        header.flip();

        // Record index entry
        index.add(new OffsetIndex(offset, currentPosition));

        channel.position(currentPosition);
        channel.write(header);
        channel.write(data);

        currentPosition += ENTRY_HEADER_SIZE + messageSize;
        endOffset = offset;

        if (flushPolicy == FlushPolicy.EVERY_MESSAGE) {
            channel.force(false);
        }
    }

    /**
     * Force flush buffered data to disk.
     * Called by CommitLog's periodic flush thread when using PERIODIC flush policy.
     */
    public synchronized void flush() throws IOException {
        channel.force(false);
    }

    public List<TitanMessage> read(long fromOffset, int maxMessages) throws IOException {
        List<TitanMessage> messages = new ArrayList<>();
        long filePos = findPosition(fromOffset);

        while (messages.size() < maxMessages && filePos < currentPosition) {
            ByteBuffer header = ByteBuffer.allocate(ENTRY_HEADER_SIZE);
            channel.read(header, filePos);
            header.flip();

            long offset = header.getLong();
            int messageSize = header.getInt();

            if (offset < fromOffset) {
                filePos += ENTRY_HEADER_SIZE + messageSize;
                continue;
            }

            ByteBuffer messageData = ByteBuffer.allocate(messageSize);
            channel.read(messageData, filePos + ENTRY_HEADER_SIZE);
            messageData.flip();

            messages.add(TitanMessage.deserialize(messageData));
            filePos += ENTRY_HEADER_SIZE + messageSize;
        }
        return messages;
    }

    /**
     * Recover segment state by scanning the file from the beginning.
     * Rebuilds the in-memory index and recovers currentPosition/endOffset.
     */
    void recover() throws IOException {
        long fileSize = channel.size();
        long pos = 0;
        index.clear();
        endOffset = -1;

        while (pos + ENTRY_HEADER_SIZE <= fileSize) {
            ByteBuffer header = ByteBuffer.allocate(ENTRY_HEADER_SIZE);
            int bytesRead = channel.read(header, pos);
            if (bytesRead < ENTRY_HEADER_SIZE) break;
            header.flip();

            long offset = header.getLong();
            int messageSize = header.getInt();

            if (messageSize <= 0 || pos + ENTRY_HEADER_SIZE + messageSize > fileSize) {
                // Truncated entry (partial write before crash) — truncate file here
                log.warn("Truncated entry at position {} in {}, truncating segment", pos, filePath);
                channel.truncate(pos);
                break;
            }

            index.add(new OffsetIndex(offset, pos));
            endOffset = offset;
            pos += ENTRY_HEADER_SIZE + messageSize;
        }

        currentPosition = pos;
        log.info("Recovered segment {}: {} entries, endOffset={}", filePath.getFileName(), index.size(), endOffset);
    }

    private long findPosition(long targetOffset) {
        long pos = 0;
        int lo = 0, hi = index.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (index.get(mid).offset() <= targetOffset) {
                pos = index.get(mid).position();
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return pos;
    }

    /**
     * Delete this segment's file from disk.
     */
    public void delete() throws IOException {
        close();
        if (!filePath.toFile().delete()) {
            log.warn("Failed to delete segment file {}", filePath);
        }
    }

    public long size() { return currentPosition; }
    public long baseOffset() { return baseOffset; }
    public long endOffset() { return endOffset; }
    public Path filePath() { return filePath; }

    public void close() throws IOException {
        channel.close();
        raf.close();
    }

    record OffsetIndex(long offset, long position) {}
}
