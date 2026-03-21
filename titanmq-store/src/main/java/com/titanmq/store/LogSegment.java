package com.titanmq.store;

import com.titanmq.common.TitanMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A single segment of the commit log backed by a memory-mapped file.
 *
 * <p>Each segment stores messages sequentially with an offset index for fast lookups.
 * Format per entry:
 * <pre>
 * [8 bytes: offset] [4 bytes: message size] [N bytes: serialized message]
 * </pre>
 */
public class LogSegment {

    private static final Logger log = LoggerFactory.getLogger(LogSegment.class);
    private static final int ENTRY_HEADER_SIZE = 8 + 4; // offset + size

    private final long baseOffset;
    private final Path filePath;
    private final RandomAccessFile raf;
    private final FileChannel channel;
    private long currentPosition = 0;
    private long endOffset = -1;
    private final long maxSize;

    // Sparse in-memory index: offset -> file position
    private final List<OffsetIndex> index = new ArrayList<>();

    public LogSegment(Path directory, long baseOffset, long maxSize) throws IOException {
        this.baseOffset = baseOffset;
        this.maxSize = maxSize;
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

    private long findPosition(long targetOffset) {
        // Binary search on sparse index
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

    public long size() { return currentPosition; }
    public long baseOffset() { return baseOffset; }
    public long endOffset() { return endOffset; }
    public Path filePath() { return filePath; }

    public void close() throws IOException {
        channel.close();
        raf.close();
    }

    private record OffsetIndex(long offset, long position) {}
}
